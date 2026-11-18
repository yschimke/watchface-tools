package ee.schimke.watchface.apk

/**
 * One file to put in the archive. [compress] is a request: it is dropped when it would not help.
 */
internal class ZipItem(val name: String, val data: ByteArray, val compress: Boolean)

/** A built archive and where its parts start, which the signing scheme needs. */
internal class ZipImage(val bytes: ByteArray, val centralDirectoryOffset: Int, val eocdOffset: Int)

/**
 * Writes an APK-shaped zip: sizes known up front (no data descriptors), a fixed 1980-01-01 time so
 * the same input gives the same bytes, and every stored entry's data starting on a 4-byte boundary
 * through an extra-field pad, which is what `zipalign -p 4` does to the same archive.
 */
internal object Zip {
  private const val ALIGNMENT = 4
  private const val DOS_DATE = 0x0021 // 1980-01-01
  private const val ALIGN_FIELD_ID = 0xD935 // the id zipalign gives its pad

  fun build(items: List<ZipItem>): ZipImage {
    require(items.size < 0xFFFF) { "too many entries for a plain zip" }
    val out = ByteSink(items.sumOf { it.data.size } + 1024)
    class Placed(
      val item: ZipItem,
      val nameBytes: ByteArray,
      val method: Int,
      val crc: Int,
      val csize: Int,
      val offset: Int,
    )
    val placed = ArrayList<Placed>(items.size)
    for (item in items) {
      val nameBytes = item.name.encodeToByteArray()
      val deflated = if (item.compress && item.data.size > 16) Deflate.compress(item.data) else null
      val useDeflate = deflated != null && deflated.size < item.data.size
      val payload = if (useDeflate) deflated!! else item.data
      require(out.size.toLong() + payload.size < Int.MAX_VALUE) { "APK larger than 2 GiB" }
      val offset = out.size
      val ascii = item.name.all { it.code < 0x80 }
      // Only a stored entry has to be aligned; a deflated one is not read in place.
      val dataStart = offset + 30 + nameBytes.size
      val pad = if (useDeflate) 0 else padFor(dataStart)
      out.u32(0x04034b50)
      out.u16(20)
      out.u16(if (ascii) 0 else 0x0800)
      out.u16(if (useDeflate) 8 else 0)
      out.u16(0).u16(DOS_DATE)
      out.u32(Crc32.of(item.data))
      out.u32(payload.size).u32(item.data.size)
      out.u16(nameBytes.size).u16(pad)
      out.bytes(nameBytes)
      if (pad > 0) {
        out.u16(ALIGN_FIELD_ID).u16(pad - 4).u16(ALIGNMENT).zeros(pad - 6)
      }
      out.bytes(payload)
      placed +=
        Placed(item, nameBytes, if (useDeflate) 8 else 0, Crc32.of(item.data), payload.size, offset)
    }
    val cdOffset = out.size
    for (p in placed) {
      val ascii = p.item.name.all { it.code < 0x80 }
      out.u32(0x02014b50)
      out.u16(20).u16(20)
      out.u16(if (ascii) 0 else 0x0800)
      out.u16(p.method)
      out.u16(0).u16(DOS_DATE)
      out.u32(p.crc).u32(p.csize).u32(p.item.data.size)
      out.u16(p.nameBytes.size).u16(0).u16(0)
      out.u16(0).u16(0).u32(0)
      out.u32(p.offset)
      out.bytes(p.nameBytes)
    }
    val eocdOffset = out.size
    out.u32(0x06054b50).u16(0).u16(0).u16(placed.size).u16(placed.size)
    out.u32(eocdOffset - cdOffset).u32(cdOffset).u16(0)
    return ZipImage(out.toByteArray(), cdOffset, eocdOffset)
  }

  /**
   * The extra-field length that moves data starting at [dataStart] onto the next boundary. An extra
   * field is at least its 4-byte header, and zipalign's carries a 2-byte alignment, so a pad is
   * either nothing or at least 6 bytes: a shortfall of 1 becomes 5 + 4 = 9, and so on.
   */
  private fun padFor(dataStart: Int): Int {
    val r = (ALIGNMENT - dataStart % ALIGNMENT) % ALIGNMENT
    if (r == 0) return 0
    var pad = r
    while (pad < 6) pad += ALIGNMENT
    return pad
  }
}
