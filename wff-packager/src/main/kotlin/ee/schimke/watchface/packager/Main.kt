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
  --token-out <file>         also write the validation token to this file

Needs the Android SDK (aapt2, zipalign, android.jar) via ANDROID_HOME or ANDROID_SDK_ROOT."""

public fun main(args: Array<String>) {
  if (args.isEmpty() || args[0] in setOf("-h", "--help", "help")) {
    println(USAGE)
    exitProcess(if (args.isEmpty()) 2 else 0)
  }
  if (args[0] != "pack") {
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
  exitProcess(runPack(flags))
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
      "token-out",
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
    val versionCode =
      flags["version-code"]?.toIntOrNull()
        ?: if ("version-code" in flags) {
          throw IllegalArgumentException("--version-code must be an integer")
        } else 1
    val target =
      flags["push-marketplace"]?.let { marketplace ->
        val face =
          flags["face-name"]
            ?: defaultFaceName(bundle)
            ?: throw IllegalArgumentException("--face-name is required (bundle has no package)")
        PackageTarget.WatchFacePush(marketplace, face)
      } ?: PackageTarget.Sideload(flags["package"])
    val signing =
      flags["keystore"]?.let {
        SigningConfig.Keystore(
          File(it),
          flags["keystore-pass"] ?: throw IllegalArgumentException("--keystore-pass is required"),
          flags["key-alias"],
          flags["key-pass"] ?: flags.getValue("keystore-pass"),
        )
      } ?: SigningConfig.GeneratedDebugKey()
    val options =
      PackagerOptions(
        target,
        versionCode,
        signing,
        flags["previous-version-code"]?.toIntOrNull(),
      )
    val packaged = WatchFacePackager().pack(bundle, options)
    out.absoluteFile.parentFile?.mkdirs()
    out.writeBytes(packaged.apk)
    packaged.diagnostics.forEach { System.err.println(it) }
    println("wrote $out (${packaged.apk.size} bytes)")
    println(
      "package: ${packaged.packageName}  versionCode: ${packaged.versionCode}  wff: ${packaged.wffVersion.number}",
    )
    println("memory: ${packaged.memoryFootprint}")
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

private fun defaultFaceName(bundle: Map<String, ByteArray>): String? =
  bundle["AndroidManifest.xml"]
    ?.let { ee.schimke.watchface.wff.WffXmlParser.parseOrNull(it.decodeToString()) }
    ?.root
    ?.attr("package")
    ?.substringAfterLast('.')
    ?.replace(Regex("[^A-Za-z0-9_]"), "_")
