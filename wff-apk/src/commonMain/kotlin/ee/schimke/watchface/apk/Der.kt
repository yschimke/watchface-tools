package ee.schimke.watchface.apk

/**
 * The little DER this module needs: building a self-signed certificate, reading a key out of one.
 */
internal object Der {
  const val SEQUENCE = 0x30
  const val SET = 0x31
  const val INTEGER = 0x02
  const val BIT_STRING = 0x03
  const val OID = 0x06
  const val UTF8_STRING = 0x0c
  const val UTC_TIME = 0x17
  const val NULL = 0x05

  fun tlv(tag: Int, value: ByteArray): ByteArray {
    val out = ByteSink(value.size + 6)
    out.u8(tag)
    val n = value.size
    when {
      n < 0x80 -> out.u8(n)
      n < 0x100 -> out.u8(0x81).u8(n)
      n < 0x10000 -> out.u8(0x82).u8(n ushr 8).u8(n)
      else -> out.u8(0x83).u8(n ushr 16).u8(n ushr 8).u8(n)
    }
    return out.bytes(value).toByteArray()
  }

  fun sequence(vararg parts: ByteArray): ByteArray = tlv(SEQUENCE, concat(*parts))

  fun set(vararg parts: ByteArray): ByteArray = tlv(SET, concat(*parts))

  /** A non-negative INTEGER from big-endian magnitude bytes. */
  fun integer(magnitude: ByteArray): ByteArray {
    var i = 0
    while (i < magnitude.size - 1 && magnitude[i] == 0.toByte()) i++
    val trimmed = magnitude.copyOfRange(i, magnitude.size)
    val v = if (trimmed.isEmpty()) byteArrayOf(0) else trimmed
    return tlv(INTEGER, if (v[0] < 0) concat(byteArrayOf(0), v) else v)
  }

  fun integer(v: Int): ByteArray =
    integer(
      byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()),
    )

  fun oid(dotted: String): ByteArray {
    val arcs = dotted.split('.').map { it.toLong() }
    val out = ByteSink(16)
    out.u8((arcs[0] * 40 + arcs[1]).toInt())
    for (arc in arcs.drop(2)) {
      var v = arc
      val groups = ArrayList<Int>()
      do {
        groups += (v and 0x7f).toInt()
        v = v ushr 7
      } while (v > 0)
      for (g in groups.indices.reversed()) out.u8(groups[g] or if (g > 0) 0x80 else 0)
    }
    return tlv(OID, out.toByteArray())
  }

  fun bitString(bytes: ByteArray): ByteArray = tlv(BIT_STRING, concat(byteArrayOf(0), bytes))

  fun utf8(text: String): ByteArray = tlv(UTF8_STRING, text.encodeToByteArray())

  fun utcTime(text: String): ByteArray = tlv(UTC_TIME, text.encodeToByteArray())

  fun nullValue(): ByteArray = byteArrayOf(NULL.toByte(), 0)

  /** A parsed element: [tag], and where its content lies in the source array. */
  class Element(val tag: Int, val start: Int, val contentStart: Int, val end: Int)

  fun read(data: ByteArray, at: Int): Element {
    val tag = data[at].toInt() and 0xff
    val first = data[at + 1].toInt() and 0xff
    var len: Int
    var p = at + 2
    if (first < 0x80) {
      len = first
    } else {
      val n = first and 0x7f
      require(n in 1..4) { "unsupported DER length" }
      len = 0
      repeat(n) { len = (len shl 8) or (data[p++].toInt() and 0xff) }
    }
    require(p + len <= data.size) { "DER element overruns its input" }
    return Element(tag, at, p, p + len)
  }

  /**
   * The `SubjectPublicKeyInfo` DER of an X.509 certificate: Certificate -> TBSCertificate, skipping
   * the optional `[0]` version, serial, signature algorithm, issuer, validity and subject.
   */
  fun subjectPublicKeyInfo(certificate: ByteArray): ByteArray {
    val cert = read(certificate, 0)
    require(cert.tag == SEQUENCE) { "certificate is not a DER SEQUENCE" }
    val tbs = read(certificate, cert.contentStart)
    var p = tbs.contentStart
    var e = read(certificate, p)
    if (e.tag == 0xa0) { // explicit version
      p = e.end
      e = read(certificate, p)
    }
    repeat(5) { // serial, signature algorithm, issuer, validity, subject
      p = read(certificate, p).end
    }
    val spki = read(certificate, p)
    return certificate.copyOfRange(spki.start, spki.end)
  }

  /**
   * An ECDSA signature as the P1363 `r || s` WebCrypto returns, as the DER the APK format wants.
   */
  fun ecdsaP1363ToDer(raw: ByteArray): ByteArray {
    require(raw.size % 2 == 0) { "ECDSA signature has odd length" }
    val half = raw.size / 2
    return sequence(integer(raw.copyOfRange(0, half)), integer(raw.copyOfRange(half, raw.size)))
  }
}
