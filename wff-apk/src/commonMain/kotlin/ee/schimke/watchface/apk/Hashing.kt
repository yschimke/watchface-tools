package ee.schimke.watchface.apk

/** SHA-256 (FIPS 180-4), incremental. Pure Kotlin so the browser needs no async digest. */
internal class Sha256 {
  private val h =
    intArrayOf(
      0x6a09e667,
      0xbb67ae85.toInt(),
      0x3c6ef372,
      0xa54ff53a.toInt(),
      0x510e527f,
      0x9b05688c.toInt(),
      0x1f83d9ab,
      0x5be0cd19,
    )
  private val block = ByteArray(64)
  private var blockLen = 0
  private var total = 0L
  private val w = IntArray(64)

  fun update(data: ByteArray, off: Int = 0, len: Int = data.size - off): Sha256 {
    var i = off
    val end = off + len
    total += len
    while (i < end) {
      if (blockLen == 0 && end - i >= 64) {
        compress(data, i)
        i += 64
        continue
      }
      val n = minOf(64 - blockLen, end - i)
      data.copyInto(block, blockLen, i, i + n)
      blockLen += n
      i += n
      if (blockLen == 64) {
        compress(block, 0)
        blockLen = 0
      }
    }
    return this
  }

  fun finish(): ByteArray {
    val bitLen = total * 8
    update(byteArrayOf(0x80.toByte()))
    while (blockLen != 56) update(byteArrayOf(0))
    val len = ByteArray(8) { (bitLen ushr (56 - 8 * it)).toByte() }
    update(len)
    return ByteArray(32) { (h[it / 4] ushr (24 - 8 * (it % 4))).toByte() }
  }

  private fun compress(src: ByteArray, off: Int) {
    for (t in 0 until 16) {
      val j = off + 4 * t
      w[t] =
        ((src[j].toInt() and 0xff) shl 24) or
          ((src[j + 1].toInt() and 0xff) shl 16) or
          ((src[j + 2].toInt() and 0xff) shl 8) or
          (src[j + 3].toInt() and 0xff)
    }
    for (t in 16 until 64) {
      val s0 = w[t - 15].rotr(7) xor w[t - 15].rotr(18) xor (w[t - 15] ushr 3)
      val s1 = w[t - 2].rotr(17) xor w[t - 2].rotr(19) xor (w[t - 2] ushr 10)
      w[t] = w[t - 16] + s0 + w[t - 7] + s1
    }
    var a = h[0]
    var b = h[1]
    var c = h[2]
    var d = h[3]
    var e = h[4]
    var f = h[5]
    var g = h[6]
    var hh = h[7]
    for (t in 0 until 64) {
      val s1 = e.rotr(6) xor e.rotr(11) xor e.rotr(25)
      val ch = (e and f) xor (e.inv() and g)
      val t1 = hh + s1 + ch + K[t] + w[t]
      val s0 = a.rotr(2) xor a.rotr(13) xor a.rotr(22)
      val maj = (a and b) xor (a and c) xor (b and c)
      val t2 = s0 + maj
      hh = g
      g = f
      f = e
      e = d + t1
      d = c
      c = b
      b = a
      a = t1 + t2
    }
    h[0] += a
    h[1] += b
    h[2] += c
    h[3] += d
    h[4] += e
    h[5] += f
    h[6] += g
    h[7] += hh
  }

  private fun Int.rotr(n: Int): Int = (this ushr n) or (this shl (32 - n))

  companion object {
    fun digest(data: ByteArray): ByteArray = Sha256().update(data).finish()

    private val K =
      intArrayOf(
        0x428a2f98,
        0x71374491,
        0xb5c0fbcf.toInt(),
        0xe9b5dba5.toInt(),
        0x3956c25b,
        0x59f111f1,
        0x923f82a4.toInt(),
        0xab1c5ed5.toInt(),
        0xd807aa98.toInt(),
        0x12835b01,
        0x243185be,
        0x550c7dc3,
        0x72be5d74,
        0x80deb1fe.toInt(),
        0x9bdc06a7.toInt(),
        0xc19bf174.toInt(),
        0xe49b69c1.toInt(),
        0xefbe4786.toInt(),
        0x0fc19dc6,
        0x240ca1cc,
        0x2de92c6f,
        0x4a7484aa,
        0x5cb0a9dc,
        0x76f988da,
        0x983e5152.toInt(),
        0xa831c66d.toInt(),
        0xb00327c8.toInt(),
        0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(),
        0xd5a79147.toInt(),
        0x06ca6351,
        0x14292967,
        0x27b70a85,
        0x2e1b2138,
        0x4d2c6dfc,
        0x53380d13,
        0x650a7354,
        0x766a0abb,
        0x81c2c92e.toInt(),
        0x92722c85.toInt(),
        0xa2bfe8a1.toInt(),
        0xa81a664b.toInt(),
        0xc24b8b70.toInt(),
        0xc76c51a3.toInt(),
        0xd192e819.toInt(),
        0xd6990624.toInt(),
        0xf40e3585.toInt(),
        0x106aa070,
        0x19a4c116,
        0x1e376c08,
        0x2748774c,
        0x34b0bcb5,
        0x391c0cb3,
        0x4ed8aa4a,
        0x5b9cca4f,
        0x682e6ff3,
        0x748f82ee,
        0x78a5636f,
        0x84c87814.toInt(),
        0x8cc70208.toInt(),
        0x90befffa.toInt(),
        0xa4506ceb.toInt(),
        0xbef9a3f7.toInt(),
        0xc67178f2.toInt(),
      )
  }
}

/** CRC-32 (IEEE 802.3), as the zip format wants. */
internal object Crc32 {
  private val table =
    IntArray(256) { n ->
      var c = n
      repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
      c
    }

  fun of(data: ByteArray): Int {
    var c = -1
    for (b in data) c = table[(c xor b.toInt()) and 0xff] xor (c ushr 8)
    return c.inv()
  }
}
