package ee.schimke.watchface.wff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WffValidatorTest {
  private fun parse(xml: String) = WffXmlParser.parse(xml)

  @Test
  fun minimalFacePassesAtV1() {
    val doc =
      parse(
        """<WatchFace width="450" height="450"><Scene><Group x="0" y="0" width="1" height="1"/></Scene></WatchFace>""",
      )
    assertEquals(emptyList(), WffValidator.check(doc, WffVersion.V1))
    assertEquals(WffVersion.V1, WffValidator.minimumVersion(doc))
  }

  @Test
  fun v2OnlyConstructFailsAtV1() {
    val doc =
      parse(
        """<WatchFace width="450" height="450">
             <UserConfigurations><Flavors defaultValue="a"><Flavor id="a"/></Flavors></UserConfigurations>
             <Scene/>
           </WatchFace>""",
      )
    val v1 = WffValidator.errors(doc, WffVersion.V1)
    assertEquals(
      listOf(
        "/WatchFace/UserConfigurations/Flavors",
        "/WatchFace/UserConfigurations/Flavors/Flavor",
      ),
      v1.filter { it.code == WffDiagnosticCode.ELEMENT_TOO_NEW }.map { it.path },
    )
    assertEquals(emptyList(), WffValidator.check(doc, WffVersion.V2))
    assertEquals(WffVersion.V2, WffValidator.minimumVersion(doc))
  }

  @Test
  fun newerAttributeIsAnErrorOnAnOlderTarget() {
    val doc = parse("""<WatchFace width="1" height="1" clipShape="CIRCLE"/>""")
    val d = WffValidator.check(doc, WffVersion.V1).single()
    assertEquals(WffDiagnosticCode.ATTRIBUTE_TOO_NEW, d.code)
    assertEquals("/WatchFace/@clipShape", d.path)
    assertEquals(emptyList(), WffValidator.check(doc, WffVersion.V2))
  }

  @Test
  fun missingRequiredAttributeAndPaths() {
    val doc =
      parse(
        """<WatchFace width="1" height="1"><Scene>
             <Group x="0" y="0" width="1" height="1"/>
             <Group x="0" y="0" width="1" height="1"><PartText x="0" y="0" width="1" height="1"><Variant mode="AMBIENT" target="alpha"/></PartText></Group>
           </Scene></WatchFace>""",
      )
    val d = WffValidator.check(doc, WffVersion.V4).single()
    assertEquals(WffDiagnosticCode.MISSING_REQUIRED_ATTRIBUTE, d.code)
    assertEquals("/WatchFace/Scene/Group[2]/PartText/Variant", d.path)
  }

  @Test
  fun disallowedChildIsErrorUnknownElementIsWarning() {
    val doc =
      parse(
        """<WatchFace width="1" height="1"><Scene><Stroke color="#fff" thickness="1"/><Mystery a="b"><Inner/></Mystery></Scene></WatchFace>""",
      )
    val byCode = WffValidator.check(doc, WffVersion.V4).associateBy { it.code }
    assertEquals(WffSeverity.ERROR, byCode.getValue(WffDiagnosticCode.DISALLOWED_CHILD).severity)
    assertEquals(
      "/WatchFace/Scene/Stroke",
      byCode.getValue(WffDiagnosticCode.DISALLOWED_CHILD).path,
    )
    assertEquals(WffSeverity.WARNING, byCode.getValue(WffDiagnosticCode.UNKNOWN_ELEMENT).severity)
    assertEquals(
      "/WatchFace/Scene/Mystery",
      byCode.getValue(WffDiagnosticCode.UNKNOWN_ELEMENT).path,
    )
    assertEquals(2, byCode.size)
    // passthrough: the unknown subtree survives a round trip
    assertTrue(WffXmlParser.parse(doc.toXml()).toXml().contains("<Mystery a=\"b\">"))
  }

  @Test
  fun wrongRootIsReported() {
    val d = WffValidator.check(parse("<Other/>"), WffVersion.V1).single()
    assertEquals(WffDiagnosticCode.UNEXPECTED_ROOT, d.code)
  }

  @Test
  fun schemaIsSelfConsistent() {
    for (spec in WffSchema.elements.values) {
      for (id in spec.childIds) {
        assertTrue(id in WffSchema.elements, "${spec.id} references unknown child $id")
      }
      val names = spec.childIds.map { WffSchema.elements.getValue(it).name }
      assertEquals(names.size, names.toSet().size, "${spec.id} has ambiguous child names $names")
    }
  }
}
