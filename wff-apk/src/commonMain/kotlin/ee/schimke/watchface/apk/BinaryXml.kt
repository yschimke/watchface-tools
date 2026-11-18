package ee.schimke.watchface.apk

import ee.schimke.watchface.packager.PackagingException
import ee.schimke.watchface.wff.WffCData
import ee.schimke.watchface.wff.WffComment
import ee.schimke.watchface.wff.WffDocument
import ee.schimke.watchface.wff.WffElement
import ee.schimke.watchface.wff.WffText

/**
 * Compiles an XML document to Android binary XML (`RES_XML_TYPE`), the format the platform reads
 * `AndroidManifest.xml` and the XML under `res/xml` in. What `aapt2 compile` + `link` do for those
 * files, for the attribute set a watch face uses (see [AndroidAttributes]).
 *
 * Attributes that have a framework resource id come first, sorted by id (the platform looks them up
 * that way), and their names are the first strings of the pool, in that order, so the resource map
 * chunk can be a plain list of ids.
 */
internal object BinaryXml {
  private const val TYPE_NULL = 0x00
  private const val TYPE_REFERENCE = 0x01
  private const val TYPE_STRING = 0x03
  private const val TYPE_INT_DEC = 0x10
  private const val TYPE_INT_HEX = 0x11
  private const val TYPE_INT_BOOLEAN = 0x12
  private const val TYPE_FLOAT = 0x04
  private const val TYPE_INT_COLOR_ARGB8 = 0x1c
  private const val TYPE_INT_COLOR_RGB8 = 0x1d
  private const val TYPE_INT_COLOR_ARGB4 = 0x1e
  private const val TYPE_INT_COLOR_RGB4 = 0x1f
  private const val NONE = -1

  private class Attr(
    val uri: String?,
    val name: String,
    val id: Int,
    val value: String,
    val kind: AttrKind,
  )

  private class Node(
    val element: WffElement,
    val declarations: List<Pair<String, String>>,
    val uri: String?,
    val name: String,
    val attrs: List<Attr>,
    val children: List<Any>, // Node, or String text
  )

