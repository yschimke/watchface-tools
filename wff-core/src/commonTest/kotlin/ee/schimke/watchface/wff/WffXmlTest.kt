package ee.schimke.watchface.wff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WffXmlTest {
  @Test
  fun preservesAttributeOrderAndWritesCanonicalForm() {
    val doc =
      WffXmlParser.parse("""<?xml version="1.0"?><A z="1"  b='2'><B/><C   x="&amp;&lt;"></C></A>""")
    assertEquals(listOf("z", "b"), doc.root.attributes.map { it.name })
    assertEquals(
      "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
        "<A z=\"1\" b=\"2\">\n  <B/>\n  <C x=\"&amp;&lt;\"/>\n</A>\n",
      doc.toXml(),
    )
  }

  @Test
  fun mixedContentIsKeptVerbatim() {
    val src = "<T>♡ %s<P e=\"[X]\"/>\n   </T>"
    val doc = WffXmlParser.parse(src)
    assertEquals(3, doc.root.children.size)
    assertEquals("♡ %s", (doc.root.children[0] as WffText).text)
    assertEquals("\n   ", (doc.root.children[2] as WffText).text)
    val once = doc.toXml()
    assertTrue(once.contains("<T>♡ %s<P e=\"[X]\"/>\n   </T>"))
    assertEquals(once, WffXmlParser.parse(once).toXml())
  }

  @Test
  fun cdataCommentsAndProlog() {
    val src =
      "<!-- licence -->\n<R><!--c--><E name=\"n\"><![CDATA[[A] != null && [B] < 2]]></E></R>\n<!--tail-->"
    val doc = WffXmlParser.parse(src)
    assertEquals(listOf(WffComment(" licence ")), doc.prolog)
    assertEquals(listOf(WffComment("tail")), doc.epilog)
    val e = doc.root.firstChild("E")!!
    assertEquals(listOf<WffNode>(WffCData("[A] != null && [B] < 2")), e.children)
    val once = doc.toXml()
    assertEquals(doc, WffXmlParser.parse(once))
    assertEquals(once, WffXmlParser.parse(once).toXml())
  }

  @Test
  fun cdataTerminatorIsSplitAndStable() {
    val doc = WffDocument(WffElement("E", children = listOf(WffCData("a]]>b"))))
    val once = doc.toXml()
    assertEquals("a]]>b", WffXmlParser.parse(once).root.textContent())
    assertEquals(once, WffXmlParser.parse(once).toXml())
  }

  @Test
  fun attributeWhitespaceAndEscapesRoundTrip() {
    val doc = WffDocument(WffElement("E", listOf(WffAttribute("v", "a\nb\t\"c\" & <d>\r"))))
    val back = WffXmlParser.parse(doc.toXml())
    assertEquals(doc, back)
  }

  @Test
  fun malformedInputFailsWithPosition() {
    val ex = assertFailsWith<WffParseException> { WffXmlParser.parse("<A>\n  <B></A>") }
    assertEquals(2, ex.line)
    assertFailsWith<WffParseException> { WffXmlParser.parse("<A a=1/>") }
    assertFailsWith<WffParseException> { WffXmlParser.parse("<A a=\"1\" a=\"2\"/>") }
    assertFailsWith<WffParseException> { WffXmlParser.parse("") }
    assertFailsWith<WffParseException> { WffXmlParser.parse("<A>&bogus;</A>") }
  }

  @Test
  fun bomAndCrlfAreNormalised() {
    val doc = WffXmlParser.parse("﻿<A>\r\n<B/>\r\n</A>")
    assertEquals(listOf("B"), doc.root.childElements.map { it.name })
  }

  @Test
  fun nonBreakingSpaceIsContentNotFormatting() {
    val doc = WffXmlParser.parse("<A><Template>&#160;</Template></A>")
    val template = doc.root.childElements.single()
    assertEquals("\u00A0", template.textContent())
    assertEquals(doc, WffXmlParser.parse(doc.toXml()))
  }

  @Test
  fun literalLessThanInAttributeIsRejected() {
    assertFailsWith<WffParseException> { WffXmlParser.parse("<Compare expression=\"[X] < 2\"/>") }
    val ok = WffXmlParser.parse("<Compare expression=\"[X] &lt; 2\"/>")
    assertEquals("[X] < 2", ok.root.attributes.single().value)
  }

  @Test
  fun carriageReturnInCDataSurvives() {
    val doc = WffDocument(WffElement("E", children = listOf(WffCData("a\rb"))))
    val once = doc.toXml()
    assertEquals("a\rb", WffXmlParser.parse(once).root.textContent())
    assertEquals(once, WffXmlParser.parse(once).toXml())
  }

  @Test
  fun forbiddenCharacterReferencesFail() {
    assertFailsWith<WffParseException> { WffXmlParser.parse("<T>&#0;</T>") }
    assertFailsWith<WffParseException> { WffXmlParser.parse("<T>&#xD800;</T>") }
    assertEquals("\uFFFD", WffXmlParser.parse("<T>&#xFFFD;</T>").root.textContent())
  }
}
