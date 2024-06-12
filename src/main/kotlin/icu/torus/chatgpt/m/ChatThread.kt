package icu.torus.chatgpt.m

import com.aallam.openai.api.chat.ChatCompletion
import com.aallam.openai.api.chat.ChatCompletionRequest
import com.aallam.openai.api.chat.ChatMessage
import com.aallam.openai.api.chat.chatMessage
import com.aallam.openai.api.core.Role
import com.aallam.openai.api.model.ModelId
import kotlinx.serialization.Serializable
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Serializable
data class ChatThread(val modelId: String, val messages: List<ChatMessage>) {
    fun addCompletion(completion: ChatCompletion) = copy(messages = messages + completion.first())
    fun addUserTextMessage(message: String) = copy(messages = messages + chatMessage {
        role = Role.User
        content { text(message) }
    })

    @OptIn(ExperimentalEncodingApi::class)
    fun addUserImage(image: ByteArray, imageType: String) = copy(messages = messages + chatMessage {
        role = Role.User
        content { image("data:image/$imageType;base64,${Base64.encode(image)}") }
    })

    fun toRequest() = ChatCompletionRequest(ModelId(modelId), messages)

    companion object {
        fun new(modelId: String, prompt: String) = ChatThread(
            modelId, listOf(
                chatMessage {
                    role = Role.System
                    content { text("You are a helpful assistant.") }
                },
                chatMessage {
                    role = Role.User
                    content { text(prompt) }
                }
            ))
    }
}