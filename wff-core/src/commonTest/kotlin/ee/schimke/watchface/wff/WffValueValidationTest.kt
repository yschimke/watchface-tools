package ee.schimke.watchface.wff

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the value, expression, data source and content-model checks, built from the
 * minimal faces the dogfood runs reduced their findings to.
 */
class WffValueValidationTest {
  private val SHAPE =
    """<Ellipse x="0" y="0" width="1" height="1"><Fill color="#ffffffff"/></Ellipse>"""

  private fun face(scene: String) =
    WffXmlParser.parse("""<WatchFace width="450" height="450"><Scene>$scene</Scene></WatchFace>""")

  private fun text(expression: String, template: String = "%s") =
    """<PartText x="0" y="0" width="450" height="50"><Text><Font family="SYNC_TO_DEVICE" size="30" color="#ffffffff"><Template>$template<Parameter expression="$expression"/></Template></Font></Text></PartText>"""

  private fun errors(doc: WffDocument, v: WffVersion = WffVersion.LATEST) =
    WffValidator.errors(doc, v)

  private fun only(doc: WffDocument, v: WffVersion = WffVersion.LATEST): WffDiagnostic =
    errors(doc, v).single()

  @Test
  fun badColourIsAnErrorWithItsPath() {
    // a/work: `<Fill color="not-a-colour"/>` packaged with exit 0.
    val doc =
      face(
        """<PartDraw x="0" y="0" width="10" height="10"><Ellipse x="0" y="0" width="10" height="10"><Fill color="not-a-colour"/></Ellipse></PartDraw>""",
      )
    val d = only(doc)
    assertEquals(WffDiagnosticCode.INVALID_VALUE, d.code)
    assertEquals("/WatchFace/Scene/PartDraw/Ellipse/Fill/@color", d.path)
    assertTrue("not-a-colour" in d.message, d.message)
  }

  @Test
  fun colourFormatsTheXsdAccepts() {
    for (ok in
      listOf("#ff000000", "#FF00aa", "[CONFIGURATION.themeColor.1]", "[COMPLICATION.TEXT]")) {
      val doc =
        face(
          """<PartDraw x="0" y="0" width="10" height="10"><Ellipse x="0" y="0" width="10" height="10"><Fill color="$ok"/></Ellipse></PartDraw>""",
        )
      assertEquals(emptyList(), WffValidator.check(doc, WffVersion.V4), ok)
    }
    for (bad in listOf("#fff", "#12345", "red", "ff000000", "#ggg000")) {
      val doc =
        face(
          """<PartDraw x="0" y="0" width="10" height="10"><Ellipse x="0" y="0" width="10" height="10"><Fill color="$bad"/></Ellipse></PartDraw>""",
        )
      assertEquals(WffDiagnosticCode.INVALID_VALUE, only(doc, WffVersion.V4).code, bad)
    }
  }

  @Test
  fun numbersAndBooleans() {
    val bad = face("""<PartDraw x="0" y="0" width="abc" height="10">$SHAPE</PartDraw>""")
    val d = only(bad)
    assertEquals(WffDiagnosticCode.INVALID_VALUE, d.code)
    assertEquals("/WatchFace/Scene/PartDraw/@width", d.path)

    val negative = face("""<PartDraw x="0" y="0" width="-5" height="10">$SHAPE</PartDraw>""")
    assertEquals("/WatchFace/Scene/PartDraw/@width", only(negative).path)

    val flag =
      face(
        """<PartDraw x="0" y="0" width="10" height="10"><Ellipse x="0" y="0" width="10" height="10"><Stroke color="#ffffffff" thickness="2" cap="BUTT"/></Ellipse></PartDraw>""",
      )
    assertEquals(emptyList(), WffValidator.check(flag, WffVersion.V1))
    val enumBad =
      face(
        """<PartDraw x="0" y="0" width="10" height="10"><Ellipse x="0" y="0" width="10" height="10"><Stroke color="#ffffffff" thickness="2" cap="POINTY"/></Ellipse></PartDraw>""",
      )
    assertEquals("/WatchFace/Scene/PartDraw/Ellipse/Stroke/@cap", only(enumBad).path)
  }

  private fun slot(id: String, types: String) =
    """<ComplicationSlot slotId="$id" displayName="a" x="0" y="0" width="100" height="100" supportedTypes="$types"><BoundingOval x="0" y="0" width="100" height="100"/><Complication type="SHORT_TEXT"><PartDraw x="0" y="0" width="10" height="10">$SHAPE</PartDraw></Complication></ComplicationSlot>"""

