package ee.schimke.watchface.apk

import ee.schimke.watchface.packager.PackageTarget
import ee.schimke.watchface.packager.PackagingException
import ee.schimke.watchface.wff.WffResourceFile
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Structure of what the builder writes, checked by readers that share no code with it. */
/**
 * The cases, as suspending bodies: JVM and browser tests run the same list (a common `@Test` cannot
 * return the browser's promise, so each platform has its own thin runner).
 */
object ApkStructureCases {
  private val options = WffApkOptions(TestBundles.pushTarget, versionCode = 7)

  private suspend fun build(
    bundle: Map<String, ByteArray> = TestBundles.bundle(),
    opts: WffApkOptions = options,
  ): WffApk = WffApkBuilder.build(bundle, opts, generateDebugApkSigner())

  suspend fun entriesAreTheAllowedSetAndArscIsStoredAndAligned() {
    val apk = build()
    val zip = ReadZip(apk.apk)
    assertEquals(
      listOf(
        "AndroidManifest.xml",
        "res/drawable/preview.png",
        "res/raw/watchface.xml",
        "res/xml/watch_face_info.xml",
        "resources.arsc",
      ),
      zip.entries.map { it.name },
    )
    val arsc = zip.entry("resources.arsc")
    assertEquals(0, arsc.method)
    assertEquals(0, arsc.dataOffset % 4)
    assertTrue(
      zip.entries.filter { it.method == 0 }.all { it.dataOffset % 4 == 0 },
      "stored entries 4-aligned",
    )
    assertEquals(0x0002, zip.stored("resources.arsc").u16At(0))
    // The face is deflated (text) and its CRC is of the original bytes.
    val face = zip.entry("res/raw/watchface.xml")
    assertEquals(8, face.method)
    assertEquals(
      Crc32.of(
        TestBundles.FACE_XML.let { _ -> TestBundles.bundle().getValue("res/raw/watchface.xml") },
      ),
      face.crc,
    )
  }

  suspend fun signingBlockSitsBeforeTheCentralDirectory() {
    val apk = build().apk
    val zip = ReadZip(apk)
    val cd = zip.centralDirectoryOffset
    assertEquals("APK Sig Block 42", apk.decodeToString(cd - 16, cd))
    val blockSize = apk.u32At(cd - 24) // low half of the trailing u64
    val start = cd - blockSize - 8
    assertEquals(blockSize, apk.u32At(start), "leading and trailing sizes agree")
    // Pair ids present: v2 and v3.
    val ids = mutableListOf<Int>()
    var p = start + 8
    while (p < cd - 24) {
      val len = apk.u32At(p)
      ids += apk.u32At(p + 8)
      p += 8 + len
    }
    assertEquals(listOf(0x7109871a, 0xf05368c0.toInt()), ids)
  }

  suspend fun v3CanBeLeftOut() {
    val apk = build(opts = WffApkOptions(TestBundles.pushTarget, 7, signV3 = false)).apk
    val cd = ReadZip(apk).centralDirectoryOffset
    val start = cd - apk.u32At(cd - 24) - 8
    assertEquals(0x7109871a, apk.u32At(start + 8 + 8))
    assertEquals(cd - 24, start + 16 + apk.u32At(start + 8), "only one pair")
  }

  suspend fun resultReportsPackageAndCertificate() {
    val signer = generateDebugApkSigner()
    val r = WffApkBuilder.build(TestBundles.bundle(), options, signer)
    assertEquals("com.example.market.watchfacepush.face", r.packageName)
    assertEquals(7, r.versionCode)
    assertEquals(signer.certificateSha256, r.signerCertificateSha256)
    assertEquals(64, r.signerCertificateSha256.length)
  }

  suspend fun aPinnedCertificateThatDoesNotMatchFailsBeforeSigning() {
    val e =
      assertFailsWith<PackagingException> {
        build(
          opts =
            WffApkOptions(TestBundles.pushTarget, 7, expectedCertificateSha256 = "00".repeat(32)),
        )
      }
    assertTrue("does not match the pinned" in e.message!!, e.message)
    val signer = generateDebugApkSigner()
    val pin = signer.certificateSha256.chunked(2).joinToString(":").uppercase()
    WffApkBuilder.build(
      TestBundles.bundle(),
      WffApkOptions(TestBundles.pushTarget, 7, expectedCertificateSha256 = pin),
      signer,
    )
  }

