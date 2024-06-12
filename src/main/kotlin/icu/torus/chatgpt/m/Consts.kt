package icu.torus.chatgpt.m

object Consts {
    val helpMessage =
        """
            Usage: open a thread with the following commands and reply in thread for further chatting (except !image).
            Messages started with `//` will be ignored.
            * `!chat <text>` - use gpt-3.5-turbo
            * `!chat o <text>` - use gpt-4o
            * `!chat 4 <text>` - use gpt-4-turbo
            * `!image <text>` - use dall-e-3 to generate an image
        """.trimIndent()


    val pricingMessage =
        """
            ```
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
            ```
    """.trimIndent()

}