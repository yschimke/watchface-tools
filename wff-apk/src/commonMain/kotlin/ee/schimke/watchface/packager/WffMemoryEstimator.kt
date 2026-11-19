package ee.schimke.watchface.packager

import ee.schimke.watchface.wff.WffBundleLayout
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffElement
import ee.schimke.watchface.wff.WffXmlParser

/** Width, height and colour model read from an image file's header, without decoding it. */
public data class ImageHeader(
  val format: String,
  val width: Int,
  val height: Int,
  /** False for a greyscale PNG or JPEG; null when the header does not say (WebP). */
  val colour: Boolean?,
)

/** What [WffMemoryEstimator.estimate] measured. */
public class MemoryEstimate(
  /** Most expensive first ([MemoryContributor.cost], then name). */
  public val contributors: List<MemoryContributor>,
  public val memory: ReportMemory,
  /**
   * Missing resources and limit overruns (errors), and every contributor's suggestion (warnings).
   */
  public val findings: List<ReportFinding>,
)

/**
 * A cheap, pure-Kotlin memory estimate of a WFF source bundle: no aapt2, no SDK, no image decoder,
 * so it can run on every edit, in the browser too. It reads each image's size from its header and
 * charges it `width * height * 4` bytes (decoded ARGB) to active mode when the face draws it, and
 * to ambient mode unless the element or an ancestor hides itself in ambient (`<Variant
 * mode="AMBIENT" target="alpha" value="0"/>`). Fonts cost their file size.
 *
 * It approximates Google's memory-footprint evaluator, which stays the authority (it accounts for
 * layers, clipping and the ambient offload rules this does not), but it names every resource, so an
 * author can see what to make cheaper.
 */
public object WffMemoryEstimator {
  /** The limits `wff-packager` evaluates with (Google's suggested Play settings). */
  public const val ACTIVE_LIMIT_BYTES: Long = 100L * 1024 * 1024
  public const val AMBIENT_LIMIT_BYTES: Long = 10L * 1024 * 1024

  /** Images the face draws (`thumbnail`: an `AnimatedImage` shown while it is not playing). */
  private val IMAGE_ATTRIBUTES = setOf("resource", "defaultImageResource", "thumbnail")
  /** Editor-only images (configuration icons): checked to exist, not charged. */
  private val ICON_ATTRIBUTES = setOf("icon")
  private const val DOWNSCALE_SLACK = 1.1
  private const val MIN_SAVING = 16L * 1024

  private class Use(
    val element: WffElement,
    val path: String,
    val drawn: Boolean,
    val ambient: Boolean,
    val width: Int?,
    val height: Int?,
    val tinted: Boolean,
    /** The face needs the file (missing is an error): anything but a configuration icon. */
    val required: Boolean = drawn,
  )

