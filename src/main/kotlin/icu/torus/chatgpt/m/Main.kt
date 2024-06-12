package icu.torus.chatgpt.m

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.aallam.openai.api.exception.InvalidRequestException
import com.aallam.openai.api.image.ImageCreation
import com.aallam.openai.api.image.ImageSize
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.ProxyConfig
import io.github.irgaly.kottage.Kottage
import io.github.irgaly.kottage.KottageEnvironment
import io.github.irgaly.kottage.platform.KottageContext
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.folivo.trixnity.api.client.defaultTrixnityHttpClientFactory
import net.folivo.trixnity.client.*
import net.folivo.trixnity.client.media.okio.OkioMediaStore
import net.folivo.trixnity.client.room.message.*
import net.folivo.trixnity.client.store.*
import net.folivo.trixnity.client.store.repository.exposed.createExposedRepositoriesModule
import net.folivo.trixnity.clientserverapi.client.SyncState
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.utils.toByteArray
import net.folivo.trixnity.utils.toByteArrayFlow
import okio.Path.Companion.toPath
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds


private suspend fun run() = coroutineScope {

    val openai = OpenAI(
        System.getenv("OPENAI_TOKEN"),
        proxy = System.getenv("OPENAI_HTTP_PROXY")?.let { ProxyConfig.Http(it) }
    )

    val kottage = Kottage("kottage", System.getenv("KOTTAGE_DIR"), KottageEnvironment(KottageContext()), this)
    val threadStorage = kottage.storage("threads")

    val repositoriesModule = createExposedRepositoriesModule(Database.connect(System.getenv("H2_DB")))
    val mediaStore = OkioMediaStore(System.getenv("MEDIA_DIR").toPath())
    val baseUrl = Url(System.getenv("MATRIX_SERVER"))
    val matrixClientConfiguration: MatrixClientConfiguration.() -> Unit = {
        httpClientFactory = defaultTrixnityHttpClientFactory {
            engine {
                System.getenv("MATRIX_HTTP_PROXY")?.let { this.proxy = ProxyBuilder.http(it) }
            }
        }
    }
    val matrixClient = MatrixClient.fromStore(
        repositoriesModule,
        mediaStore,
        configuration = matrixClientConfiguration
    ).getOrThrow() ?: MatrixClient.login(
        baseUrl,
        IdentifierType.User(System.getenv("MATRIX_USER")),
        System.getenv("MATRIX_PASSWORD"),
        repositoriesModule = repositoriesModule,
        mediaStore = mediaStore,
        configuration = matrixClientConfiguration
    ).getOrThrow()

    val httpClient = HttpClient()

    launch {
        matrixClient.syncState.first { it == SyncState.RUNNING }
        matrixClient.room.getTimelineEventsFromNowOn(decryptionTimeout = 10.seconds).collect { timelineEvent ->
            if (timelineEvent.sender == matrixClient.userId)
                return@collect
            log.debug { timelineEvent }
            launch per@{
                runCatching {
                    val content = timelineEvent.content?.getOrNull()
                    suspend fun requestChatAndReply(
                        threadRootEventId: EventId,
                        chatThreadToRequest: ChatThread,
                        inThread: Boolean
                    ) {
                        log.debug { "[$threadRootEventId]: Requesting $chatThreadToRequest" }
                        val completion = openai.chatCompletion(chatThreadToRequest.toRequest())
                        threadStorage.putChatThread(
                            timelineEvent.roomId,
                            threadRootEventId,
                            chatThreadToRequest.addCompletion(completion)
                        )
                        matrixClient.room.sendMessage(timelineEvent.roomId) {
                            completion.first().content.let {
                                if (it == null)
                                    text("Empty response")
                                else
                                    markdown("[${chatThreadToRequest.modelId}] $it")
                                if (inThread)
                                    thread(timelineEvent)
                                else
                                    reply(timelineEvent)
                            }
                        }
                    }

                    tailrec suspend fun findRootEvent(event: TimelineEvent): EventId {
                        val relatesTo = event.relatesTo ?: return event.eventId
                        return if (relatesTo is RelatesTo.Thread)
                            relatesTo.eventId
                        else {
                            val previous = matrixClient.room.getTimelineEvent(
                                event.roomId,
                                relatesTo.eventId
                            ).first()
                            if (previous != null)
                                findRootEvent(previous)
                            else
                                event.eventId
                        }
                    }

                    // A message sent in thread automatically replies the latest on in thread
                    // if it's not explicitly replying some messages.
                    // So we handle messages that are either in thread or replying to us
                    suspend fun isInThreadOrReplyingToMe(): Boolean =
                        timelineEvent.relatesTo is RelatesTo.Thread || (
                                timelineEvent.relatesTo?.replyTo?.let {
                                    matrixClient.room.getTimelineEvent(timelineEvent.roomId, it.eventId).first()?.sender
                                }?.let { it == matrixClient.userId } ?: false)

                    if (content is RoomMessageEventContent.TextBased.Text) {
                        val body = content.body

                        // Ignore messages started with //
                        if (body.startsWith("//"))
                            return@per
                        val isReplying = timelineEvent.relatesTo != null

                        val isCommand = CommandParser.isCommand(body)
                        if (isCommand && !isReplying) {
                            // This message is calling a command
                            matrixClient.withSetTyping(timelineEvent.roomId) {
                                CommandParser.parse(body)
                                    .onSuccess { cmd ->
                                        log.debug { "Parsed command $cmd" }
                                        when (cmd) {

                                            is Command.ChatCommand -> {
                                                val chatThread = ChatThread.new(cmd.modelId, cmd.prompt)
                                                requestChatAndReply(timelineEvent.eventId, chatThread, true)
                                            }

                                            is Command.ImageCommand -> {
                                                val image = runCatching {
                                                    openai.imageURL(
                                                        ImageCreation(
                                                            prompt = cmd.prompt,
                                                            model = ModelId(cmd.modelId),
                                                            n = 1,
                                                            size = ImageSize.is1024x1024
                                                        )
                                                    ).first()
                                                }
                                                log.debug { image }
                                                image.onSuccess {
                                                    val bytes =
                                                        httpClient.get(it.url).readBytes()
                                                    val fileName = Url(it.url).pathSegments.last()
                                                    matrixClient.room.sendMessage(timelineEvent.roomId) {
                                                        image(
                                                            it.revisedPrompt ?: fileName,
                                                            bytes.toByteArrayFlow(),
                                                            null,
                                                            null,
                                                            fileName,
                                                            ContentType.Image.PNG,
                                                            bytes.size,
                                                            1024,
                                                            1024
                                                        )
                                                        reply(timelineEvent)
                                                    }
                                                }.onFailure {
                                                    if (it is InvalidRequestException) {
                                                        matrixClient.room.sendMessage(timelineEvent.roomId) {
                                                            text(it.message ?: "Invalid request")
                                                            reply(timelineEvent)
                                                        }
                                                    }
                                                }
                                            }


                                            Command.Pricing -> {
                                                matrixClient.room.sendMessage(timelineEvent.roomId) {
                                                    text(Consts.pricingMessage)
                                                    reply(timelineEvent)
                                                }
                                            }
                                        }

                                    }
                                    .onFailure {
                                        matrixClient.room.sendMessage(timelineEvent.roomId) {
                                            log.error { "Failed to parse command $body: ${it.message}" }
                                            text(Consts.helpMessage)
                                        }
                                    }

                            }
                        } else if (isInThreadOrReplyingToMe()) {
                            // This message is a reply
                            val threadRootEventId = findRootEvent(timelineEvent)
                            val chatThread = threadStorage.getChatThread(timelineEvent.roomId, threadRootEventId)
                                ?.addUserTextMessage(body)
                            if (chatThread != null) {
                                matrixClient.withSetTyping(timelineEvent.roomId) {
                                    requestChatAndReply(
                                        threadRootEventId,
                                        chatThread,
                                        timelineEvent.relatesTo is RelatesTo.Thread
                                    )
                                }
                            }

                        }
                        when {
                            body.lowercase().startsWith("ping") -> {
                                matrixClient.room.sendMessage(timelineEvent.roomId) {
                                    react(timelineEvent, "🏓")
                                }
                                matrixClient.room.sendMessage(timelineEvent.roomId) {
                                    text("pong")
                                    reply(timelineEvent)
                                }
                            }
                        }
                    }

                    if (content is RoomMessageEventContent.FileBased.Image) {
                        // This message is a reply
                        if (timelineEvent.relatesTo != null && isInThreadOrReplyingToMe()) {
                            val threadRootEventId = findRootEvent(timelineEvent)
                            val chatThread = threadStorage.getChatThread(timelineEvent.roomId, threadRootEventId)
                            if (chatThread != null) {
                                if (chatThread.modelId != "gpt-4o")
                                    matrixClient.room.sendMessage(timelineEvent.roomId) {
                                        text("Only gpt-4o supports images")
                                        reply(timelineEvent)
                                    }
                                else {
                                    matrixClient.withSetTyping(timelineEvent.roomId) {
                                        val imageType =
                                            content.info?.mimeType?.removePrefix("image/")
                                                ?: File(content.body).extension
                                        val image = if (timelineEvent.isEncrypted) {
                                            content.file?.let { file ->
                                                matrixClient.media.getEncryptedMedia(file).getOrThrow().toByteArray()
                                            }
                                        } else {
                                            content.url?.let { matrixClient.media.getMedia(it).getOrThrow() }
                                                ?.toByteArray()
                                        }
                                        if (image != null) {
                                            requestChatAndReply(
                                                threadRootEventId,
                                                chatThread.addUserImage(image, imageType),
                                                timelineEvent.relatesTo is RelatesTo.Thread
                                            )
                                        }
                                    }
                                }
                            }
                        }

                    }
                }.onFailure {
                    log.error(it) { "Error on handling message" }
                    matrixClient.room.sendMessage(timelineEvent.roomId) {
                        text("Internal error")
                        reply(timelineEvent)
                    }
                }
            }

        }
    }


    matrixClient.startSync()

    Runtime.getRuntime().addShutdownHook(thread(false) {
        matrixClient.stop()
    })
}

fun main() = runBlocking {
    (LoggerFactory.getLogger("Exposed") as Logger).level = Level.INFO
    run()
}