  @Test
  fun enumValueNewerThanTargetAndNotInTheEnum() {
    // a/work: supportedTypes="GOAL_PROGRESS WEIGHTED_ELEMENTS BOGUS" at v1.
    val doc = face(slot("0", "SHORT_TEXT GOAL_PROGRESS WEIGHTED_ELEMENTS"))
    val v1 = errors(doc, WffVersion.V1)
    assertTrue(v1.all { it.code == WffDiagnosticCode.VALUE_TOO_NEW }, v1.toString())
    assertEquals(
      listOf("/WatchFace/Scene/ComplicationSlot/@supportedTypes"),
      v1.map { it.path }.distinct(),
    )
    assertEquals(emptyList(), WffValidator.check(doc, WffVersion.V2))
    assertEquals(WffVersion.V2, WffValidator.minimumVersion(doc))

    val bogus = face(slot("0", "SHORT_TEXT BOGUS"))
    assertEquals(WffDiagnosticCode.INVALID_VALUE, only(bogus).code)
  }

  @Test
  fun duplicateSlotId() {
    val doc = face(slot("0", "SHORT_TEXT") + slot("0", "SHORT_TEXT") + slot("1", "SHORT_TEXT"))
    val d = only(doc)
    assertEquals(WffDiagnosticCode.DUPLICATE_ID, d.code)
    assertEquals("/WatchFace/Scene/ComplicationSlot[2]/@slotId", d.path)
  }

  @Test
  fun templateNeedsAParameter() {
    // c/work/repro/T1-template-without-parameter.xml
    val doc =
      face(
        """<PartText x="0" y="0" width="450" height="40"><Text><Font family="SYNC_TO_DEVICE" size="24" color="#FFFFFFFF"><Template><![CDATA[Hello]]></Template></Font></Text></PartText>""",
      )
    val d = only(doc)
    assertEquals(WffDiagnosticCode.MISSING_REQUIRED_CHILD, d.code)
    assertEquals("/WatchFace/Scene/PartText/Text/Font/Template", d.path)
    assertTrue("Parameter" in d.message, d.message)
  }

  @Test
  fun listOptionHoldsExactlyOneChild() {
    // b/work/repro/lo-draw-text.xml: a scene-level ListOption holding two children.
    val two =
      face(
        """<ListConfiguration id="w">
             <ListOption id="a">
               <PartDraw x="0" y="0" width="1" height="1">$SHAPE</PartDraw>
               <PartText x="0" y="0" width="1" height="1"><Text><Font family="SYNC_TO_DEVICE" size="3" color="#ffffffff"><Template>%s<Parameter expression="1"/></Template></Font></Text></PartText>
             </ListOption>
           </ListConfiguration>""",
      )
    val d = only(two)
    assertEquals(WffDiagnosticCode.TOO_MANY_CHILDREN, d.code)
    assertEquals("/WatchFace/Scene/ListConfiguration/ListOption", d.path)
    assertTrue("Group" in d.message, d.message)

    val one =
      face(
        """<ListConfiguration id="w"><ListOption id="a"><Group name="g" x="0" y="0" width="1" height="1"/></ListOption></ListConfiguration>""",
      )
    assertEquals(emptyList(), errors(one))

    val none = face("""<ListConfiguration id="w"><ListOption id="a"/></ListConfiguration>""")
    assertEquals(WffDiagnosticCode.MISSING_REQUIRED_CHILD, only(none).code)
  }

  @Test
  fun weatherSourceNeedsVersion2() {
    // c/work/repro/V1-weather-at-v1.xml
    val doc = face(text("[WEATHER.TEMPERATURE]"))
    val v1 = only(doc, WffVersion.V1)
    assertEquals(WffDiagnosticCode.DATA_SOURCE_TOO_NEW, v1.code)
    assertEquals("/WatchFace/Scene/PartText/Text/Font/Template/Parameter/@expression", v1.path)
    assertEquals(emptyList(), errors(doc, WffVersion.V2))
    assertEquals(WffVersion.V2, WffValidator.minimumVersion(doc))
    // indexed forms resolve through the {index} pattern
    assertEquals(
      WffVersion.V2,
      WffValidator.minimumVersion(face(text("[WEATHER.HOURS.3.TEMPERATURE]"))),
    )
  }

  @Test
  fun dataSourceAndFunctionVersions() {
    assertEquals(WffVersion.V1, WffValidator.minimumVersion(face(text("[HOUR_0_23] * 15"))))
    assertEquals(WffVersion.V2, WffValidator.minimumVersion(face(text("[SECOND_TENS_DIGIT]"))))
    assertEquals(WffVersion.V3, WffValidator.minimumVersion(face(text("[SECONDS_SINCE_EPOCH]"))))
    assertEquals(
      WffVersion.V4,
      WffValidator.minimumVersion(face(text("colorRgb(255, 0, 0)"))),
    )
    val d = only(face(text("colorRgb(255, 0, 0)")), WffVersion.V3)
    assertEquals(WffDiagnosticCode.FUNCTION_TOO_NEW, d.code)
  }