  public fun estimate(
    bundle: Map<String, ByteArray>,
    activeLimitBytes: Long = ACTIVE_LIMIT_BYTES,
    ambientLimitBytes: Long = AMBIENT_LIMIT_BYTES,
  ): MemoryEstimate {
    val doc =
      bundle[WffBundleLayout.WATCH_FACE_PATH]?.decodeToString()?.let(WffXmlParser::parseOrNull)
    val drawables = resources(bundle, "drawable")
    val fonts = resources(bundle, "font")
    val imageUses = linkedMapOf<String, MutableList<Use>>()
    val fontUses = linkedMapOf<String, MutableList<Use>>()
    if (doc != null) collect(doc, imageUses, fontUses)

    val findings = mutableListOf<ReportFinding>()
    val contributors = mutableListOf<MemoryContributor>()
    for ((name, uses) in imageUses) {
      val file = drawables[name]
      if (file == null) {
        val suggestion =
          "add res/drawable/$name.png (or .webp)" +
            (closest(name, drawables.keys)?.let { ", or reference '$it' if that is the one meant" }
              ?: "")
        contributors +=
          MemoryContributor(
            "drawable/$name",
            ContributorKind.IMAGE,
            null,
            renderedMaxWidth = uses.mapNotNull { it.width }.maxOrNull(),
            renderedMaxHeight = uses.mapNotNull { it.height }.maxOrNull(),
            missing = true,
            suggestion = suggestion,
          )
        // Google's validators only reject a missing image the face draws; a missing
        // configuration icon just shows nothing in the editor.
        val drawn = uses.firstOrNull { it.required }
        findings +=
          if (drawn != null) {
            ReportFinding(
              FindingSeverity.ERROR,
              "Resource '$name' is referenced but not in the bundle (the memory evaluator and the " +
                "push validator reject this: \"Asset $name was not found in the watch face " +
                "package\")",
              path = drawn.path,
              suggestion = suggestion,
            )
          } else {
            ReportFinding(
              FindingSeverity.WARNING,
              "Configuration icon '$name' is not in the bundle: the editor shows no icon",
              path = uses.first().path,
              suggestion = suggestion,
            )
          }
        continue
      }
      contributors += image("drawable/$name", file, uses)
    }
    for ((name, uses) in fontUses) {
      val file = fonts[name] ?: continue // system fonts (SYNC_TO_DEVICE, ...) are not in the bundle
      val size = file.bytes.size.toLong()
      contributors +=
        MemoryContributor(
          "font/$name",
          ContributorKind.FONT,
          file.extension.lowercase(),
          fileBytes = size,
          decodedBytes = size,
          activeBytes = if (uses.any { it.drawn }) size else 0,
          ambientBytes = if (uses.any { it.ambient }) size else 0,
        )
    }
    // Shipped but never referenced: costs download size, not memory.
    // Referenced elsewhere (the manifest's icon, the preview, another XML resource): keep it.
    val elsewhere = drawableReferences(bundle) + "preview"
    for ((name, file) in drawables) {
      if (name in imageUses || name in elsewhere) continue
      val header = imageHeader(file.bytes)
      contributors +=
        MemoryContributor(
          "drawable/$name",
          ContributorKind.IMAGE,
          header?.format ?: file.extension.lowercase(),
          header?.width,
          header?.height,
          fileBytes = file.bytes.size.toLong(),
          decodedBytes = header?.let { it.width.toLong() * it.height * 4 } ?: 0,
          suggestion = "not referenced by watchface.xml: remove it from the bundle",
        )
    }

    val sorted =
      contributors.sortedWith(
        compareByDescending<MemoryContributor> { it.cost }.thenBy { it.resource },
      )
    val memory =
      ReportMemory(
        totalBytes =
          sorted.sumOf { if (it.activeBytes > 0 || it.ambientBytes > 0) it.decodedBytes else 0 },
        maxActiveBytes = sorted.sumOf { it.activeBytes },
        maxAmbientBytes = sorted.sumOf { it.ambientBytes },
        activeLimitBytes = activeLimitBytes,
        ambientLimitBytes = ambientLimitBytes,
      )
    if (memory.maxActiveBytes > activeLimitBytes) {
      findings +=
        overLimit("Active", memory.maxActiveBytes, activeLimitBytes, sorted) { it.activeBytes }
    }
    if (memory.maxAmbientBytes > ambientLimitBytes) {
      findings +=
        overLimit("Ambient", memory.maxAmbientBytes, ambientLimitBytes, sorted) { it.ambientBytes }
    }
    for (c in sorted) {
      if (c.missing || c.suggestion == null) continue
      findings +=
        ReportFinding(
          FindingSeverity.WARNING,
          "${c.resource}: ${c.suggestion}",
          suggestion = c.suggestion,
        )
    }
    return MemoryEstimate(sorted, memory, findings)
  }

