package ee.schimke.watchface.apk

import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.RSAPrivateKey
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/**
 * An [ApkSigner] over `java.security`: an RSA or ECDSA P-256 [privateKey] with the X.509
 * [certificates] that go with it (the key's own first), such as the entry a `KeyStore` yields.
 */
public class JcaApkSigner(
  private val privateKey: PrivateKey,
  certificates: List<X509Certificate>,
) : ApkSigner {
  override val certificates: List<ByteArray> = certificates.map { it.encoded }

  override val algorithm: SignatureAlgorithm =
    when (privateKey) {
      is RSAPrivateKey -> SignatureAlgorithm.RSA_PKCS1_SHA256
      is ECPrivateKey -> {
        require(privateKey.params.order.bitLength() == 256) {
          "only ECDSA over P-256 is a supported APK signing curve here"
        }
        SignatureAlgorithm.ECDSA_SHA256
      }
      else -> throw IllegalArgumentException("unsupported key type ${privateKey.algorithm}")
    }

  init {
    require(certificates.isNotEmpty()) { "a signer needs at least its own certificate" }
  }

  override suspend fun sign(data: ByteArray): ByteArray =
    Signature.getInstance(
        if (algorithm == SignatureAlgorithm.ECDSA_SHA256) "SHA256withECDSA" else "SHA256withRSA",
      )
      .run {
        initSign(privateKey)
        update(data)
        sign()
      }
}

internal actual suspend fun generateEcdsaP256(): GeneratedKey {
  val pair =
    KeyPairGenerator.getInstance("EC").run {
      initialize(ECGenParameterSpec("secp256r1"))
      generateKeyPair()
    }
  return GeneratedKey(pair.public.encoded) { data ->
    Signature.getInstance("SHA256withECDSA").run {
      initSign(pair.private)
      update(data)
      sign()
    }
  }
}

/** [generateDebugApkSigner] for blocking callers. */
public fun generateDebugApkSignerBlocking(commonName: String = "wff-apk debug key"): ApkSigner =
  runBlocking {
    generateDebugApkSigner(commonName)
  }

/**
 * [WffApkBuilder.build] for blocking callers: runs the suspending build to completion on the
 * calling thread. The JVM signers never actually suspend, so this does not need a coroutine
 * library.
 */
public fun WffApkBuilder.buildBlocking(
  bundle: Map<String, ByteArray>,
  options: WffApkOptions,
  signer: ApkSigner,
): WffApk = runBlocking { build(bundle, options, signer) }

private fun <T> runBlocking(block: suspend () -> T): T {
  var result: Result<T>? = null
  val done = CountDownLatch(1)
  block.startCoroutine(
    Continuation(EmptyCoroutineContext) {
      result = it
      done.countDown()
    },
  )
  done.await()
  return result!!.getOrThrow()
}