  /**
   * @param resolve `(type, name)` to a resource id for `@type/name` values, or null.
   * @param utf8 the pool encoding: aapt2 writes the manifest in UTF-16 and `res/xml` in UTF-8.
   */
  fun compile(
    document: WffDocument,
    source: String,
    utf8: Boolean,
    resolve: (type: String, name: String) -> Int?,
  ): ByteArray {
    fun bad(msg: String): Nothing = throw PackagingException("axml", "$source: $msg")

    fun build(e: WffElement, scope: Map<String, String>): Node {
      val decls = mutableListOf<Pair<String, String>>()
      val inner = scope.toMutableMap()
      for (a in e.attributes) {
        when {
          a.name == "xmlns" -> {
            decls += "" to a.value
            inner[""] = a.value
          }
          a.name.startsWith("xmlns:") -> {
            decls += a.name.removePrefix("xmlns:") to a.value
            inner[a.name.removePrefix("xmlns:")] = a.value
          }
        }
      }
      fun split(qname: String, forAttr: Boolean): Pair<String?, String> {
        val i = qname.indexOf(':')
        if (i < 0) return (if (forAttr) null else inner[""]) to qname
        val prefix = qname.substring(0, i)
        val uri = inner[prefix] ?: bad("undeclared namespace prefix '$prefix' in '$qname'")
        return uri to qname.substring(i + 1)
      }
      val (uri, local) = split(e.name, false)
      val attrs =
        e.attributes
          .filterNot { it.name == "xmlns" || it.name.startsWith("xmlns:") }
          .map { a ->
            val (auri, alocal) = split(a.name, true)
            if (auri == AndroidAttributes.NAMESPACE) {
              val known =
                AndroidAttributes.find(alocal)
                  ?: bad("attribute android:$alocal on <${e.name}> is not supported")
              Attr(auri, alocal, known.id, a.value, known.kind)
            } else {
              Attr(auri, alocal, 0, a.value, AttrKind.ANY)
            }
          }
      val kids =
        e.children.mapNotNull { c ->
          when (c) {
            is WffElement -> build(c, inner)
            is WffText -> c.text.takeIf { it.isNotBlank() }
            is WffCData -> c.text.takeIf { it.isNotBlank() }
            is WffComment -> null
          }
        }
      return Node(e, decls, uri, local, attrs, kids)
    }

    val root = build(document.root, emptyMap())

    // Pool order: id-bearing attribute names by id first, then everything in document order.
    val named = HashMap<Int, String>()
    fun collect(n: Node) {
      for (a in n.attrs) if (a.id != 0) named[a.id] = a.name
      n.children.filterIsInstance<Node>().forEach(::collect)
    }
    collect(root)
    val pool = StringPoolBuilder()
    val namedIds = named.keys.sorted()
    for (id in namedIds) pool.intern(named.getValue(id), id)

    val body = ByteSink(512)
    var line = 1

    fun typed(a: Attr, owner: String): Triple<Int, Int, Int> {
      // returns (rawStringIndex or NONE, dataType, data)
      val text = a.value
      fun str(): Triple<Int, Int, Int> {
        val i = pool.intern(text)
        return Triple(i, TYPE_STRING, i)
      }
      if (text.startsWith("@") && a.kind != AttrKind.STRING) {
        if (text == "@null") return Triple(NONE, TYPE_REFERENCE, 0)
        val m =
          Regex("@([a-z]+)/([A-Za-z_][A-Za-z0-9_.]*)").matchEntire(text)
            ?: bad("$owner: unsupported reference '$text'")
        val id =
          resolve(m.groupValues[1], m.groupValues[2])
            ?: bad("$owner: reference '$text' does not match any resource in the bundle")
        return Triple(NONE, TYPE_REFERENCE, id)
      }
      // aapt2 reads numbers and booleans with surrounding whitespace ignored, text exactly.
      val t = text.trim()
      fun int(): Triple<Int, Int, Int>? {
        val dec = t.toIntOrNull()
        if (dec != null && !t.startsWith("+")) return Triple(NONE, TYPE_INT_DEC, dec)
        if (t.startsWith("0x") || t.startsWith("0X")) {
          t.drop(2)
            .toLongOrNull(16)
            ?.takeIf { it <= 0xffffffffL }
            ?.let {
              return Triple(NONE, TYPE_INT_HEX, it.toInt())
            }
        }
        return null
      }
      fun bool(): Triple<Int, Int, Int>? =
        when (t) {
          "true",
          "TRUE",
          "True" -> Triple(NONE, TYPE_INT_BOOLEAN, -1)
          "false",
          "FALSE",
          "False" -> Triple(NONE, TYPE_INT_BOOLEAN, 0)
          else -> null
        }
      fun float(): Triple<Int, Int, Int>? {
        if (!FLOAT.matches(t)) return null
        val f = t.toFloatOrNull() ?: return null
        return Triple(NONE, TYPE_FLOAT, f.toRawBits())
      }
      if (COMPLEX.matches(t) && a.kind == AttrKind.ANY) {
        bad("$owner: '$text' is a dimension or fraction, which this packager does not encode")
      }
      return when (a.kind) {
        AttrKind.BOOL -> bool() ?: bad("$owner: '$text' is not a boolean")
        AttrKind.INT -> int() ?: bad("$owner: '$text' is not an integer")
        AttrKind.INT_OR_STRING -> int() ?: str()
        AttrKind.ANY -> bool() ?: int() ?: float() ?: color(t) ?: str()
        AttrKind.REF -> bad("$owner: '$text' is not a resource reference")
        AttrKind.STRING,
        AttrKind.STRING_OR_REF -> str()
      }
    }

    fun emit(n: Node) {
      for ((prefix, uri) in n.declarations) {
        body.chunk(0x0100, 16) {
          u32(line++).u32(NONE)
          u32(pool.intern(prefix)).u32(pool.intern(uri))
        }
      }
      val ordered =
        n.attrs
          .withIndex()
          .sortedWith(
            compareBy<IndexedValue<Attr>>(
              { if (it.value.id == 0) 1 else 0 },
              { it.value.id },
              { it.index },
            ),
          )
          .map { it.value }
      val nsIdx = n.uri?.let { pool.intern(it) } ?: NONE
      val nameIdx = pool.intern(n.name)
      val attrWords = ordered.map { a ->
        val (raw, type, data) = typed(a, "<${n.element.name} ${a.name}>")
        intArrayOf(
          a.uri?.let { pool.intern(it) } ?: NONE,
          if (a.id != 0) pool.intern(a.name, a.id) else pool.intern(a.name),
          raw,
          (type shl 24) or 8,
          data,
        )
      }
      body.chunk(0x0102, 16) {
        u32(line++).u32(NONE)
        u32(nsIdx).u32(nameIdx)
        u16(0x14).u16(0x14).u16(attrWords.size).u16(0).u16(0).u16(0)
        for (w in attrWords) {
          u32(w[0]).u32(w[1]).u32(w[2])
          // Res_value: size (8), res0 (0), dataType, data.
          u16(8).u8(0).u8(w[3] ushr 24).u32(w[4])
        }
      }
      for (c in n.children) {
        when (c) {
          is Node -> emit(c)
          is String ->
            body.chunk(0x0104, 16) {
              u32(line++).u32(NONE)
              u32(pool.intern(c))
              u16(8).u8(0).u8(TYPE_NULL).u32(0)
            }
        }
      }
      body.chunk(0x0103, 16) {
        u32(line++).u32(NONE)
        u32(nsIdx).u32(nameIdx)
      }
      for ((prefix, uri) in n.declarations.reversed()) {
        body.chunk(0x0101, 16) {
          u32(line++).u32(NONE)
          u32(pool.intern(prefix)).u32(pool.intern(uri))
        }
      }
    }
    emit(root)

    val out = ByteSink(body.size + 512)
    out.chunk(0x0003, 8) {
      bytes(pool.build(utf8))
      chunk(0x0180, 8) { for (id in namedIds) u32(id) }
      bytes(body.toByteArray())
    }
    return out.toByteArray()
  }

