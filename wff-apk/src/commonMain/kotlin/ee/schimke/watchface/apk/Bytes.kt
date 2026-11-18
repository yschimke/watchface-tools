package ee.schimke.watchface.apk

/** A growable little-endian byte buffer, the only way anything in this module writes binary. */
internal class ByteSink(initialCapacity: Int = 256) {
  private var buf = ByteArray(initialCapacity.coerceAtLeast(16))
  var size: Int = 0
    private set

  private fun ensure(extra: Int) {
    val need = size + extra
    if (need > buf.size) buf = buf.copyOf(maxOf(need, buf.size * 2))
  }

  fun u8(v: Int): ByteSink {
    ensure(1)
    buf[size++] = v.toByte()
    return this
  }

  fun u16(v: Int): ByteSink = u8(v).u8(v ushr 8)

  fun u32(v: Int): ByteSink = u16(v).u16(v ushr 16)

  fun u64(v: Long): ByteSink = u32(v.toInt()).u32((v ushr 32).toInt())

  fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off): ByteSink {
    ensure(len)
    b.copyInto(buf, size, off, off + len)
    size += len
    return this
  }

  fun zeros(n: Int): ByteSink {
    ensure(n)
    size += n
    return this
  }

  /** Pads with zeros to a multiple of [n]. */
  fun alignTo(n: Int): ByteSink = zeros((n - size % n) % n)

  fun putU32(at: Int, v: Int) {
    for (i in 0 until 4) buf[at + i] = (v ushr (8 * i)).toByte()
  }

  fun putU16(at: Int, v: Int) {
    buf[at] = v.toByte()
    buf[at + 1] = (v ushr 8).toByte()
  }

  fun toByteArray(): ByteArray = buf.copyOf(size)
}

/** Builds a chunk: writes a header with a placeholder size, runs [body], then patches the size. */
internal inline fun ByteSink.chunk(type: Int, headerSize: Int, body: ByteSink.() -> Unit) {
  val start = size
  u16(type).u16(headerSize).u32(0)
  body()
  putU32(start + 4, size - start)
}

internal fun ByteArray.u16At(i: Int): Int =
  (this[i].toInt() and 0xff) or ((this[i + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.u32At(i: Int): Int = u16At(i) or (u16At(i + 2) shl 16)

internal fun ByteArray.toHex(): String = buildString {
  for (b in this@toHex) {
    val v = b.toInt() and 0xff
    append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 15])
  }
}

internal fun hexToBytes(hex: String): ByteArray {
  require(hex.length % 2 == 0) { "odd hex length" }
  return ByteArray(hex.length / 2) { i ->
    ((hex[2 * i].digitToInt(16) shl 4) or hex[2 * i + 1].digitToInt(16)).toByte()
  }
}

internal fun concat(vararg parts: ByteArray): ByteArray {
  val out = ByteArray(parts.sumOf { it.size })
  var at = 0
  for (p in parts) {
    p.copyInto(out, at)
    at += p.size
  }
  return out
}
