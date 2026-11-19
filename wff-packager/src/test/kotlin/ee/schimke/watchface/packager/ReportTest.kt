package ee.schimke.watchface.packager

import ee.schimke.watchface.packager.ReportCheck.Companion.MEMORY_FOOTPRINT
import ee.schimke.watchface.packager.ReportCheck.Companion.PUSH_VALIDATOR
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_SCHEMA
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_XSD
import ee.schimke.watchface.packager.TestSupport.pushOptions
import ee.schimke.watchface.wff.WffBundleLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** The `--report-out` / `validate` report, with validator doubles (aapt2 still runs). */
class ReportTest {
  @TempDir lateinit var tmp: File

  private val ids = listOf(WFF_SCHEMA, WFF_XSD, MEMORY_FOOTPRINT, PUSH_VALIDATOR)

  private fun PackagerReport.status(id: String) = check(id)!!.status

  private fun sideload() =
    PackagerOptions(PackageTarget.Sideload(), 1, SigningConfig.GeneratedDebugKey(tmp))

  /** wff-core rejects the colour (an error), so `pack` stops before building the APK. */
  private fun wffCoreInvalid(): Map<String, ByteArray> =
    TestSupport.sampleBundle("SimpleAnalog").edit {
      it.replaceFirst("backgroundColor=\"#000000\"", "backgroundColor=\"nope\"")
    }

  private fun Map<String, ByteArray>.edit(f: (String) -> String): Map<String, ByteArray> =
    toMutableMap().also {
      it[WffBundleLayout.WATCH_FACE_PATH] =
        f(getValue(WffBundleLayout.WATCH_FACE_PATH).decodeToString()).encodeToByteArray()
    }

  @Test
  fun aPassingPushBuildReportsEveryCheckInOrderWithoutTheToken() {
    val sdk = TestSupport.requireSdk()
    val outcome =
      WatchFacePackager(sdk, TestSupport.fakeValidators("SECRET-TOKEN"))
        .packWithReport(TestSupport.sampleBundle("SimpleAnalog"), pushOptions(keyDir = tmp))
    val packaged = assertNotNull(outcome.packaged)
    assertEquals("SECRET-TOKEN", packaged.validationToken)
    val report = outcome.report
    assertEquals(ids, report.checks.map { it.id })
    assertTrue(report.passed)
    assertTrue(report.checks.all { it.status == CheckStatus.PASSED }, "${report.checks}")
    assertEquals(packaged.wffVersion.number, report.wffVersion)
    assertNotNull(report.minimumWffVersion)
    assertEquals(
      ReportMemory(
        1,
        2,
        3,
        MemoryFootprintResult.DEFAULT_ACTIVE_LIMIT_BYTES,
        MemoryFootprintResult.DEFAULT_AMBIENT_LIMIT_BYTES,
      ),
      report.check(MEMORY_FOOTPRINT)!!.memory,
    )
    assertEquals(true, report.check(PUSH_VALIDATOR)!!.tokenIssued)

    val json = report.toJson()
    assertFalse("SECRET-TOKEN" in json, json)
    assertTrue(json.startsWith("{\"schemaVersion\":1,\"mode\":\"full\",\"wffVersion\":"), json)
    assertTrue("\"passed\":true" in json && "\"tokenIssued\":true" in json, json)
    assertTrue("\"activeLimitBytes\":104857600" in json, json)
  }

