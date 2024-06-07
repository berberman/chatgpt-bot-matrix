package icu.torus.chatgpt.m

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.model.ModelId
import com.aallam.openai.client.OpenAI
import com.aallam.openai.client.ProxyConfig
import io.github.irgaly.kottage.*
import io.github.irgaly.kottage.platform.KottageContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.engine.*
import io.ktor.http.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import net.folivo.trixnity.api.client.defaultTrixnityHttpClientFactory
import net.folivo.trixnity.client.*
import net.folivo.trixnity.client.media.okio.OkioMediaStore
import net.folivo.trixnity.client.room.message.react
import net.folivo.trixnity.client.room.message.reply
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.client.room.message.thread
import net.folivo.trixnity.client.store.eventId
import net.folivo.trixnity.client.store.relatesTo
import net.folivo.trixnity.client.store.repository.exposed.createExposedRepositoriesModule
import net.folivo.trixnity.client.store.roomId
import net.folivo.trixnity.client.store.sender
import net.folivo.trixnity.clientserverapi.client.SyncState
import net.folivo.trixnity.clientserverapi.model.authentication.IdentifierType
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId
import net.folivo.trixnity.core.model.events.m.RelatesTo
import net.folivo.trixnity.core.model.events.m.room.RoomMessageEventContent
import okio.Path.Companion.toPath
import org.jetbrains.exposed.sql.Database
import kotlin.concurrent.thread
import kotlin.time.Duration.Companion.seconds

private val log = KotlinLogging.logger {}
private val commandRegex = "!chat( [o4])?( \\S.*)".toRegex()

@Serializable
data class SavedThread(val modelId: String, val messages: List<ChatMessage>) {
    fun addCompletion(completion: ChatCompletion) = copy(messages = messages + completion.first())
    fun addUserTextMessage(message: String) = copy(messages = messages + chatMessage {
        role = Role.User
        content { text(message) }
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
    val client = MatrixClient.fromStore(
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

    launch {
        client.syncState.first { it == SyncState.RUNNING }
        client.room.getTimelineEventsFromNowOn(decryptionTimeout = 10.seconds).collect { timelineEvent ->
            if (timelineEvent.sender == client.userId)
                return@collect
            launch per@{
                val content = timelineEvent.content?.getOrNull()
                suspend fun requestAndReply(threadRootEventId: EventId, savedThreadToRequest: SavedThread) {
                    log.debug { "Requesting $savedThreadToRequest" }
                    val completion = openai.chatCompletion(savedThreadToRequest.toRequest())
                    threads.putSavedThread(
                        timelineEvent.roomId,
                        threadRootEventId,
                        savedThreadToRequest.addCompletion(completion)
                    )
                    client.room.sendMessage(timelineEvent.roomId) {
                        completion.first().content.let {
                            if (it == null)
                                text("Empty response")
                            else
                                text("[${savedThreadToRequest.modelId}] $it")
                            thread(timelineEvent)
                        }
                    }
                }
                if (content is RoomMessageEventContent.TextBased.Text) {
                    val body = content.body

                    // Ignore messages started with //
                    if (body.startsWith("//"))
                        return@per
                    val isReplyingThread = timelineEvent.relatesTo is RelatesTo.Thread

                    val matchResult = commandRegex.matchEntire(body)
                    if (matchResult != null && !isReplyingThread) {
                        // This message is calling a command
                        client.withSetTyping(timelineEvent.roomId) {
                            val model = matchResult.groupValues[1].trimStart()
                            val text = matchResult.groupValues[2].trimStart()
                            val parsedModel = when (model) {
                                "o" -> "gpt-4o"
                                "4" -> "gpt-4-turbo"
                                "" -> "gpt-3.5-turbo"
                                else -> null
                            }
                            if (parsedModel == null)
                                client.room.sendMessage(timelineEvent.roomId) {
                                    text("Invalid model: $model")
                                    reply(timelineEvent)
                                }
                            else {
                                val savedThread = SavedThread(
                                    parsedModel, listOf(
                                        chatMessage {
                                            role = Role.System
                                            content { text("You are a helpful assistant.") }
                                        },
                                        chatMessage {
                                            role = Role.User
                                            content { text(text) }
                                        }
                                    )
                                )
                                requestAndReply(timelineEvent.eventId, savedThread)
                            }
                        }
                    } else if (body.startsWith("!chat") && !isReplyingThread) {
                        client.room.sendMessage(timelineEvent.roomId) {
                            text(
                                """
                                Usage: open a thread with the following commands and reply in thread for further chatting.
                                Messages started with `//` will be ignored.
                                * !chat <text> - use gpt-3.5-turbo
                                * !chat o <text> - use gpt-4o
                                * !chat 4 <text> - use gpt-4-turbo
                            """.trimIndent()
                            )
                        }
                    } else {
                        // This message is replying to a thread
                        val relatesTo = timelineEvent.relatesTo
                        if (relatesTo is RelatesTo.Thread) {
                            val threadRootEventId = relatesTo.eventId
                            val savedThread = threads.getSavedThread(timelineEvent.roomId, threadRootEventId)
                                ?.addUserTextMessage(body)
                            if (savedThread != null) {
                                client.withSetTyping(timelineEvent.roomId) {
                                    requestAndReply(threadRootEventId, savedThread)
                                }
                            }
                        }
                    }
                    when {
                        body.lowercase().startsWith("ping") -> {
                            client.room.sendMessage(timelineEvent.roomId) {
                                react(timelineEvent, "🏓")
                            }
                            client.room.sendMessage(timelineEvent.roomId) {
                                text("pong")
                                reply(timelineEvent)
                            }
                        }
                    }
                }
            }
        }
    }


    client.startSync()

    Runtime.getRuntime().addShutdownHook(thread(false) {
        client.stop()
    })
}

fun main() = runBlocking {
    run()
}