  private fun image(resource: String, file: ResourceFile, uses: List<Use>): MemoryContributor {
    val header = imageHeader(file.bytes)
    val decoded = header?.let { it.width.toLong() * it.height * 4 } ?: file.bytes.size.toLong()
    val drawn = uses.any { it.drawn }
    val ambient = uses.any { it.ambient }
    val rw = uses.mapNotNull { it.width }.maxOrNull()
    val rh = uses.mapNotNull { it.height }.maxOrNull()
    val suggestions = mutableListOf<String>()
    if (header == null) {
      suggestions += "the image header could not be read: use PNG, JPEG or WebP"
    } else {
      if (rw != null && rh != null && rw > 0 && rh > 0) {
        val saving = decoded - rw.toLong() * rh * 4
        if (
          header.width > rw * DOWNSCALE_SLACK &&
            header.height > rh * DOWNSCALE_SLACK &&
            saving >= MIN_SAVING
        ) {
          suggestions +=
            "downscale from ${header.width}x${header.height} to the ${rw}x$rh it is drawn at " +
              "(saves ${humanBytes(saving)} per mode that draws it)"
        }
      }
      if (ambient && header.colour == true && uses.none { it.ambient && it.tinted }) {
        suggestions +=
          "ambient draws this full-colour image: hide it in ambient " +
            "(<Variant mode=\"AMBIENT\" target=\"alpha\" value=\"0\"/>) or draw a greyscale variant"
      }
    }
    return MemoryContributor(
      resource,
      ContributorKind.IMAGE,
      header?.format ?: "unknown",
      header?.width,
      header?.height,
      fileBytes = file.bytes.size.toLong(),
      decodedBytes = decoded,
      activeBytes = if (drawn) decoded else 0,
      ambientBytes = if (ambient) decoded else 0,
      renderedMaxWidth = rw,
      renderedMaxHeight = rh,
      suggestion = suggestions.takeIf { it.isNotEmpty() }?.joinToString("; "),
    )
  }

  private fun overLimit(
    mode: String,
    bytes: Long,
    limit: Long,
    sorted: List<MemoryContributor>,
    cost: (MemoryContributor) -> Long,
  ): ReportFinding {
    val top = sorted.filter { cost(it) > 0 }.sortedByDescending(cost).take(3)
    return ReportFinding(
      FindingSeverity.ERROR,
      "$mode footprint ${humanBytes(bytes)} exceeds the ${humanBytes(limit)} limit (estimate)",
      suggestion =
        "shrink the largest: " + top.joinToString { "${it.resource} (${humanBytes(cost(it))})" },
    )
  }

