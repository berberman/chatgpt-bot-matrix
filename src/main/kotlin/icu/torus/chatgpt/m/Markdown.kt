package icu.torus.chatgpt.m

import org.intellij.markdown.flavours.commonmark.CommonMarkFlavourDescriptor
import org.intellij.markdown.html.HtmlGenerator
import org.intellij.markdown.parser.MarkdownParser

object Markdown {
    private val flavor = CommonMarkFlavourDescriptor()
    private val parser = MarkdownParser(flavor)
    fun markdownToHtml(src: String) = runCatching {
        val parsedTree = parser.buildMarkdownTreeFromString(src)
        val html = HtmlGenerator(src, parsedTree, flavor)
        html.generateHtml()
    }
}