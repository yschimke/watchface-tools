package ee.schimke.watchface.packager

import java.io.File
import kotlin.system.exitProcess

private const val USAGE =
  """wff-packager pack --bundle <dir> --out <file.apk> --version-code <n> [options]

  --bundle <dir>             source bundle: AndroidManifest.xml and res/ (WffBundleLayout)
  --out <file>               APK to write
  --version-code <n>         monotonically increasing per face (default 1)
  --push-marketplace <pkg>   build for Watch Face Push: package <pkg>.watchfacepush.<face>,
                             run the official validator and print the validation token
  --face-name <name>         face name for push (default: last segment of the bundle's package)
  --package <name>           package for a sideload build (default: the bundle's)
  --previous-version-code n  reject unless --version-code is greater
  --keystore <file> --keystore-pass <pw> [--key-alias a] [--key-pass pw]
                             sign with this keystore (default: generated per-installation debug key)
  --keystore-from-env true   sign with the keystore in WFF_SIGNING_KEYSTORE_B64 (base64 PKCS12),
                             WFF_SIGNING_KEYSTORE_PASSWORD, and optional WFF_SIGNING_KEY_ALIAS /
                             WFF_SIGNING_KEY_PASSWORD; WFF_SIGNING_CERT_SHA256 pins the certificate
  --expect-cert-sha256 <hex> refuse to sign unless the key's certificate has this SHA-256
  --token-out <file>         also write the validation token to this file
  --report-out <file.json>   write the JSON report of every check (written on failure too; it
                             never contains the token)

wff-packager validate --bundle <dir> --report-out <file.json> [options]

  Runs every check without stopping at the first failure, signs with the debug key and writes
  no APK. Exit code 0 when every check passed, 1 otherwise.
  --push-marketplace <pkg>   run the Watch Face Push validator for this marketplace (default:
                             inferred from a bundle package <pkg>.watchfacepush.<face>; without
                             one the push validator is skipped)
  --face-name, --package, --version-code   as for pack

wff-packager estimate --bundle <dir> --report-out <file.json>

  The cheap report: wff-core and a per-resource memory estimate, with no SDK or Google validator
  (the same code runs in the browser: WffEstimate). Exit code 0 when it passed, 1 otherwise.

Needs the Android SDK (aapt2, zipalign, android.jar) via ANDROID_HOME or ANDROID_SDK_ROOT."""

public fun main(args: Array<String>) {
  if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
    println(USAGE)
    exitProcess(if (args.isEmpty()) 2 else 0)
  }
  if (args[0] !in setOf("pack", "validate", "estimate")) {
    System.err.println("Unknown command '${args[0]}'\n\n$USAGE")
    exitProcess(2)
  }
  val flags =
    try {
      parseFlags(args.drop(1))
    } catch (e: IllegalArgumentException) {
      System.err.println("${e.message}\n\n$USAGE")
      exitProcess(2)
    }
  exitProcess(
    when (args[0]) {
      "pack" -> runPack(flags)
      "validate" -> runValidate(flags)
      else -> runEstimate(flags)
    },
  )
}

internal fun parseFlags(args: List<String>): Map<String, String> {
  val out = mutableMapOf<String, String>()
  var i = 0
  while (i < args.size) {
    val a = args[i]
    require(a.startsWith("--")) { "Unexpected argument '$a'" }
    val (name, inline) = a.removePrefix("--").split('=', limit = 2).let { it[0] to it.getOrNull(1) }
    val value =
      inline ?: args.getOrNull(++i) ?: throw IllegalArgumentException("--$name needs a value")
    out[name] = value
    i++
  }
  return out
}

internal fun readBundleDir(dir: File): Map<String, ByteArray> {
  require(dir.isDirectory) { "Bundle directory $dir does not exist" }
  return dir
    .walkTopDown()
    .filter { it.isFile }
    .associate { it.relativeTo(dir).path.replace(File.separatorChar, '/') to it.readBytes() }
    .toSortedMap()
}

