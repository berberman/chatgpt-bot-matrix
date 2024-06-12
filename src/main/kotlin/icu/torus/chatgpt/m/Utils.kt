package icu.torus.chatgpt.m

import com.aallam.openai.api.chat.ChatCompletion
import io.github.irgaly.kottage.KottageStorage
import io.github.irgaly.kottage.getOrNull
import io.github.irgaly.kottage.put
import io.github.oshai.kotlinlogging.KotlinLogging
import net.folivo.trixnity.client.MatrixClient
import net.folivo.trixnity.client.room.message.MessageBuilder
import net.folivo.trixnity.client.room.message.text
import net.folivo.trixnity.core.model.EventId
import net.folivo.trixnity.core.model.RoomId

val log = KotlinLogging.logger("Bot")

fun ChatCompletion.first() = choices[0].message

suspend fun KottageStorage.getChatThread(roomId: RoomId, threadRootEventId: EventId) =
    getOrNull<ChatThread>(roomId.full + threadRootEventId.full)

suspend fun KottageStorage.putChatThread(roomId: RoomId, threadRootEventId: EventId, chatThread: ChatThread) {
    log.debug { "Saved ${roomId.full}${threadRootEventId.full}, $chatThread" }
    put(roomId.full + threadRootEventId.full, chatThread)
}

suspend fun MatrixClient.withSetTyping(roomId: RoomId, block: suspend () -> Unit) {
    api.room.setTyping(roomId, userId, true, timeout = 30000)
    block()
    api.room.setTyping(roomId, userId, false)
}

fun MessageBuilder.markdown(src: String) {
    text(
        src,
        formattedBody = Markdown
            .markdownToHtml(src)
            .onFailure {
                log.error(it) { "Failed to parse markdown $src" }
            }.getOrNull()
    )
}