  private val FLOAT = Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)([eE][+-]?[0-9]+)?")
  private val COMPLEX = Regex("[+-]?([0-9]+\\.?[0-9]*|\\.[0-9]+)(px|dip|dp|sp|pt|in|mm|%|%p)")

  /** `#rgb`, `#argb`, `#rrggbb`, `#aarrggbb`, typed and expanded the way aapt2 stores them. */
  private fun color(text: String): Triple<Int, Int, Int>? {
    if (!text.startsWith("#") || text.drop(1).any { it.digitToIntOrNull(16) == null }) return null
    val hex = text.drop(1)
    val v = hex.toLongOrNull(16) ?: return null
    fun expand(nibbles: Int): Int {
      var out = 0
      for (i in nibbles - 1 downTo 0) {
        val n = ((v ushr (4 * i)) and 0xf).toInt()
        out = (out shl 8) or (n * 0x11)
      }
      return out
    }
    return when (hex.length) {
      3 -> Triple(NONE, TYPE_INT_COLOR_RGB4, 0xff000000.toInt() or expand(3))
      4 -> Triple(NONE, TYPE_INT_COLOR_ARGB4, expand(4))
      6 -> Triple(NONE, TYPE_INT_COLOR_RGB8, (0xff000000 or v).toInt())
      8 -> Triple(NONE, TYPE_INT_COLOR_ARGB8, v.toInt())
      else -> null
    }
  }
}