  /** Walks the face: image references with their drawn size and ambient visibility; font uses. */
  private fun collect(
    doc: WffDocument,
    images: MutableMap<String, MutableList<Use>>,
    fonts: MutableMap<String, MutableList<Use>>,
  ) {
    // Bitmap font glyphs are drawn wherever text uses that font: record those uses first.
    val bitmapFontUses = mutableMapOf<String, MutableList<Use>>()

    fun walk(e: WffElement, path: String, parent: Walk, visit: (WffElement, String, Walk) -> Unit) {
      val w = e.attr("width")?.toFloatOrNull()?.toInt()
      val h = e.attr("height")?.toFloatOrNull()?.toInt()
      val here =
        Walk(
          hidden = parent.hidden || hidesInAmbient(e),
          config = parent.config || e.name == "UserConfigurations",
          glyphs = parent.glyphs || e.name == "BitmapFonts",
          // A `tintColor` on a container (`PartImage`) tints the `Image` inside it.
          tinted = parent.tinted || e.attr("tintColor") != null,
          width = if (w != null && h != null) w else parent.width,
          height = if (w != null && h != null) h else parent.height,
        )
      visit(e, path, here)
      val counts = e.childElements.groupingBy { it.name }.eachCount()
      val seen = mutableMapOf<String, Int>()
      for (c in e.childElements) {
        val n = (seen[c.name] ?: 0) + 1
        seen[c.name] = n
        val childPath = path + "/" + c.name + if ((counts[c.name] ?: 0) > 1) "[$n]" else ""
        walk(c, childPath, here, visit)
      }
    }

    val root = doc.root
    val start = Walk()
    // Pass 1: where each font family is used.
    walk(root, "/${root.name}", start) { e, path, at ->
      if (at.config || at.glyphs) return@walk
      val family = e.attr("family") ?: return@walk
      if (e.name != "Font" && e.name != "BitmapFont") return@walk
      val use = Use(e, path, drawn = true, ambient = !at.hidden, at.width, at.height, false)
      if (e.name == "BitmapFont") bitmapFontUses.getOrPut(family) { mutableListOf() } += use
      else fonts.getOrPut(family) { mutableListOf() } += use
    }
    // Pass 2: image references.
    walk(root, "/${root.name}", start) { e, path, at ->
      for (a in e.attributes) {
        val value = a.value.removePrefix("@drawable/")
        if (value.isEmpty() || value.startsWith("[")) continue
        val icon = a.name in ICON_ATTRIBUTES
        if (a.name !in IMAGE_ATTRIBUTES && !icon) continue
        val use =
          when {
            icon || at.config -> Use(e, path, false, false, null, null, false)
            at.glyphs -> {
              // A glyph is drawn only where text uses its font: an unused font costs nothing.
              val users = bitmapFontName(root, e)?.let { bitmapFontUses[it] }.orEmpty()
              Use(
                e,
                path,
                drawn = users.isNotEmpty(),
                ambient = users.any { it.ambient },
                at.width,
                at.height,
                tinted = false,
                required = true,
              )
            }
            else -> Use(e, path, true, !at.hidden, at.width, at.height, at.tinted)
          }
        images.getOrPut(value) { mutableListOf() } += use
      }
    }
  }

  /** What [collect] inherits from the ancestors of an element. */
  private data class Walk(
    val hidden: Boolean = false,
    val config: Boolean = false,
    val glyphs: Boolean = false,
    val tinted: Boolean = false,
    val width: Int? = null,
    val height: Int? = null,
  )

  private fun hidesInAmbient(e: WffElement): Boolean =
    e.childElements("Variant").any {
      it.attr("mode") == "AMBIENT" &&
        it.attr("target") == "alpha" &&
        it.attr("value")?.trim()?.toFloatOrNull() == 0f
    }

  /** The `name` of the `<BitmapFont>` under `<BitmapFonts>` that contains [glyph]. */
  private fun bitmapFontName(root: WffElement, glyph: WffElement): String? {
    fun find(e: WffElement, font: String?): String? {
      val here = if (e.name == "BitmapFont") e.attr("name") ?: font else font
      if (e === glyph) return here
      for (c in e.childElements) find(c, here)?.let {
        return it
      }
      return null
    }
    return find(root, null)
  }

  private class ResourceFile(val bytes: ByteArray, val extension: String)

  /** `res/<type>[-qualifiers]/<name>.<ext>` by name; the unqualified directory wins. */
  private fun resources(bundle: Map<String, ByteArray>, type: String): Map<String, ResourceFile> {
    val out = linkedMapOf<String, ResourceFile>()
    val entries =
      bundle.entries
        .filter { (path, _) ->
          val dir = path.removePrefix("res/").substringBefore('/', "")
          path.startsWith("res/") &&
            (dir == type || dir.startsWith("$type-")) &&
            path.count { it == '/' } == 2
        }
        .sortedBy { (path, _) -> if (path.startsWith("res/$type/")) 0 else 1 }
    for ((path, bytes) in entries) {
      val file = path.substringAfterLast('/')
      val name = file.substringBefore('.')
      if (name !in out) out[name] = ResourceFile(bytes, file.substringAfter('.', ""))
    }
    return out
  }

  private val DRAWABLE_REFERENCE = Regex("""@drawable/([A-Za-z0-9_.]+)""")