internal fun runPack(flags: Map<String, String>): Int {
  val known =
    setOf(
      "bundle",
      "out",
      "version-code",
      "push-marketplace",
      "face-name",
      "package",
      "previous-version-code",
      "keystore",
      "keystore-pass",
      "key-alias",
      "key-pass",
      "keystore-from-env",
      "expect-cert-sha256",
      "token-out",
      "report-out",
    )
  (flags.keys - known).firstOrNull()?.let {
    System.err.println("Unknown option --$it\n\n$USAGE")
    return 2
  }
  val bundleDir = flags["bundle"]?.let(::File)
  val out = flags["out"]?.let(::File)
  if (bundleDir == null || out == null) {
    System.err.println("--bundle and --out are required\n\n$USAGE")
    return 2
  }
  return try {
    val bundle = readBundleDir(bundleDir)
    val versionCode = versionCode(flags)
    val target = target(flags, flags["push-marketplace"], bundle)
    val fromEnv =
      when (flags["keystore-from-env"]) {
        null,
        "false" -> false
        "true" -> true
        else -> throw IllegalArgumentException("--keystore-from-env takes true or false")
      }
    require(!(fromEnv && "keystore" in flags)) {
      "--keystore and --keystore-from-env are mutually exclusive"
    }
    val envSigning =
      if (fromEnv) {
        SigningConfig.fromEnvironment()
          ?: throw IllegalArgumentException(
            "--keystore-from-env needs ${SigningConfig.ENV_KEYSTORE_B64} to be set",
          )
      } else null
    val expectedCert =
      flags["expect-cert-sha256"]
        ?: if (fromEnv) SigningConfig.expectedCertificateFromEnvironment() else null
    val signing =
      envSigning
        ?: flags["keystore"]?.let {
          SigningConfig.Keystore(
            File(it),
            flags["keystore-pass"] ?: throw IllegalArgumentException("--keystore-pass is required"),
            flags["key-alias"],
            flags["key-pass"] ?: flags.getValue("keystore-pass"),
          )
        }
        ?: SigningConfig.GeneratedDebugKey()
    val options =
      PackagerOptions(
        target,
        versionCode,
        signing,
        flags["previous-version-code"]?.toIntOrNull(),
        expectedCertificateSha256 = expectedCert,
      )
    val reportOut = flags["report-out"]?.let(::File)
    val packaged =
      if (reportOut == null) WatchFacePackager().pack(bundle, options)
      else {
        val outcome = WatchFacePackager().packWithReport(bundle, options)
        writeReport(reportOut, outcome.report)
        outcome.packaged ?: throw PackagingException(outcome.diagnostics)
      }
    out.absoluteFile.parentFile?.mkdirs()
    out.writeBytes(packaged.apk)
    packaged.diagnostics.forEach { System.err.println(it) }
    println("wrote $out (${packaged.apk.size} bytes)")
    println(
      "package: ${packaged.packageName}  versionCode: ${packaged.versionCode}  wff: ${packaged.wffVersion.number}",
    )
    println("memory: ${packaged.memoryFootprint}")
    println("signer certificate SHA-256: ${packaged.signerCertificateSha256}")
    packaged.validationToken?.let {
      println("validation token: $it")
      flags["token-out"]?.let { f -> File(f).writeText(it) }
    }
    0
  } catch (e: PackagingException) {
    e.diagnostics.forEach { System.err.println(it) }
    1
  } catch (e: IllegalArgumentException) {
    System.err.println(e.message)
    2
  }
}

