package icu.torus.chatgpt.m

object CommandParser {
    private val knownCommands = arrayOf("chat", "image", "pricing")
    fun isCommand(input: String): Boolean =
        input.trim().let { raw ->
            raw.isNotEmpty() && raw.startsWith('!') && raw.drop(1).let { x ->
                knownCommands.map { x.startsWith(it) }.any()
            }
        }

    fun parse(input: String): Result<Command> {
        var raw = input.trim()
        if (raw.isEmpty() || !raw.startsWith('!'))
            throw IllegalArgumentException("$input is not a command")
        // drop !
        raw = raw.drop(1)
        return runCatching {
            val c = raw.takeWhile { !it.isWhitespace() }
            raw = raw.drop(c.length)
            if (raw.startsWith(' '))
                raw = raw.drop(1)
            when (c) {
                "chat" -> {
                    if (raw.isNotEmpty()) {
                        val x = raw.first()
                        if ((x == 'o' || x == '4') && raw[1] == ' ') {
                            val model =
                                when (x) {
                                    'o' -> "gpt-4o"
                                    '4' -> "gpt-4-turbo"
                                    else -> error("Impossible")
                                }
                            // drop o/4 and ws
                            raw = raw.drop(2)
                            Command.Model(model, raw, Command.Model.Type.Chat)
                        } else
                            Command.Model("gpt-3.5-turbo", raw, Command.Model.Type.Chat)
                    } else
                        throw IllegalArgumentException("Prompt is empty")
                }

                "image" -> {
                    if (raw.isNotEmpty()) {
                        val x = raw.first()
                        if ((x == '3') && raw[1] == ' ') {
                            // drop 3 and ws
                            raw = raw.drop(2)
                            Command.Model("dall-e-3", raw, Command.Model.Type.Image)
                        } else
                            Command.Model("dall-e-2", raw, Command.Model.Type.Image)
                    } else
                        throw IllegalArgumentException("Prompt is empty")
                }

                "pricing" -> Command.Pricing

                else -> throw IllegalArgumentException("Unknown command $c")
            }
        }
    }

}