  /** `@drawable/<name>` references in any bundled XML file other than the watch face itself. */
  private fun drawableReferences(bundle: Map<String, ByteArray>): Set<String> =
    bundle
      .filterKeys { it.endsWith(".xml") && it != WffBundleLayout.WATCH_FACE_PATH }
      .values
      .flatMap { bytes ->
        DRAWABLE_REFERENCE.findAll(bytes.decodeToString()).map { it.groupValues[1] }
      }
      .toSet()

  /** The bundle name nearest to [name] by edit distance, when it is close enough to be a typo. */
  private fun closest(name: String, candidates: Collection<String>): String? =
    candidates
      .map { it to editDistance(name, it) }
      .filter { (c, d) -> d <= maxOf(2, minOf(name.length, c.length) / 3) }
      .minByOrNull { it.second }
      ?.first

  private fun editDistance(a: String, b: String): Int {
    var prev = IntArray(b.length + 1) { it }
    for (i in 1..a.length) {
      val cur = IntArray(b.length + 1)
      cur[0] = i
      for (j in 1..b.length) {
        cur[j] =
          minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
      }
      prev = cur
    }
    return prev[b.length]
  }

  /** Reads PNG, JPEG and WebP headers; null for anything else (or a truncated file). */
  public fun imageHeader(b: ByteArray): ImageHeader? {
    fun u8(i: Int) = b[i].toInt() and 0xff
    fun be16(i: Int) = (u8(i) shl 8) or u8(i + 1)
    fun be32(i: Int) = (u8(i) shl 24) or (u8(i + 1) shl 16) or (u8(i + 2) shl 8) or u8(i + 3)
    fun le16(i: Int) = u8(i) or (u8(i + 1) shl 8)
    fun le24(i: Int) = u8(i) or (u8(i + 1) shl 8) or (u8(i + 2) shl 16)
    fun ascii(i: Int, n: Int) = (i until i + n).map { u8(it).toChar() }.joinToString("")
    try {
      if (b.size >= 26 && u8(0) == 0x89 && ascii(1, 3) == "PNG" && ascii(12, 4) == "IHDR") {
        // Colour types 0 and 4 are greyscale (with alpha); 2, 3, 6 are colour.
        val colourType = u8(25)
        return ImageHeader("png", be32(16), be32(20), colourType != 0 && colourType != 4)
      }
      if (b.size >= 4 && u8(0) == 0xff && u8(1) == 0xd8) {
        var i = 2
        while (i + 9 < b.size) {
          if (u8(i) != 0xff) return null
          val marker = u8(i + 1)
          if (marker == 0xff) {
            i++
            continue
          }
          val len = be16(i + 2)
          val sof = marker in 0xc0..0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
          if (sof) return ImageHeader("jpeg", be16(i + 7), be16(i + 5), u8(i + 9) != 1)
          i += 2 + len
        }
        return null
      }
      if (b.size >= 30 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP") {
        return when (ascii(12, 4)) {
          "VP8 " -> ImageHeader("webp", le16(26) and 0x3fff, le16(28) and 0x3fff, null)
          "VP8L" -> {
            val bits = u8(21) or (u8(22) shl 8) or (u8(23) shl 16) or (u8(24) shl 24)
            ImageHeader("webp", (bits and 0x3fff) + 1, ((bits shr 14) and 0x3fff) + 1, null)
          }
          "VP8X" -> ImageHeader("webp", le24(24) + 1, le24(27) + 1, null)
          else -> null
        }
      }
    } catch (_: IndexOutOfBoundsException) {
      return null
    }
    return null
  }

  internal fun humanBytes(n: Long): String {
    if (n < 1024) return "$n B"
    val units = listOf("KB", "MB", "GB")
    var v = n / 1024.0
    var u = 0
    while (v >= 1024 && u < units.lastIndex) {
      v /= 1024
      u++
    }
    val tenths = (v * 10 + 0.5).toLong()
    return "${tenths / 10}.${tenths % 10} ${units[u]}"
  }
}