internal fun runValidate(
  flags: Map<String, String>,
  debugKeyDirectory: File = SigningConfig.defaultDebugKeyDirectory(),
): Int {
  val known =
    setOf("bundle", "report-out", "version-code", "push-marketplace", "face-name", "package")
  (flags.keys - known).firstOrNull()?.let {
    System.err.println("Unknown option --$it for validate (it signs with the debug key)\n\n$USAGE")
    return 2
  }
  val bundleDir = flags["bundle"]?.let(::File)
  val reportOut = flags["report-out"]?.let(::File)
  if (bundleDir == null || reportOut == null) {
    System.err.println("--bundle and --report-out are required\n\n$USAGE")
    return 2
  }
  return try {
    val bundle = readBundleDir(bundleDir)
    val marketplace = flags["push-marketplace"] ?: inferredMarketplace(bundle)
    val options =
      PackagerOptions(
        target(flags, marketplace, bundle),
        versionCode(flags),
        SigningConfig.GeneratedDebugKey(debugKeyDirectory),
      )
    val report = WatchFacePackager().validate(bundle, options)
    writeReport(reportOut, report)
    printReport(report, reportOut)
    if (report.passed) 0 else 1
  } catch (e: IllegalArgumentException) {
    System.err.println(e.message)
    2
  }
}

internal fun runEstimate(flags: Map<String, String>): Int {
  (flags.keys - setOf("bundle", "report-out")).firstOrNull()?.let {
    System.err.println("Unknown option --$it for estimate\n\n$USAGE")
    return 2
  }
  val bundleDir = flags["bundle"]?.let(::File)
  val reportOut = flags["report-out"]?.let(::File)
  if (bundleDir == null || reportOut == null) {
    System.err.println("--bundle and --report-out are required\n\n$USAGE")
    return 2
  }
  return try {
    val report = WffEstimate.report(readBundleDir(bundleDir))
    writeReport(reportOut, report)
    printReport(report, reportOut)
    if (report.passed) 0 else 1
  } catch (e: IllegalArgumentException) {
    System.err.println(e.message)
    2
  }
}

private fun versionCode(flags: Map<String, String>): Int =
  flags["version-code"]?.toIntOrNull()
    ?: if ("version-code" in flags) {
      throw IllegalArgumentException("--version-code must be an integer")
    } else 1

private fun target(
  flags: Map<String, String>,
  marketplace: String?,
  bundle: Map<String, ByteArray>,
): PackageTarget =
  marketplace?.let {
    val face =
      flags["face-name"]
        ?: defaultFaceName(bundle)
        ?: throw IllegalArgumentException("--face-name is required (bundle has no package)")
    PackageTarget.WatchFacePush(it, face)
  } ?: PackageTarget.Sideload(flags["package"])

private fun writeReport(file: File, report: PackagerReport) {
  file.absoluteFile.parentFile?.mkdirs()
  file.writeText(report.toJson() + "\n")
}

/** `a.b` for a bundle whose package is `a.b.watchfacepush.<face>`. */
internal fun inferredMarketplace(bundle: Map<String, ByteArray>): String? =
  bundlePackage(bundle)?.substringBefore(".watchfacepush.", "")?.takeIf(String::isNotEmpty)

private fun bundlePackage(bundle: Map<String, ByteArray>): String? =
  bundle["AndroidManifest.xml"]
    ?.let { ee.schimke.watchface.wff.WffXmlParser.parseOrNull(it.decodeToString()) }
    ?.root
    ?.attr("package")

private fun defaultFaceName(bundle: Map<String, ByteArray>): String? =
  bundlePackage(bundle)?.substringAfterLast('.')?.replace(Regex("[^A-Za-z0-9_]"), "_")

private fun printReport(report: PackagerReport, reportOut: File) {
  for (c in report.checks) {
    println("${c.status.name.lowercase()}  ${c.id}")
    c.findings.forEach { f ->
      val at =
        listOfNotNull(
            f.line?.let { l -> "line $l" + (f.column?.let { ", column $it" } ?: "") },
            f.path,
          )
          .joinToString(" ")
      println(
        "  ${f.severity.name.lowercase()}: ${f.message}${if (at.isEmpty()) "" else " ($at)"}",
      )
      f.suggestion?.let { println("    suggestion: $it") }
    }
  }
  println(if (report.passed) "passed" else "failed")
  println("wrote $reportOut")
}
