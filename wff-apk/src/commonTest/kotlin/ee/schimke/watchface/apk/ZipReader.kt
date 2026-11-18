package ee.schimke.watchface.apk

/** A deliberately independent zip reader for tests: only what the APK builder writes. */
class ReadEntry(
  val name: String,
  val method: Int,
  val flags: Int,
  val crc: Int,
  val compressedSize: Int,
  val size: Int,
  val localOffset: Int,
  val dataOffset: Int,
  val extraLength: Int,
)

class ReadZip(val bytes: ByteArray) {
  private fun u16(i: Int) = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8)

  private fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)

  val eocd: Int = bytes.size - 22
  val centralDirectoryOffset: Int
  val entries: List<ReadEntry>

  init {
    check(u32(eocd) == 0x06054b50) { "no EOCD at the end (no comment expected)" }
    val count = u16(eocd + 10)
    centralDirectoryOffset = u32(eocd + 16)
    val list = ArrayList<ReadEntry>()
    var p = centralDirectoryOffset
    repeat(count) {
      check(u32(p) == 0x02014b50) { "bad central directory entry at $p" }
      val nameLen = u16(p + 28)
      val extraLen = u16(p + 30)
      val commentLen = u16(p + 32)
      val local = u32(p + 42)
      check(u32(local) == 0x04034b50) { "bad local header at $local" }
      val lname = u16(local + 26)
      val lextra = u16(local + 28)
      list +=
        ReadEntry(
          name = bytes.decodeToString(p + 46, p + 46 + nameLen),
          method = u16(p + 10),
          flags = u16(p + 8),
          crc = u32(p + 16),
          compressedSize = u32(p + 20),
          size = u32(p + 24),
          localOffset = local,
          dataOffset = local + 30 + lname + lextra,
          extraLength = lextra,
        )
      p += 46 + nameLen + extraLen + commentLen
    }
    check(p == eocd) { "central directory does not end at the EOCD ($p vs $eocd)" }
    entries = list
  }

  fun entry(name: String): ReadEntry = entries.first { it.name == name }

  /** The stored (method 0) bytes of [name]. */
  fun stored(name: String): ByteArray {
    val e = entry(name)
    check(e.method == 0) { "$name is not stored" }
    return bytes.copyOfRange(e.dataOffset, e.dataOffset + e.size)
  }
}
