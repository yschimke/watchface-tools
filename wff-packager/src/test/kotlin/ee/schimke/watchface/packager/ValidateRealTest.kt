package ee.schimke.watchface.packager

import ee.schimke.watchface.packager.ReportCheck.Companion.MEMORY_FOOTPRINT
import ee.schimke.watchface.packager.ReportCheck.Companion.PUSH_VALIDATOR
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_SCHEMA
import ee.schimke.watchface.packager.ReportCheck.Companion.WFF_XSD
import ee.schimke.watchface.wff.WffBundleLayout
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/** `validate` and `pack --report-out` with Google's real validators. Skipped without an SDK. */
class ValidateRealTest {
  @TempDir lateinit var tmp: File

  private val pushPackage = "com.example.market.watchfacepush.analog"

  private fun bundle(edit: (MutableMap<String, ByteArray>) -> Unit = {}): Map<String, ByteArray> =
    TestSupport.sampleBundle("SimpleAnalog", pushPackage).toMutableMap().also(edit)

  private fun writeBundle(files: Map<String, ByteArray>): File {
    val dir = File(tmp, "bundle-${files.hashCode()}")
    for ((path, bytes) in files) File(dir, path).apply { parentFile.mkdirs() }.writeBytes(bytes)
    return dir
  }

  private fun validate(files: Map<String, ByteArray>): Pair<Int, PackagerReport> {
    TestSupport.requireSdk()
    val dir = writeBundle(files)
    val out = File(tmp, "report-${files.hashCode()}.json")
    val code = runValidate(mapOf("bundle" to dir.path, "report-out" to out.path), File(tmp, "keys"))
    assertTrue(out.isFile, "report written")
    // Same packager run, as a typed report, so the assertions don't need a JSON parser.
    val report =
      WatchFacePackager(TestSupport.sdk, Validators.google())
        .validate(
          files,
          PackagerOptions(
            PackageTarget.WatchFacePush("com.example.market", "analog"),
            1,
            SigningConfig.GeneratedDebugKey(File(tmp, "keys")),
          ),
        )
    assertEquals(report.toJson() + "\n", out.readText())
    return code to report
  }

  @Test
  fun aKnownGoodBundlePassesEveryCheckAndIssuesAToken() {
    val (code, report) = validate(bundle())
    assertEquals(0, code, report.toJson())
    assertTrue(report.passed, report.toJson())
    assertTrue(report.checks.all { it.status == CheckStatus.PASSED }, report.toJson())
    val memory = assertNotNull(report.check(MEMORY_FOOTPRINT)!!.memory)
    assertTrue(memory.totalBytes > 0 && memory.activeLimitBytes == 100L * 1024 * 1024, "$memory")
    val push = report.check(PUSH_VALIDATOR)!!
    assertEquals(true, push.tokenIssued)
    assertTrue(push.passedChecks!!.isNotEmpty())
  }

  @Test
  fun aBadXsdAttributeIsReportedWithLineAndColumn() {
    val (code, report) =
      validate(
        bundle {
          it[WffBundleLayout.WATCH_FACE_PATH] =
            it
              .getValue(WffBundleLayout.WATCH_FACE_PATH)
              .decodeToString()
              .replaceFirst("<Scene ", "<Scene bogusAttribute=\"1\" ")
              .encodeToByteArray()
        },
      )
    assertEquals(1, code)
    assertFalse(report.passed)
    // wff-core only warns about an unknown attribute; Google's XSD is the authority.
    val schema = report.check(WFF_SCHEMA)!!
    assertEquals(CheckStatus.PASSED, schema.status, "${schema.findings}")
    assertTrue(
      schema.findings.any { it.path == "/WatchFace/Scene/@bogusAttribute" },
      "${schema.findings}",
    )
    val xsd = report.check(WFF_XSD)!!
    assertEquals(CheckStatus.FAILED, xsd.status)
    val finding = xsd.findings.first { "bogusAttribute" in it.message }
    assertTrue(finding.message.startsWith("cvc-"), finding.message)
    assertNotNull(finding.line)
    assertNotNull(finding.column)
    // Every check ran: the memory evaluator and push validator still have verdicts.
    assertTrue(report.checks.none { it.status == CheckStatus.SKIPPED }, report.toJson())
  }

  /** What users hit with `case_steel`: the face names a drawable the bundle doesn't ship. */
  @Test
  fun aMissingAssetFailsTheMemoryCheckAndThePushValidatorStillRuns() {
    val (code, report) = validate(bundle { it.remove("res/drawable/hour.png") })
    assertEquals(1, code)
    assertFalse(report.passed)
    val memory = report.check(MEMORY_FOOTPRINT)!!
    assertEquals(CheckStatus.FAILED, memory.status)
    assertTrue(
      memory.findings.any { "Asset hour was not found" in it.message },
      "${memory.findings}",
    )
    val push = report.check(PUSH_VALIDATOR)!!
    assertTrue(push.status != CheckStatus.SKIPPED, report.toJson())
    assertTrue(push.passedChecks != null, report.toJson())
  }

  @Test
  fun packWritesTheReportOnSuccessAndFailure() {
    TestSupport.requireSdk()
    val keys = File(tmp, "keys")
    fun pack(files: Map<String, ByteArray>, name: String): Pair<Int, String> {
      val report = File(tmp, "$name.json")
      val flags =
        mapOf(
          "bundle" to writeBundle(files).path,
          "out" to File(tmp, "$name.apk").path,
          "push-marketplace" to "com.example.market",
          "keystore" to debugKeystore(keys).path,
          "keystore-pass" to "android",
          "report-out" to report.path,
        )
      return runPack(flags) to report.readText()
    }
    val (ok, okJson) = pack(bundle(), "ok")
    assertEquals(0, ok)
    assertTrue("\"passed\":true" in okJson && "\"tokenIssued\":true" in okJson, okJson)
    val (bad, badJson) = pack(bundle { it.remove("res/drawable/hour.png") }, "bad")
    assertEquals(1, bad)
    assertTrue("\"passed\":false" in badJson && "Asset hour was not found" in badJson, badJson)
  }

  /** The generated debug key, as a plain keystore, so `pack` doesn't touch `~/.config`. */
  private fun debugKeystore(dir: File): File {
    WatchFacePackager(TestSupport.sdk, TestSupport.fakeValidators())
      .pack(bundle(), TestSupport.pushOptions(keyDir = dir, face = "analog"))
    return File(dir, "debug.p12")
  }
}
