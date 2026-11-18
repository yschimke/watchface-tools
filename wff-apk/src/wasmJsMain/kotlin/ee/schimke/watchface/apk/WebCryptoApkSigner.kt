package ee.schimke.watchface.apk

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.JsAny
import kotlin.js.JsString
import kotlin.js.Promise

// WebCrypto speaks promises and byte arrays; this file is the whole of that seam. Bytes cross as
// hex strings, which costs nothing at the sizes signed here (a few hundred bytes of signed data)
// and keeps typed-array interop out of the module.

private const val HEX_TO_BYTES =
  "const b = (h) => Uint8Array.from((h.match(/../g) || []).map((x) => parseInt(x, 16)));"
private const val BYTES_TO_HEX =
  "const x = (buf) => Array.from(new Uint8Array(buf)).map((v) => v.toString(16).padStart(2, '0')).join('');"

@JsFun(
  "() => crypto.subtle.generateKey({name: 'ECDSA', namedCurve: 'P-256'}, true, ['sign', 'verify'])",
)
private external fun jsGenerateEcdsaKeyPair(): Promise<JsAny>

@JsFun(
  "(pair) => { $BYTES_TO_HEX return crypto.subtle.exportKey('spki', pair.publicKey).then(x); }",
)
private external fun jsExportSpkiHex(pair: JsAny): Promise<JsString>

@JsFun("(pair) => pair.privateKey") private external fun jsPrivateKeyOf(pair: JsAny): JsAny

@JsFun(
  "(key, hex) => { $HEX_TO_BYTES $BYTES_TO_HEX " +
    "return crypto.subtle.sign({name: 'ECDSA', hash: 'SHA-256'}, key, b(hex)).then(x); }",
)
private external fun jsSignEcdsaHex(key: JsAny, hex: String): Promise<JsString>

@JsFun(
  "(key, hex) => { $HEX_TO_BYTES $BYTES_TO_HEX " +
    "return crypto.subtle.sign({name: 'RSASSA-PKCS1-v1_5'}, key, b(hex)).then(x); }",
)
private external fun jsSignRsaHex(key: JsAny, hex: String): Promise<JsString>

@JsFun(
  "(hex) => { $HEX_TO_BYTES return crypto.subtle.importKey('pkcs8', b(hex), " +
    "{name: 'ECDSA', namedCurve: 'P-256'}, false, ['sign']); }",
)
private external fun jsImportEcdsaPkcs8(hex: String): Promise<JsAny>

@JsFun(
  "(hex) => { $HEX_TO_BYTES return crypto.subtle.importKey('pkcs8', b(hex), " +
    "{name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256'}, false, ['sign']); }",
)
private external fun jsImportRsaPkcs8(hex: String): Promise<JsAny>

private suspend fun <T : JsAny?> Promise<T>.await(): T = suspendCoroutine { continuation ->
  then(
    onFulfilled = { value ->
      continuation.resume(value)
      null
    },
    onRejected = { reason ->
      continuation.resumeWithException(IllegalStateException("WebCrypto failed: $reason"))
      null
    },
  )
}

internal actual suspend fun generateEcdsaP256(): GeneratedKey {
  val pair = jsGenerateEcdsaKeyPair().await()
  val spki = hexToBytes(jsExportSpkiHex(pair).await().toString())
  val privateKey = jsPrivateKeyOf(pair)
  return GeneratedKey(spki) { data ->
    Der.ecdsaP1363ToDer(hexToBytes(jsSignEcdsaHex(privateKey, data.toHex()).await().toString()))
  }
}

/**
 * An [ApkSigner] over a WebCrypto private [key] (a `CryptoKey` imported or generated for
 * `ECDSA`/P-256 or `RSASSA-PKCS1-v1_5`/SHA-256 with the `sign` usage; it can be non-extractable)
 * and the DER [certificates] for it, signer's own first. [algorithm] must say which kind [key] is.
 */
public fun webCryptoApkSigner(
  key: JsAny,
  algorithm: SignatureAlgorithm,
  certificates: List<ByteArray>,
): ApkSigner =
  FunctionApkSigner(algorithm, certificates) { data ->
    when (algorithm) {
      SignatureAlgorithm.ECDSA_SHA256 ->
        Der.ecdsaP1363ToDer(hexToBytes(jsSignEcdsaHex(key, data.toHex()).await().toString()))
      SignatureAlgorithm.RSA_PKCS1_SHA256 ->
        hexToBytes(jsSignRsaHex(key, data.toHex()).await().toString())
    }
  }

/**
 * An [ApkSigner] for a PKCS#8 private key (what `openssl pkcs8 -topk8` and Java's
 * `PrivateKey.getEncoded()` produce) with its DER [certificates], imported into WebCrypto as a
 * non-extractable key.
 */
public suspend fun webCryptoApkSigner(
  pkcs8: ByteArray,
  algorithm: SignatureAlgorithm,
  certificates: List<ByteArray>,
): ApkSigner {
  val key =
    when (algorithm) {
      SignatureAlgorithm.ECDSA_SHA256 -> jsImportEcdsaPkcs8(pkcs8.toHex()).await()
      SignatureAlgorithm.RSA_PKCS1_SHA256 -> jsImportRsaPkcs8(pkcs8.toHex()).await()
    }
  return webCryptoApkSigner(key, algorithm, certificates)
}
