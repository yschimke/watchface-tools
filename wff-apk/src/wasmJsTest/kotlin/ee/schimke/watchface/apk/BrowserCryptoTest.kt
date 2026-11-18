package ee.schimke.watchface.apk

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@JsFun(
  "(hex) => { const b = Uint8Array.from((hex.match(/../g) || []).map((x) => parseInt(x, 16)));" +
    " return crypto.subtle.digest('SHA-256', b).then((d) => Array.from(new Uint8Array(d))" +
    ".map((v) => v.toString(16).padStart(2, '0')).join('')); }",
)
private external fun jsSha256Hex(hex: String): Promise<JsString>

/** Inflates a raw DEFLATE stream with the browser's own DecompressionStream. */
@JsFun(
  "(hex) => { const b = Uint8Array.from((hex.match(/../g) || []).map((x) => parseInt(x, 16)));" +
    " const ds = new DecompressionStream('deflate-raw');" +
    " const w = ds.writable.getWriter(); w.write(b); w.close();" +
    " return new Response(ds.readable).arrayBuffer().then((d) => Array.from(new Uint8Array(d))" +
    ".map((v) => v.toString(16).padStart(2, '0')).join('')); }",
)
private external fun jsInflateHex(hex: String): Promise<JsString>

/** Verifies an ECDSA P-256 P1363 signature over data with an SPKI public key. */
@JsFun(
  "(spkiHex, dataHex, sigHex) => { const b = (h) => Uint8Array.from((h.match(/../g) || []).map((x) => parseInt(x, 16)));" +
    " return crypto.subtle.importKey('spki', b(spkiHex), {name: 'ECDSA', namedCurve: 'P-256'}, false, ['verify'])" +
    ".then((k) => crypto.subtle.verify({name: 'ECDSA', hash: 'SHA-256'}, k, b(sigHex), b(dataHex))); }",
)
private external fun jsVerifyEcdsa(spkiHex: String, dataHex: String, sigHex: String): Promise<JsAny>

/** Leaves the APK where a harness (or a person in the console) can fetch it: `__wffApkHex`. */
@JsFun("(hex) => { globalThis.__wffApkHex = hex; }") private external fun jsPublishApk(hex: String)

@JsFun("(v) => v === true") private external fun jsIsTrue(v: JsAny?): Boolean

private suspend fun <T : JsAny?> Promise<T>.await(): T = suspendCoroutine { c ->
  then(
    onFulfilled = {
      c.resume(it)
      null
    },
    onRejected = {
      c.resumeWithException(IllegalStateException("promise rejected: $it"))
      null
    },
  )
}

/** The browser is the only place the WebCrypto actuals and the browser's inflater can be tried. */
class BrowserCryptoTest {
  @Test
  fun sha256AgreesWithWebCrypto(): Promise<JsAny?> = runAsync {
    for (size in listOf(0, 1, 63, 64, 65, 1000, 70_000)) {
      val data = ByteArray(size) { (it * 7 + 3).toByte() }
      assertEquals(
        jsSha256Hex(data.toHex()).await().toString(),
        Sha256.digest(data).toHex(),
        "size $size",
      )
    }
  }

  @Test
  fun deflateInflatesInTheBrowser(): Promise<JsAny?> = runAsync {
    val text = buildString {
      repeat(3000) { append("<Part x=\"${it % 37}\"/>\n") }
    }
      .encodeToByteArray()
    val random = ByteArray(5000) { ((it * 2654435761L) ushr 13).toByte() }
    for (data in listOf(text, random, byteArrayOf(1), ByteArray(0))) {
      val inflated = hexToBytes(jsInflateHex(Deflate.compress(data).toHex()).await().toString())
      assertContentEquals(data, inflated, "size ${data.size}")
    }
  }

  @Test
  fun theApkSignatureVerifiesUnderWebCrypto(): Promise<JsAny?> = runAsync {
    val signer = generateDebugApkSigner()
    val apk =
      WffApkBuilder.build(TestBundles.bundle(), WffApkOptions(TestBundles.pushTarget, 3), signer)
        .apk
    val cd = ReadZip(apk).centralDirectoryOffset
    val blockStart = cd - apk.u32At(cd - 24) - 8
    // First pair is v2: u64 len, u32 id, then lp(signers) -> lp(signer) -> lp(signed data),
    // lp(sigs), lp(spki)
    var p = blockStart + 8 + 12
    p += 4 // signers sequence length
    p += 4 // signer length
    val signedLen = apk.u32At(p)
    val signedData = apk.copyOfRange(p + 4, p + 4 + signedLen)
    p += 4 + signedLen
    p += 4 // signatures sequence length
    p += 4 // first signature record length
    assertEquals(SignatureAlgorithm.ECDSA_SHA256.id, apk.u32At(p))
    val sigLen = apk.u32At(p + 4)
    val der = apk.copyOfRange(p + 8, p + 8 + sigLen)
    val spki = Der.subjectPublicKeyInfo(signer.certificates.first())
    // DER r,s -> P1363 for WebCrypto.
    val seq = Der.read(der, 0)
    val r = Der.read(der, seq.contentStart)
    val s = Der.read(der, r.end)
    fun fixed(e: Der.Element): ByteArray {
      val raw = der.copyOfRange(e.contentStart, e.end).dropWhile { it == 0.toByte() }.toByteArray()
      return ByteArray(32 - raw.size) + raw
    }
    val ok = jsVerifyEcdsa(spki.toHex(), signedData.toHex(), (fixed(r) + fixed(s)).toHex()).await()
    assertTrue(jsIsTrue(ok), "WebCrypto rejects the v2 signature")
  }

  /**
   * Builds the sample face in the browser and publishes it for external verification: the same
   * bytes can be fed to apksigner, aapt2 and Google's validators on the JVM.
   */
  @Test
  fun publishesTheBrowserBuiltApkForExternalChecks(): Promise<JsAny?> = runAsync {
    val apk =
      WffApkBuilder.build(
          TestBundles.bundle(),
          WffApkOptions(TestBundles.pushTarget, 5),
          generateDebugApkSigner(),
        )
        .apk
    jsPublishApk(apk.toHex())
    assertTrue(apk.size > 1000)
  }
}
