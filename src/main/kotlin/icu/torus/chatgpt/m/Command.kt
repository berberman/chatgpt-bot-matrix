package icu.torus.chatgpt.m

import com.aallam.openai.api.image.ImageSize

sealed interface Command {

    data class ChatCommand(val modelId: String, val prompt: String) : Command
    data class ImageCommand(val modelId: String, val prompt: String, val size: ImageSize) : Command

    data object Pricing : Command
}