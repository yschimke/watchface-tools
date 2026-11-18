package ee.schimke.watchface.apk

import kotlin.test.Test
import kotlin.test.assertEquals

class PrimitivesTest {
  private fun sha(s: String) = Sha256.digest(s.encodeToByteArray()).toHex()

  @Test
  fun sha256KnownVectors() {
    assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", sha(""))
    assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha("abc"))
    assertEquals(
      "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
      sha("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
    )
    // A million 'a': exercises the multi-block path and the incremental update.
    val h = Sha256()
    val chunk = ByteArray(1000) { 'a'.code.toByte() }
    repeat(1000) { h.update(chunk) }
    assertEquals(
      "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0",
      h.finish().toHex(),
    )
  }

  @Test
  fun crc32KnownVector() {
    assertEquals(0xCBF43926.toInt(), Crc32.of("123456789".encodeToByteArray()))
    assertEquals(0, Crc32.of(ByteArray(0)))
  }

  @Test
  fun derIntegerKeepsItPositive() {
    assertEquals("02020080", Der.integer(byteArrayOf(0x80.toByte())).toHex())
    assertEquals("02017f", Der.integer(byteArrayOf(0, 0, 0x7f)).toHex())
    assertEquals("06032a8648", Der.oid("1.2.840").toHex())
  }

  @Test
  fun stringPoolRoundTripsBothEncodings() {
    for (utf8 in listOf(true, false)) {
      val pool = writeStringPool(listOf("a", "héllo", "x".repeat(200)), utf8)
      assertEquals(0, pool.size % 4)
      assertEquals(0x0001, pool.u16At(0))
      assertEquals(3, pool.u32At(8))
      assertEquals(if (utf8) 0x100 else 0, pool.u32At(16))
    }
  }
}