  @Test
  fun packStopsAtTheFirstFailureAndSaysSoInTheReport() {
    val sdk = TestSupport.requireSdk()
    val outcome =
      WatchFacePackager(sdk, TestSupport.fakeValidators())
        .packWithReport(wffCoreInvalid(), pushOptions(keyDir = tmp))
    assertNull(outcome.packaged)
    val report = outcome.report
    assertFalse(report.passed)
    val schema = report.check(WFF_SCHEMA)!!
    assertEquals(CheckStatus.FAILED, schema.status)
    val finding = schema.findings.first { it.severity == FindingSeverity.ERROR }
    assertEquals("/WatchFace/Scene/@backgroundColor", finding.path)
    assertTrue("nope" in finding.message, finding.message)
    for (id in listOf(WFF_XSD, MEMORY_FOOTPRINT, PUSH_VALIDATOR)) {
      val c = report.check(id)!!
      assertEquals(CheckStatus.SKIPPED, c.status, id)
      assertEquals(1, c.findings.size, id)
    }
    // pack itself still throws the same diagnostics.
    val e =
      assertFailsWith<PackagingException> {
        WatchFacePackager(sdk, TestSupport.fakeValidators())
          .pack(wffCoreInvalid(), pushOptions(keyDir = tmp))
      }
    assertTrue("error[wff]: /WatchFace/Scene/@backgroundColor:" in e.message!!, e.message)
  }

  @Test
  fun validateKeepsGoingPastAWffCoreFailure() {
    val sdk = TestSupport.requireSdk()
    val report =
      WatchFacePackager(sdk, TestSupport.fakeValidators())
        .validate(wffCoreInvalid(), pushOptions(keyDir = tmp))
    assertFalse(report.passed)
    assertEquals(CheckStatus.FAILED, report.status(WFF_SCHEMA))
    assertEquals(CheckStatus.PASSED, report.status(WFF_XSD))
    assertEquals(CheckStatus.PASSED, report.status(MEMORY_FOOTPRINT))
    assertEquals(CheckStatus.PASSED, report.status(PUSH_VALIDATOR))
  }

  @Test
  fun validateRunsThePushValidatorWhenTheMemoryEvaluatorThrows() {
    val sdk = TestSupport.requireSdk()
    val fake = TestSupport.fakeValidators()
    val validators =
      Validators(
        push = fake.push,
        memory = { _, _ -> throw PackagingException("memory-footprint", "Asset x was not found") },
        format = fake.format,
      )
    val bundle = TestSupport.sampleBundle("SimpleAnalog")
    val report = WatchFacePackager(sdk, validators).validate(bundle, pushOptions(keyDir = tmp))
    val memory = report.check(MEMORY_FOOTPRINT)!!
    assertEquals(CheckStatus.FAILED, memory.status)
    assertEquals("Asset x was not found", memory.findings.single().message)
    assertNull(memory.memory)
    assertEquals(CheckStatus.PASSED, report.status(PUSH_VALIDATOR))
    assertFalse(report.passed)

    // pack stops there: the push validator is skipped.
    val packed =
      WatchFacePackager(sdk, validators).packWithReport(bundle, pushOptions(keyDir = tmp))
    assertEquals(CheckStatus.SKIPPED, packed.report.status(PUSH_VALIDATOR))
    assertEquals(CheckStatus.FAILED, packed.report.status(MEMORY_FOOTPRINT))
  }

  @Test
  fun pushValidatorFailuresAreFindingsAndNoTokenIsIssued() {
    val sdk = TestSupport.requireSdk()
    val validators =
      TestSupport.fakeValidators(failures = listOf(error("push-validator", "[cat] check: bad")))
    val report =
      WatchFacePackager(sdk, validators)
        .validate(TestSupport.sampleBundle("SimpleAnalog"), pushOptions(keyDir = tmp))
    val push = report.check(PUSH_VALIDATOR)!!
    assertEquals(CheckStatus.FAILED, push.status)
    assertEquals("[cat] check: bad", push.findings.single().message)
    assertEquals(false, push.tokenIssued)
    assertFalse(report.passed)
  }