  suspend fun pushRulesRejectABadFaceNameAndAFaceWithoutItsManifestVersion() {
    val e =
      assertFailsWith<PackagingException> {
        build(
          opts = WffApkOptions(PackageTarget.WatchFacePush("com.example.market", "bad name"), 7),
        )
      }
    assertTrue("Face name 'bad name'" in e.message!!, e.message)
    val noVersion =
      TestBundles.bundle().toMutableMap().also {
        it["AndroidManifest.xml"] =
          it
            .getValue("AndroidManifest.xml")
            .decodeToString()
            .replace("com.google.wear.watchface.format.version", "x")
            .encodeToByteArray()
      }
    assertTrue(
      "Manifest needs <property" in
        assertFailsWith<PackagingException> { build(noVersion) }.message!!,
    )
  }

  suspend fun unsupportedResourcesAreNamedNotGuessed() {
    val e =
      assertFailsWith<PackagingException> {
        build(
          TestBundles.bundle(
            extraFiles = listOf(WffResourceFile("drawable-tlh", "x.png", byteArrayOf(1))),
          ),
        )
      }
    assertTrue("unsupported resource qualifier 'tlh'" in e.message!!, e.message)
    val xml =
      assertFailsWith<PackagingException> {
        build(
          TestBundles.bundle(
            extraFiles =
              listOf(WffResourceFile("drawable", "v.xml", "<vector/>".encodeToByteArray())),
          ),
        )
      }
    assertTrue("only res/xml and res/raw XML" in xml.message!!, xml.message)
  }

  suspend fun stringsAndQualifiedFilesLandInTheTable() {
    val bundle =
      TestBundles.bundle(
        extraFiles =
          listOf(
            WffResourceFile("drawable-xhdpi", "preview.png", TestBundles.png),
            WffResourceFile("drawable-night", "preview.png", TestBundles.png),
          ),
      )
    val zip = ReadZip(build(bundle).apk)
    val arsc = zip.stored("resources.arsc")
    // A table the platform can walk: every chunk's size lands exactly on the next chunk.
    var chunks = 0
    fun walk(from: Int, to: Int) {
      var p = from
      while (p < to) {
        val size = arsc.u32At(p + 4)
        assertTrue(size >= 8 && p + size <= to, "chunk at $p size $size")
        val type = arsc.u16At(p)
        if (type == 0x0200) walk(p + arsc.u16At(p + 2), p + size)
        chunks++
        p += size
      }
      assertEquals(to, p)
    }
    walk(arsc.u16At(2), arsc.size)
    assertTrue(chunks > 8)
    assertTrue(zip.entries.any { it.name == "res/drawable-xhdpi/preview.png" })
    assertTrue(zip.entries.any { it.name == "res/drawable-night/preview.png" })
  }

  suspend fun buildingTwiceWithTheSameSignerGivesTheSameEntries() {
    val signer = generateDebugApkSigner()
    val a = ReadZip(WffApkBuilder.build(TestBundles.bundle(), options, signer).apk)
    val b = ReadZip(WffApkBuilder.build(TestBundles.bundle(), options, signer).apk)
    for (e in a.entries) {
      assertContentEquals(
        a.bytes.copyOfRange(e.dataOffset, e.dataOffset + e.compressedSize),
        b.bytes.copyOfRange(
          b.entry(e.name).dataOffset,
          b.entry(e.name).dataOffset + e.compressedSize,
        ),
        e.name,
      )
    }
  }

  val cases: List<Pair<String, suspend () -> Unit>> =
    listOf(
      "entriesAreTheAllowedSetAndArscIsStoredAndAligned" to
        ::entriesAreTheAllowedSetAndArscIsStoredAndAligned,
      "signingBlockSitsBeforeTheCentralDirectory" to ::signingBlockSitsBeforeTheCentralDirectory,
      "v3CanBeLeftOut" to ::v3CanBeLeftOut,
      "resultReportsPackageAndCertificate" to ::resultReportsPackageAndCertificate,
      "aPinnedCertificateThatDoesNotMatchFailsBeforeSigning" to
        ::aPinnedCertificateThatDoesNotMatchFailsBeforeSigning,
      "pushRulesRejectABadFaceNameAndAFaceWithoutItsManifestVersion" to
        ::pushRulesRejectABadFaceNameAndAFaceWithoutItsManifestVersion,
      "unsupportedResourcesAreNamedNotGuessed" to ::unsupportedResourcesAreNamedNotGuessed,
      "stringsAndQualifiedFilesLandInTheTable" to ::stringsAndQualifiedFilesLandInTheTable,
      "buildingTwiceWithTheSameSignerGivesTheSameEntries" to
        ::buildingTwiceWithTheSameSignerGivesTheSameEntries,
    )
}
