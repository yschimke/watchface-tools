package ee.schimke.watchface.apk

/**
 * APK Signature Scheme v2 and v3 (source.android.com/docs/security/features/apksigning/v2, /v3): a
 * SHA-256 Merkle-style digest over the archive in 1 MiB chunks, one signature over it per scheme,
 * and the block that carries them, inserted before the zip's central directory. v1 (JAR) signing is
 * not done: Wear OS 4 and newer, the only platforms Watch Face Format runs on, verify v2/v3 and
 * never need it.
 */
internal object ApkSigningBlock {
  private const val V2_BLOCK_ID = 0x7109871a
  private const val V3_BLOCK_ID = 0xf05368c0.toInt()
  private const val STRIPPING_PROTECTION_ID = 0xbeeff00d.toInt()
  private const val STRIPPING_PROTECTION_V3 = 3
  private const val CHUNK = 1024 * 1024
  private const val MAX_SDK = 0x7fffffff
  private val MAGIC = "APK Sig Block 42".encodeToByteArray()

  /**
   * Returns [image] with a signing block holding a v2 signature and, with [v3], a v3 signature
   * valid from [minSdk] up.
   */
  suspend fun sign(image: ZipImage, signer: ApkSigner, minSdk: Int, v3: Boolean): ByteArray {
    val bytes = image.bytes
    val digest = contentDigest(bytes, image)
    val spki = Der.subjectPublicKeyInfo(signer.certificates.first())
    val algId = signer.algorithm.id

    val pairs = ByteSink(2048)
    // v2
    val v2SignedData =
      concat(
        lengthPrefixed(digestsEntry(algId, digest)),
        lengthPrefixed(certificatesEntries(signer)),
        lengthPrefixed(
          if (v3)
            attribute(
              STRIPPING_PROTECTION_ID,
              ByteSink(4).u32(STRIPPING_PROTECTION_V3).toByteArray(),
            )
          else ByteArray(0),
        ),
      )
    val v2Signer =
      lengthPrefixed(
        lengthPrefixed(v2SignedData),
        lengthPrefixed(signatureEntry(algId, signer.sign(v2SignedData))),
        lengthPrefixed(spki),
      )
    pair(pairs, V2_BLOCK_ID, lengthPrefixed(v2Signer))

    if (v3) {
      val v3SignedData =
        concat(
          lengthPrefixed(digestsEntry(algId, digest)),
          lengthPrefixed(certificatesEntries(signer)),
          ByteSink(8).u32(minSdk).u32(MAX_SDK).toByteArray(),
          lengthPrefixed(ByteArray(0)),
        )
      val v3Signer =
        lengthPrefixed(
          lengthPrefixed(v3SignedData),
          ByteSink(8).u32(minSdk).u32(MAX_SDK).toByteArray(),
          lengthPrefixed(signatureEntry(algId, signer.sign(v3SignedData))),
          lengthPrefixed(spki),
        )
      pair(pairs, V3_BLOCK_ID, lengthPrefixed(v3Signer))
    }

    val pairBytes = pairs.toByteArray()
    val blockSize = (pairBytes.size + 24).toLong() // pairs + trailing size + magic
    val block = ByteSink(pairBytes.size + 32)
    block.u64(blockSize).bytes(pairBytes).u64(blockSize).bytes(MAGIC)

    val out = ByteSink(bytes.size + block.size + 16)
    out.bytes(bytes, 0, image.centralDirectoryOffset)
    out.bytes(block.toByteArray())
    out.bytes(bytes, image.centralDirectoryOffset, image.eocdOffset - image.centralDirectoryOffset)
    val eocdStart = out.size
    out.bytes(bytes, image.eocdOffset, bytes.size - image.eocdOffset)
    // The end-of-central-directory record's "offset of central directory" moves past the block.
    out.putU32(eocdStart + 16, image.centralDirectoryOffset + block.size)
    return out.toByteArray()
  }

  /**
   * The digest the signature covers: each of entries / central directory / EOCD is cut into 1 MiB
   * chunks, each chunk hashed with a `0xa5` prefix and its length, then the chunk hashes hashed
   * with a `0x5a` prefix and the chunk count. The EOCD is taken as it is before signing, whose
   * central-directory offset is where the signing block will start.
   */
  fun contentDigest(bytes: ByteArray, image: ZipImage): ByteArray {
    val sections =
      listOf(
        0 to image.centralDirectoryOffset,
        image.centralDirectoryOffset to image.eocdOffset,
        image.eocdOffset to bytes.size,
      )
    val chunkDigests = ByteSink(256)
    var count = 0
    for ((start, end) in sections) {
      var p = start
      while (p < end) {
        val n = minOf(CHUNK, end - p)
        chunkDigests.bytes(
          Sha256()
            .update(byteArrayOf(0xa5.toByte()))
            .update(ByteSink(4).u32(n).toByteArray())
            .update(bytes, p, n)
            .finish(),
        )
        count++
        p += n
      }
    }
    return Sha256()
      .update(byteArrayOf(0x5a))
      .update(ByteSink(4).u32(count).toByteArray())
      .update(chunkDigests.toByteArray())
      .finish()
  }

  private fun digestsEntry(algId: Int, digest: ByteArray): ByteArray {
    // The digest algorithm is SHA-256 for both supported signature algorithms; its id equals the
    // signature algorithm id for the entries that name it, as apksig writes them.
    return lengthPrefixed(ByteSink(8).u32(algId).toByteArray() + lengthPrefixed(digest))
  }

  private fun certificatesEntries(signer: ApkSigner): ByteArray =
    concat(*signer.certificates.map { lengthPrefixed(it) }.toTypedArray())

  private fun signatureEntry(algId: Int, signature: ByteArray): ByteArray =
    lengthPrefixed(ByteSink(8).u32(algId).toByteArray() + lengthPrefixed(signature))

  private fun attribute(id: Int, value: ByteArray): ByteArray =
    lengthPrefixed(ByteSink(4).u32(id).toByteArray() + value)

  private fun pair(out: ByteSink, id: Int, value: ByteArray) {
    out.u64((4 + value.size).toLong()).u32(id).bytes(value)
  }

  private fun lengthPrefixed(vararg parts: ByteArray): ByteArray {
    val body = concat(*parts)
    return ByteSink(body.size + 4).u32(body.size).bytes(body).toByteArray()
  }

  private operator fun ByteArray.plus(other: ByteArray): ByteArray = concat(this, other)
}
