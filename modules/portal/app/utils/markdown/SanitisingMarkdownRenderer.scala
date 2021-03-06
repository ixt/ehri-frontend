package utils.markdown

import javax.inject.Inject

import org.jsoup.Jsoup
import org.jsoup.safety.Whitelist
import views.MarkdownRenderer


case class SanitisingMarkdownRenderer @Inject() (rawMarkdownRenderer: RawMarkdownRenderer) extends MarkdownRenderer {

  private val whiteListStandard: Whitelist = Whitelist.basic()
    .addAttributes("a", "target", "_blank")
    .addAttributes("a", "class", "external")
    .addAttributes("a", "rel", "nofollow")

  private val whiteListStrict: Whitelist = Whitelist.simpleText()
    .addTags("p", "a")
    .addAttributes("a", "target", "_blank")
    .addAttributes("a", "class", "external")
    .addAttributes("a", "rel", "nofollow")

  private def render(markdown: String): String = rawMarkdownRenderer.render(markdown)

  override def renderMarkdown(markdown: String): String =
    Jsoup.clean(render(markdown), whiteListStandard)

  override def renderUntrustedMarkdown(markdown: String): String =
    Jsoup.clean(render(markdown), whiteListStrict)

  override def renderTrustedMarkdown(markdown: String): String =
    render(markdown)
}