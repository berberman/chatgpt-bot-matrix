package icu.torus.chatgpt.m

sealed interface Command {
    data class Model(
        val modelId: String, val prompt: String, val type: Type
    ) : Command {
        enum class Type {
            Chat, Image
        }
    }

    data object Pricing : Command
}