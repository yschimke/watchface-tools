package ee.schimke.watchface.apk

import ee.schimke.watchface.packager.PackagingException

/**
 * A resource configuration (`ResTable_config`, 64 bytes) from a directory qualifier string such as
 * `""`, `fr`, `fr-rCA`, `xhdpi`, `night` or `round`. Only the qualifiers a watch face uses are
 * understood; anything else is an error naming the qualifier, because silently packaging a resource
 * under the wrong configuration changes what the face shows.
 */
internal class ResourceConfig private constructor(val qualifiers: String, val bytes: ByteArray) {
  /** The `CONFIG_*` bits this configuration varies on, for a type spec's change flags. */
  var changeFlags: Int = 0
    private set

  val isDefault: Boolean
    get() = qualifiers.isEmpty()

  companion object {
    const val SIZE = 64
    private const val CONFIG_LOCALE = 0x4
    private const val CONFIG_DENSITY = 0x100
    private const val CONFIG_VERSION = 0x400
    private const val CONFIG_SCREEN_LAYOUT = 0x800
    private const val CONFIG_UI_MODE = 0x1000
    private const val CONFIG_SCREEN_ROUND = 0x8000

    private val densities =
      mapOf(
        "ldpi" to 120,
        "mdpi" to 160,
        "tvdpi" to 213,
        "hdpi" to 240,
        "xhdpi" to 320,
        "xxhdpi" to 480,
        "xxxhdpi" to 640,
        "nodpi" to 0xffff,
        "anydpi" to 0xfffe,
      )

    fun parse(qualifiers: String, owner: String): ResourceConfig {
      val b = ByteArray(SIZE)
      ByteSink(4).u32(SIZE).toByteArray().copyInto(b)
      var flags = 0
      fun bad(msg: String): Nothing = throw PackagingException("resources", "$owner: $msg")
      var locale = false
      if (qualifiers.isNotEmpty()) {
        for (q in qualifiers.split('-')) {
          when {
            q in densities -> {
              b[14] = densities.getValue(q).toByte()
              b[15] = (densities.getValue(q) ushr 8).toByte()
              flags = flags or CONFIG_DENSITY
            }
            q == "night" -> {
              b[29] = ((b[29].toInt() and 0x0f) or 0x20).toByte()
              flags = flags or CONFIG_UI_MODE
            }
            q == "notnight" -> {
              b[29] = ((b[29].toInt() and 0x0f) or 0x10).toByte()
              flags = flags or CONFIG_UI_MODE
            }
            q == "round" -> {
              b[48] = ((b[48].toInt() and 0xfc) or 2).toByte()
              flags = flags or CONFIG_SCREEN_ROUND
            }
            q == "notround" -> {
              b[48] = ((b[48].toInt() and 0xfc) or 1).toByte()
              flags = flags or CONFIG_SCREEN_ROUND
            }
            q == "ldltr" || q == "ldrtl" -> {
              b[28] = ((b[28].toInt() and 0x3f) or if (q == "ldltr") 0x40 else 0x80).toByte()
              flags = flags or CONFIG_SCREEN_LAYOUT
            }
            q.length >= 2 && q[0] == 'v' && q.drop(1).all { it.isDigit() } -> {
              val v = q.drop(1).toInt()
              b[24] = v.toByte()
              b[25] = (v ushr 8).toByte()
              flags = flags or CONFIG_VERSION
            }
            !locale && q.length == 2 && q.all { it in 'a'..'z' } -> {
              b[8] = q[0].code.toByte()
              b[9] = q[1].code.toByte()
              locale = true
              flags = flags or CONFIG_LOCALE
            }
            locale && q.length == 3 && q[0] == 'r' && q.drop(1).all { it in 'A'..'Z' } -> {
              b[10] = q[1].code.toByte()
              b[11] = q[2].code.toByte()
            }
            else ->
              bad(
                "unsupported resource qualifier '$q' in '$qualifiers' (supported: a two-letter " +
                  "language and -rXX region, ${densities.keys.joinToString()}, night, notnight, " +
                  "round, notround, ldltr, ldrtl, vNN)",
              )
          }
        }
      }
      return ResourceConfig(qualifiers, b).also { it.changeFlags = flags }
    }

    /** `ResTable_config` sorts the default configuration first, then by bytes. */
    val order: Comparator<ResourceConfig> = Comparator { a, b ->
      for (i in 4 until SIZE) {
        val d = (a.bytes[i].toInt() and 0xff) - (b.bytes[i].toInt() and 0xff)
        if (d != 0) return@Comparator d
      }
      0
    }
  }
}
