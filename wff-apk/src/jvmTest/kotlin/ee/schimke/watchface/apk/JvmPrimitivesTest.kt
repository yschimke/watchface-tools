package ee.schimke.watchface.apk

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure-Kotlin primitives against the JDK's. */
class JvmPrimitivesTest {
  private val random = Random(42)

  private fun inflate(data: ByteArray, size: Int): ByteArray {
    val inf = Inflater(true)
    inf.setInput(data)
    val out = ByteArray(size)
    var n = 0
    while (!inf.finished()) {
      val got = inf.inflate(out, n, size - n)
      n += got
      if (got == 0 && !inf.finished()) break
    }
    assertTrue(inf.finished(), "stream not terminated")
    inf.end()
    return out
  }

  @Test
  fun sha256MatchesTheJdkAcrossSizes() {
    for (size in listOf(0, 1, 55, 56, 57, 63, 64, 65, 119, 120, 1000, 100_003)) {
      val data = ByteArray(size).also(random::nextBytes)
      assertContentEquals(
        MessageDigest.getInstance("SHA-256").digest(data),
        Sha256.digest(data),
        "size $size",
      )
    }
  }

  @Test
  fun crc32MatchesTheJdk() {
    val data = ByteArray(10_000).also(random::nextBytes)
    assertEquals(CRC32().apply { update(data) }.value.toInt(), Crc32.of(data))
  }

  @Test
  fun deflateRoundTripsThroughTheJdkInflater() {
    val samples =
      listOf(
        ByteArray(0),
        byteArrayOf(7),
        "abcabcabcabcabcabcabc".encodeToByteArray(),
        ByteArray(5000) { 'x'.code.toByte() },
        ByteArray(70_000).also(random::nextBytes),
        // Pseudo-text with repeats at distances across the window.
        buildString { repeat(4000) { append("<Part x=\"${it % 37}\" y=\"${it % 11}\"/>\n") } }
          .encodeToByteArray(),
        ByteArray(300_000) { (it * 31 % 251).toByte() },
      )
    for (data in samples) {
      assertContentEquals(data, inflate(Deflate.compress(data), data.size), "size ${data.size}")
    }
    val xml = samples[5]
    assertTrue(Deflate.compress(xml).size < xml.size / 3, "repetitive markup should compress well")
  }

  @Test
  fun zipReadsBackThroughTheJdkWithEntriesAligned() {
    val items =
      listOf(
        ZipItem("a.txt", "hello hello hello hello hello".encodeToByteArray(), compress = true),
        ZipItem("b.bin", byteArrayOf(1, 2, 3), compress = false),
        ZipItem("res/ccc.png", ByteArray(100) { it.toByte() }, compress = false),
        ZipItem("dddd", ByteArray(0), compress = false),
        ZipItem("eeeee.arsc", ByteArray(33) { 9 }, compress = false),
      )
    val image = Zip.build(items)
    val read = ReadZip(image.bytes)
    for (e in read.entries.filter { it.method == 0 }) assertEquals(0, e.dataOffset % 4, e.name)
    ZipInputStream(ByteArrayInputStream(image.bytes)).use { zin ->
      for (item in items) {
        val entry = zin.nextEntry
        assertEquals(item.name, entry.name)
        assertContentEquals(item.data, zin.readBytes(), item.name)
      }
    }
    java.util.zip
      .ZipFile(
        java.io.File.createTempFile("wffapk", ".zip").also {
          it.deleteOnExit()
          it.writeBytes(image.bytes)
        },
      )
      .use { zf ->
        for (item in items) assertContentEquals(
          item.data,
          zf.getInputStream(zf.getEntry(item.name)).readBytes(),
        )
      }
  }
}
