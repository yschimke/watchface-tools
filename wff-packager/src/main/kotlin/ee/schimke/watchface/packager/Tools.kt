package ee.schimke.watchface.packager

import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit

internal fun run(source: String, vararg cmd: String) {
  // Output goes to a file, not a pipe, so a hung tool can't block us before the timeout applies.
  val log = Files.createTempFile("wff-packager-", ".log").toFile()
  try {
    val proc =
      ProcessBuilder(*cmd)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.to(log))
        .start()
    if (!proc.waitFor(120, TimeUnit.SECONDS)) {
      proc.destroyForcibly().waitFor()
      throw PackagingException(source, "${File(cmd.first()).name} timed out after 120s")
    }
    if (proc.exitValue() != 0) {
      throw PackagingException(
        source,
        "${File(cmd.first()).name} exited with ${proc.exitValue()}:\n${log.readText().trim()}",
      )
    }
  } finally {
    log.delete()
  }
}

/** Writes [files] under [dir] (rejecting path traversal). */
internal fun writeBundle(files: Map<String, ByteArray>, dir: File) {
  val root = dir.canonicalFile
  for ((path, bytes) in files) {
    val target = File(root, path).canonicalFile
    if (!target.path.startsWith(root.path + File.separator)) {
      throw PackagingException("bundle", "Bundle path '$path' escapes the bundle directory")
    }
    target.parentFile.mkdirs()
    target.writeBytes(bytes)
  }
}

/** Mirrors `ensureWffApk` in compose-ai-tools: `aapt2 compile --dir res`, then `aapt2 link`. */
internal fun linkResourceApk(sdk: AndroidSdk, bundleDir: File, out: File) {
  val compiled = File(bundleDir.parentFile, "compiled-res.zip")
  run(
    "aapt2",
    sdk.aapt2.path,
    "compile",
    "--dir",
    File(bundleDir, "res").path,
    "-o",
    compiled.path,
  )
  run(
    "aapt2",
    sdk.aapt2.path,
    "link",
    "-I",
    sdk.androidJar.path,
    "--manifest",
    File(bundleDir, "AndroidManifest.xml").path,
    "-o",
    out.path,
    compiled.path,
  )
}

internal fun zipalign(sdk: AndroidSdk, input: File, out: File) {
  val tool =
    sdk.zipalign
      ?: throw PackagingException(
        "zipalign",
        "zipalign not found in the SDK build-tools. Install with: ${AndroidSdk.INSTALL_HINT}",
      )
  // -P 16 page-aligns uncompressed .so files; harmless without them. 4-byte aligns the rest.
  run("zipalign", tool.path, "-f", "-p", "4", input.path, out.path)
}

internal class SigningKey(val key: PrivateKey, val chain: List<X509Certificate>, val debug: Boolean)

internal fun loadSigningKey(config: SigningConfig): SigningKey =
  when (config) {
    is SigningConfig.Keystore -> loadKeystore(config, debug = false)
    is SigningConfig.GeneratedDebugKey -> loadKeystore(ensureDebugKey(config.directory), true)
  }

private const val DEBUG_ALIAS = "wff-packager-debug"
private const val DEBUG_PASSWORD = "android"

private fun ensureDebugKey(dir: File): SigningConfig.Keystore {
  val file = File(dir, "debug.p12")
  if (!file.isFile) {
    dir.mkdirs()
    val tmp = Files.createTempFile(dir.toPath(), "debug", ".p12")
    Files.delete(tmp) // keytool wants to create it itself
    val keytool = File(System.getProperty("java.home"), "bin/keytool").path
    run(
      "signing",
      keytool,
      "-genkeypair",
      "-keystore",
      tmp.toString(),
      "-storetype",
      "PKCS12",
      "-storepass",
      DEBUG_PASSWORD,
      "-keypass",
      DEBUG_PASSWORD,
      "-alias",
      DEBUG_ALIAS,
      "-keyalg",
      "RSA",
      "-keysize",
      "2048",
      "-validity",
      "10950",
      "-dname",
      "CN=wff-packager debug key",
    )
    runCatching {
      Files.setPosixFilePermissions(
        tmp,
        setOf(
          java.nio.file.attribute.PosixFilePermission.OWNER_READ,
          java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
        ),
      )
    }
    // Publish without replacing: if another process generated a key first, keep (and use) theirs.
    try {
      Files.createLink(file.toPath(), tmp)
    } catch (_: java.nio.file.FileAlreadyExistsException) {
      // lost the race; fall through and load the winner's key
    } catch (_: UnsupportedOperationException) {
      runCatching { Files.move(tmp, file.toPath()) }
    } catch (_: java.io.IOException) {
      runCatching { Files.move(tmp, file.toPath()) }
    } finally {
      Files.deleteIfExists(tmp)
    }
  }
  return SigningConfig.Keystore(file, DEBUG_PASSWORD, DEBUG_ALIAS)
}

private fun loadKeystore(config: SigningConfig.Keystore, debug: Boolean): SigningKey {
  if (!config.file.isFile) {
    throw PackagingException("signing", "Keystore ${config.file} does not exist")
  }
  val ks =
    try {
      KeyStore.getInstance(config.file, config.storePassword.toCharArray())
    } catch (e: Exception) {
      throw PackagingException("signing", "Cannot open keystore ${config.file}: ${e.message}")
    }
  val alias =
    config.keyAlias
      ?: ks.aliases().toList().singleOrNull { ks.isKeyEntry(it) }
      ?: throw PackagingException(
        "signing",
        "Keystore ${config.file} needs exactly one key entry or an explicit key alias",
      )
  val key =
    try {
      ks.getKey(alias, config.keyPassword.toCharArray()) as? PrivateKey
    } catch (e: Exception) {
      throw PackagingException("signing", "Cannot read key '$alias': ${e.message}")
    } ?: throw PackagingException("signing", "No private key '$alias' in ${config.file}")
  val chain = ks.getCertificateChain(alias).map { it as X509Certificate }
  return SigningKey(key, chain, debug)
}

/** Signs with APK signature scheme v2+v3 (Wear OS 4+ never needs v1); fails if it won't verify. */
internal fun signApk(input: File, out: File, key: SigningKey, minSdk: Int) {
  val signer = ApkSigner.SignerConfig.Builder("wffpackager", key.key, key.chain).build()
  try {
    ApkSigner.Builder(listOf(signer))
      .setInputApk(input)
      .setOutputApk(out)
      .setMinSdkVersion(minSdk)
      .setV1SigningEnabled(false)
      .setV2SigningEnabled(true)
      .setV3SigningEnabled(true)
      .build()
      .sign()
  } catch (e: Exception) {
    throw PackagingException("signing", "apksig failed: ${e.message}")
  }
  val result = ApkVerifier.Builder(out).setMinCheckedPlatformVersion(minSdk).build().verify()
  if (!result.isVerified) {
    throw PackagingException(
      "signing",
      "Signed APK does not verify: " + result.errors.joinToString { it.toString() },
    )
  }
}