  @Test
  fun unknownSourceAndFunctionAreErrors() {
    // d/work/probes/repro-... `[NOT_A_SOURCE]`
    val d = only(face(text("[NOT_A_SOURCE]")))
    assertEquals(WffDiagnosticCode.UNKNOWN_DATA_SOURCE, d.code)
    assertTrue("NOT_A_SOURCE" in d.message, d.message)
    assertEquals(WffDiagnosticCode.UNKNOWN_FUNCTION, only(face(text("frobnicate(1)"))).code)
    // arity matters: clamp takes three arguments
    assertEquals(WffDiagnosticCode.UNKNOWN_FUNCTION, only(face(text("clamp(1, 2)"))).code)
  }

  @Test
  fun faceDefinedSourcesAreNotUnknown() {
    for (e in
      listOf("[CONFIGURATION.theme]", "[COMPLICATION.RANGED_VALUE_VALUE]", "[REFERENCE.r]")) {
      assertEquals(emptyList(), WffValidator.check(face(text(e)), WffVersion.V4), e)
    }
  }

  @Test
  fun garbageExpressionIsReportedWithItsNodePath() {
    // d/work/probes/v1-datasource.xml: `[NOT_A_REAL_SOURCE] + (1`
    val doc =
      face(
        text("[WEATHER.TEMPERATURE]", "%d") + text("[NOT_A_REAL_SOURCE] + (1", "%d"),
      )
    val d = errors(doc).single()
    assertEquals(WffDiagnosticCode.INVALID_EXPRESSION, d.code)
    assertEquals(
      "/WatchFace/Scene/PartText[2]/Text/Font/Template/Parameter/@expression",
      d.path,
    )
  }

  @Test
  fun expressionSyntax() {
    val valid =
      listOf(
        "[SECOND] * 6 + 180",
        "(1 + 2) * 3",
        "-[HOUR_0_23]",
        "[HOUR_0_23] > 12 ? 1 : 0",
        "clamp([BATTERY_PERCENT], 0, 100)",
        "numberFormat(\"00\", [MINUTE])",
        "\"--\"",
        "[A] == [B] && ![C]",
        "colorRgb(10, 20, 30)",
        "extractColorFromColors(#ff0000 #00ff00 #0000ff, 3, 1)",
      )
    for (e in valid) assertNull(WffExpressions.parse(e).syntaxError, e)
    val invalid =
      listOf(
        "",
        "--",
        "[NOT_A_REAL_SOURCE] + (1",
        "(1 + 2",
        "1 +",
        "* 3",
        "[SECOND",
        "SECOND]",
        "1 ? 2",
        "max(1,",
        "\"unterminated",
        "1 \$ 2",
      )
    for (e in invalid) assertTrue(
      WffExpressions.parse(e).syntaxError != null,
      "'$e' should not parse",
    )
    val a = WffExpressions.parse("clamp([BATTERY_PERCENT], 0, 100) + [WEATHER.HOURS.2.UV_INDEX]")
    assertEquals(listOf("BATTERY_PERCENT", "WEATHER.HOURS.2.UV_INDEX"), a.sources)
    assertEquals(listOf("clamp/3"), a.functions)
  }

  @Test
  fun expressionElementText() {
    val bad =
      WffXmlParser.parse(
        """<WatchFace width="1" height="1"><Scene><Condition><Expressions><Expression name="e"><![CDATA[[A + ]]></Expression></Expressions><Compare expression="e"><PartDraw x="0" y="0" width="1" height="1">$SHAPE</PartDraw></Compare></Condition></Scene></WatchFace>""",
      )
    val d = errors(bad).single { it.code == WffDiagnosticCode.INVALID_EXPRESSION }
    assertEquals("/WatchFace/Scene/Condition/Expressions/Expression", d.path)
  }

  @Test
  fun everyDiagnosticCarriesANodePath() {
    val doc =
      face(
        slot("0", "BOGUS") +
          slot("0", "SHORT_TEXT") +
          text("[NOPE] +") +
          """<PartDraw x="0" y="0" width="abc" height="10">$SHAPE</PartDraw>""",
      )
    val all = WffValidator.check(doc, WffVersion.V1)
    assertTrue(all.size >= 4, all.toString())
    assertTrue(all.all { it.path.startsWith("/WatchFace/") }, all.toString())
  }
}
