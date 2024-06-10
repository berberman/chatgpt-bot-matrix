@file:OptIn(ExperimentalEncodingApi::class)

package icu.torus.chatgpt.m

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.image.ImageCreation
import com.aallam.openai.api.image.ImageSize
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.ProxyConfig
import io.github.irgaly.kottage.*
import io.github.irgaly.kottage.platform.KottageContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.*
import io.ktor.client.engine.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import net.folivo.trixnity.api.client.defaultTrixnityHttpClientFactory
import net.folivo.trixnity.client.*
import net.folivo.trixnity.client.media.okio.OkioMediaStore
import net.folivo.trixnity.client.room.message.*
import net.folivo.trixnity.client.store.*
import net.folivo.trixnity.client.store.repository.exposed.createExposedRepositoriesModule
import net.folivo.trixnity.clientserverapi.client.SyncState
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import net.folivo.trixnity.utils.toByteArray
import net.folivo.trixnity.utils.toByteArrayFlow
import okio.Path.Companion.toPath
import org.jetbrains.exposed.sql.Database
import org.slf4j.LoggerFactory
import java.io.File
import kotlin.concurrent.thread
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}

@Serializable
data class SavedThread(val modelId: String, val messages: List<ChatMessage>) {
    fun addCompletion(completion: ChatCompletion) = copy(messages = messages + completion.first())
    fun addUserTextMessage(message: String) = copy(messages = messages + chatMessage {
        role = Role.User
        content { text(message) }
    })

    fun addUserImage(image: ByteArray, imageType: String) = copy(messages = messages + chatMessage {
        role = Role.User
        content { image("data:image/$imageType;base64,${Base64.encode(image)}") }
    })

    fun toRequest() = ChatCompletionRequest(ModelId(modelId), messages)
}

fun ChatCompletion.first() = choices[0].message

suspend fun KottageStorage.getSavedThread(roomId: RoomId, threadRootEventId: EventId) =
    getOrNull<SavedThread>(roomId.full + threadRootEventId.full)

suspend fun KottageStorage.putSavedThread(roomId: RoomId, threadRootEventId: EventId, savedThread: SavedThread) {
    log.debug { "Saved ${roomId.full}${threadRootEventId.full}, $savedThread" }
    put(roomId.full + threadRootEventId.full, savedThread)
}

suspend fun MatrixClient.withSetTyping(roomId: RoomId, block: suspend () -> Unit) {
    api.room.setTyping(roomId, userId, true)
    block()
    api.room.setTyping(roomId, userId, false)
}

private val helpMessage =
    """
      Usage: open a thread with the following commands and reply in thread for further chatting (except !image).
      Messages started with `//` will be ignored.
      * !chat <text> - use gpt-3.5-turbo
      * !chat o <text> - use gpt-4o
      * !chat 4 <text> - use gpt-4-turbo
      * !image <text> - use dall-e-3 to generate an image
    """.trimIndent()

private const val internalErrorMessage = "Internal error"
private val pricingMessage = """
    Pricing per 1M tokens:
    Name          | Input | Output
    ------------------------------
    gpt-4o        | $5.00  | $15.00
    gpt-4-turbo   | $10.00 | $30.00
    gpt-3.5-turbo | $0.5   | $1.50
    ------------------------------
    Pricing per image:
    dall-e-3 $0.040
    dall-e-2 $0.020
""".trimIndent()

private suspend fun run() = coroutineScope {

    val openai = OpenAI(
        System.getenv("OPENAI_TOKEN"),
        proxy = System.getenv("OPENAI_HTTP_PROXY")?.let { ProxyConfig.Http(it) }
    )

    val kottage = Kottage("kottage", System.getenv("KOTTAGE_DIR"), KottageEnvironment(KottageContext()), this)
    val threads = kottage.storage("threads")

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
                        savedThreadToRequest: SavedThread,
                        inThread: Boolean
                    ) {
                        log.debug { "[$threadRootEventId]: Requesting $savedThreadToRequest" }
                        val completion = openai.chatCompletion(savedThreadToRequest.toRequest())
                        threads.putSavedThread(
                            timelineEvent.roomId,
                            threadRootEventId,
                            savedThreadToRequest.addCompletion(completion)
                        )
                        matrixClient.room.sendMessage(timelineEvent.roomId) {
                            completion.first().content.let {
                                if (it == null)
                                    text("Empty response")
                                else
                                    text("[${savedThreadToRequest.modelId}] $it")
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
                                            is Command.Model -> when (cmd.type) {
                                                Command.Model.Type.Chat -> {
                                                    val savedThread = SavedThread(
                                                        cmd.modelId, listOf(
                                                            chatMessage {
                                                                role = Role.System
                                                                content { text("You are a helpful assistant.") }
                                                            },
                                                            chatMessage {
                                                                role = Role.User
                                                                content { text(cmd.prompt) }
                                                            }
                                                        )
                                                    )
                                                    requestChatAndReply(timelineEvent.eventId, savedThread, true)
                                                }

                                                Command.Model.Type.Image -> {
                                                    val image = openai.imageURL(
                                                        ImageCreation(
                                                            prompt = cmd.prompt,
                                                            model = ModelId(cmd.modelId),
                                                            n = 1,
                                                            size = ImageSize.is1024x1024
                                                        )
                                                    ).first()
                                                    log.debug { image }
                                                    val bytes =
                                                        httpClient.get(image.url).readBytes()
                                                    val fileName = Url(image.url).pathSegments.last()
                                                    matrixClient.room.sendMessage(timelineEvent.roomId) {
                                                        image(
                                                            fileName,
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
                                                }
                                            }

                                            Command.Pricing -> {
                                                matrixClient.room.sendMessage(timelineEvent.roomId) {
                                                    text(pricingMessage)
                                                    reply(timelineEvent)
                                                }
                                            }
                                        }

                                    }
                                    .onFailure {
                                        matrixClient.room.sendMessage(timelineEvent.roomId) {
                                            log.error { "Failed to parse command $body: ${it.message}" }
                                            text(helpMessage)
                                        }
                                    }

                            }
                        } else if (isInThreadOrReplyingToMe()) {
                            // This message is a reply
                            val threadRootEventId = findRootEvent(timelineEvent)
                            val savedThread = threads.getSavedThread(timelineEvent.roomId, threadRootEventId)
                                ?.addUserTextMessage(body)
                            if (savedThread != null) {
                                matrixClient.withSetTyping(timelineEvent.roomId) {
                                    requestChatAndReply(
                                        threadRootEventId,
                                        savedThread,
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
                            val savedThread = threads.getSavedThread(timelineEvent.roomId, threadRootEventId)
                            if (savedThread != null) {
                                if (savedThread.modelId != "gpt-4o")
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
                                                savedThread.addUserImage(image, imageType),
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
                        text(internalErrorMessage)
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