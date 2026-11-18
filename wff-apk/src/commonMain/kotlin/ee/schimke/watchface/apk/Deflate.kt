package ee.schimke.watchface.apk

/**
 * A small raw DEFLATE compressor (RFC 1951): greedy LZ77 over a 32 KiB window with hash chains,
 * emitted as one fixed-Huffman block. It compresses markup well enough for an APK (the XML
 * resources are what is worth deflating) without a dependency or a platform `Deflater`; it is not
 * trying to match zlib's ratio. Output is a valid raw stream any inflater reads.
 */
internal object Deflate {
  private val lengthBase =
    intArrayOf(
      3,
      4,
      5,
      6,
      7,
      8,
      9,
      10,
      11,
      13,
      15,
      17,
      19,
      23,
      27,
      31,
      35,
      43,
      51,
      59,
      67,
      83,
      99,
      115,
      131,
      163,
      195,
      227,
      258,
    )
  private val lengthExtra =
    intArrayOf(
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      0,
      1,
      1,
      1,
      1,
      2,
      2,
      2,
      2,
      3,
      3,
      3,
      3,
      4,
      4,
      4,
      4,
      5,
      5,
      5,
      5,
      0,
    )
  private val distBase =
    intArrayOf(
      1,
      2,
      3,
      4,
      5,
      7,
      9,
      13,
      17,
      25,
      33,
      49,
      65,
      97,
      129,
      193,
      257,
      385,
      513,
      769,
      1025,
      1537,
      2049,
      3073,
      4097,
      6145,
      8193,
      12289,
      16385,
      24577,
    )
  private val distExtra =
    intArrayOf(
      0,
      0,
      0,
      0,
      1,
      1,
      2,
      2,
      3,
      3,
      4,
      4,
      5,
      5,
      6,
      6,
      7,
      7,
      8,
      8,
      9,
      9,
      10,
      10,
      11,
      11,
      12,
      12,
      13,
      13,
    )

  private const val WINDOW = 32768
  private const val MIN_MATCH = 3
  private const val MAX_MATCH = 258
  private const val HASH_BITS = 15
  private const val MAX_CHAIN = 32

  private class Bits {
    val out = ByteSink(1024)
    private var acc = 0
    private var n = 0

    /** [count] bits of [value], least significant first (header bits and extra bits). */
    fun put(value: Int, count: Int) {
      acc = acc or (value shl n)
      n += count
      while (n >= 8) {
        out.u8(acc)
        acc = acc ushr 8
        n -= 8
      }
    }

    /** A Huffman code: its bits go out most significant first. */
    fun putCode(code: Int, len: Int) {
      var r = 0
      for (i in 0 until len) r = r or (((code ushr i) and 1) shl (len - 1 - i))
      put(r, len)
    }

    fun finish(): ByteArray {
      if (n > 0) out.u8(acc)
      return out.toByteArray()
    }
  }

  private fun literalOrLength(b: Bits, sym: Int) {
    when {
      sym <= 143 -> b.putCode(0x30 + sym, 8)
      sym <= 255 -> b.putCode(0x190 + sym - 144, 9)
      sym <= 279 -> b.putCode(sym - 256, 7)
      else -> b.putCode(0xC0 + sym - 280, 8)
    }
  }

  private fun lengthSymbol(len: Int): Int {
    var i = lengthBase.size - 1
    while (lengthBase[i] > len) i--
    return i
  }

  private fun distSymbol(dist: Int): Int {
    var i = distBase.size - 1
    while (distBase[i] > dist) i--
    return i
  }

  fun compress(data: ByteArray): ByteArray {
    val b = Bits()
    b.put(1, 1) // BFINAL
    b.put(1, 2) // BTYPE = fixed Huffman
    val n = data.size
    val head = IntArray(1 shl HASH_BITS) { -1 }
    val prev = IntArray(n)

    fun hash(i: Int): Int =
      (((data[i].toInt() and 0xff) shl 10) xor
        ((data[i + 1].toInt() and 0xff) shl 5) xor
        (data[i + 2].toInt() and 0xff)) and ((1 shl HASH_BITS) - 1)

    fun insert(i: Int) {
      if (i + MIN_MATCH > n) return
      val h = hash(i)
      prev[i] = head[h]
      head[h] = i
    }

    var i = 0
    while (i < n) {
      var bestLen = 0
      var bestDist = 0
      if (i + MIN_MATCH <= n) {
        var cand = head[hash(i)]
        var chain = 0
        val limit = minOf(MAX_MATCH, n - i)
        while (cand >= 0 && i - cand <= WINDOW && chain++ < MAX_CHAIN) {
          var l = 0
          while (l < limit && data[cand + l] == data[i + l]) l++
          if (l > bestLen) {
            bestLen = l
            bestDist = i - cand
            if (l == limit) break
          }
          cand = prev[cand]
        }
      }
      if (bestLen >= MIN_MATCH) {
        val ls = lengthSymbol(bestLen)
        literalOrLength(b, 257 + ls)
        if (lengthExtra[ls] > 0) b.put(bestLen - lengthBase[ls], lengthExtra[ls])
        val ds = distSymbol(bestDist)
        b.putCode(ds, 5)
        if (distExtra[ds] > 0) b.put(bestDist - distBase[ds], distExtra[ds])
        for (k in 0 until bestLen) insert(i + k)
        i += bestLen
      } else {
        literalOrLength(b, data[i].toInt() and 0xff)
        insert(i)
        i++
      }
    }
    literalOrLength(b, 256)
    return b.finish()
  }
}