  @Test
  fun aSideloadReportSkipsThePushValidatorAndStillPasses() {
    val sdk = TestSupport.requireSdk()
    val report =
      WatchFacePackager(sdk, TestSupport.fakeValidators())
        .validate(TestSupport.sampleBundle("SimpleAnalog"), sideload())
    val push = report.check(PUSH_VALIDATOR)!!
    assertEquals(CheckStatus.SKIPPED, push.status)
    assertEquals(FindingSeverity.WARNING, push.findings.single().severity)
    assertNull(push.tokenIssued)
    assertTrue(report.passed, report.toJson())
  }

  @Test
  fun aBuildFailureSkipsTheApkChecksWithTheReasonButStillRunsTheXsd() {
    val sdk = TestSupport.requireSdk()
    val bundle =
      TestSupport.sampleBundle("SimpleAnalog").toMutableMap().also {
        it["assets/extra.bin"] = byteArrayOf(1)
      }
    val report =
      WatchFacePackager(sdk, TestSupport.fakeValidators())
        .validate(bundle, pushOptions(keyDir = tmp))
    assertFalse(report.passed)
    assertEquals(CheckStatus.PASSED, report.status(WFF_SCHEMA))
    assertEquals(CheckStatus.PASSED, report.status(WFF_XSD))
    for (id in listOf(MEMORY_FOOTPRINT, PUSH_VALIDATOR)) {
      val c = report.check(id)!!
      assertEquals(CheckStatus.SKIPPED, c.status, id)
      val why = c.findings.single()
      assertEquals(FindingSeverity.ERROR, why.severity)
      assertTrue(
        "could not be built" in why.message && "assets/extra.bin" in why.message,
        why.message,
      )
    }
  }

  @Test
  fun xsdMessagesAreSplitIntoLineAndColumn() {
    assertEquals(
      ReportFinding(FindingSeverity.ERROR, "cvc-complex-type.3.2.2: nope", line = 12, column = 4),
      xsdFinding("[Line 12:Column 4]: cvc-complex-type.3.2.2: nope"),
    )
    assertEquals(ReportFinding(FindingSeverity.ERROR, "plain"), xsdFinding("plain"))
  }

  @Test
  fun jsonEscapesStringsAndOmitsAbsentFields() {
    val report =
      PackagerReport(
        null,
        null,
        listOf(
          ReportCheck(
            WFF_SCHEMA,
            "t",
            CheckStatus.FAILED,
            listOf(ReportFinding(FindingSeverity.ERROR, "a \"q\" \\ \n\u0001")),
          ),
        ),
      )
    assertEquals(
      "{\"schemaVersion\":1,\"mode\":\"full\",\"wffVersion\":null,\"minimumWffVersion\":null,\"passed\":false," +
        "\"checks\":[{\"id\":\"wff-schema\",\"title\":\"t\",\"status\":\"failed\",\"findings\":" +
        "[{\"severity\":\"error\",\"message\":\"a \\\"q\\\" \\\\ \\n\\u0001\"}]}]}",
      report.toJson(),
    )
  }

  @Test
  fun theMarketplaceIsInferredFromAPushPackage() {
    assertEquals(
      "com.example.market",
      inferredMarketplace(
        TestSupport.sampleBundle("SimpleAnalog", "com.example.market.watchfacepush.face"),
      ),
    )
    assertNull(inferredMarketplace(TestSupport.sampleBundle("SimpleAnalog")))
  }

  @Test
  fun everySamplePassesTheEstimate() {
    for (name in TestSupport.sampleNames) {
      val report = WffEstimate.report(TestSupport.sampleBundle(name))
      val memory = report.check(MEMORY_FOOTPRINT)!!
      // The fixtures stand in for drawn images only, so configuration icons may be missing.
      val missing = memory.contributors!!.filter { it.missing && it.activeBytes == 0L }
      assertTrue(
        memory.findings.none { it.severity == FindingSeverity.ERROR },
        "$name: ${memory.findings}",
      )
      assertTrue(missing.all { "icon" in it.resource }, "$name: $missing")
      assertEquals(CheckStatus.PASSED, memory.status, "$name: ${memory.findings}")
    }
  }
}
