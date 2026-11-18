package ee.schimke.watchface.apk

/**
 * A `ResStringPool` chunk under construction. Strings are deduplicated by value *and* an optional
 * key: an attribute name that has a resource id must not share its slot with the same text used as
 * a plain name, because a binary XML resource map assigns an id to a slot, not to a spelling.
 */
internal class StringPoolBuilder {
  private val strings = ArrayList<String>()
  private val index = HashMap<Pair<String, Int>, Int>()

  fun intern(value: String, key: Int = 0): Int =
    index.getOrPut(value to key) {
      strings += value
      strings.size - 1
    }

  val count: Int
    get() = strings.size

  fun build(utf8: Boolean): ByteArray = writeStringPool(strings, utf8)
}

/** `RES_STRING_POOL_TYPE` (0x0001) for [strings] in the given order, 4-byte aligned. */
internal fun writeStringPool(strings: List<String>, utf8: Boolean): ByteArray {
  val data = ByteSink(256)
  val offsets = IntArray(strings.size)
  for ((i, s) in strings.withIndex()) {
    offsets[i] = data.size
    if (utf8) {
      val bytes = s.encodeToByteArray()
      utf8Length(data, s.length)
      utf8Length(data, bytes.size)
      data.bytes(bytes).u8(0)
    } else {
      utf16Length(data, s.length)
      for (c in s) data.u16(c.code)
      data.u16(0)
    }
  }
  data.alignTo(4)
  val out = ByteSink(data.size + 64)
  out.chunk(0x0001, 28) {
    u32(strings.size).u32(0).u32(if (utf8) 0x100 else 0)
    u32(28 + 4 * strings.size).u32(0)
    for (o in offsets) u32(o)
    bytes(data.toByteArray())
  }
  return out.toByteArray()
}

private fun utf8Length(out: ByteSink, n: Int) {
  require(n < 0x8000) { "string too long for a resource pool" }
  if (n > 0x7f) out.u8((n ushr 8) or 0x80).u8(n) else out.u8(n)
}

private fun utf16Length(out: ByteSink, n: Int) {
  if (n > 0x7fff) out.u16((n ushr 16) or 0x8000).u16(n) else out.u16(n)
}
