package ee.schimke.watchface.apk

import ee.schimke.watchface.packager.PackagingException
import ee.schimke.watchface.wff.WffXmlParser

/** The value a resource has in one configuration. */
internal sealed interface ResourceValue {
  /** A file in the APK, referenced by its path (`res/drawable/preview.png`). */
  class File(val path: String) : ResourceValue

  /** A `<string>` value. */
  class Text(val text: String) : ResourceValue

  /** A bare `@type/name` alias, stored as a reference rather than as text. */
  class Ref(val type: String, val name: String) : ResourceValue
}

/**
 * The `res/` side of a bundle as a resource table: which resource ids exist, and the
 * `resources.arsc` that declares them. The ids follow aapt2's assignment (package 0x7f, types in
 * its fixed order, entries sorted by name), so a face's `@drawable/preview` resolves to the same id
 * either packager gives it.
 */
internal class ResourceTable
private constructor(
  val packageName: String,
  /** type -> name -> configuration -> value. */
  private val entries: Map<String, Map<String, Map<String, Pair<ResourceConfig, ResourceValue>>>>,
) {
  private val typeOrder: List<String> =
    TYPE_ORDER.filter { it in entries }.also { check(entries.keys.all { k -> k in TYPE_ORDER }) }

  /** `0x7fTTEEEE` for `@type/name`, or null. */
  fun idOf(type: String, name: String): Int? {
    val t = typeOrder.indexOf(type)
    if (t < 0) return null
    val e = entries.getValue(type).keys.sorted().indexOf(name)
    if (e < 0) return null
    return (0x7f shl 24) or ((t + 1) shl 16) or e
  }

  /** Every `res/...` file path the table points at (these must be in the APK). */
  fun filePaths(): List<String> =
    entries.values
      .flatMap { it.values }
      .flatMap { it.values }
      .mapNotNull { (it.second as? ResourceValue.File)?.path }
      .distinct()
      .sorted()

  fun toArsc(): ByteArray {
    // Global value pool: every file path and string value, sorted.
    val values = HashSet<String>()
    for (byName in entries.values) for (byConfig in byName.values) for ((_, v) in byConfig.values) {
      when (v) {
        is ResourceValue.File -> values += v.path
        is ResourceValue.Text -> values += v.text
        is ResourceValue.Ref -> {}
      }
    }
    val valueList = values.sorted()
    val keyNames = entries.values.flatMap { it.keys }.distinct().sorted()

    val out = ByteSink(4096)
    out.chunk(0x0002, 12) {
      u32(1) // package count
      bytes(writeStringPool(valueList, utf8 = true))
      val pkgStart = size
      chunk(0x0200, 288) {
        u32(0x7f)
        val name = packageName.take(127)
        for (c in name) u16(c.code)
        zeros(2 * (128 - name.length))
        val typeStringsAt = size
        u32(0).u32(typeOrder.size).u32(0).u32(keyNames.size).u32(0)
        val typePool = writeStringPool(typeOrder, utf8 = false)
        val keyPool = writeStringPool(keyNames, utf8 = true)
        // Offsets are from the start of this package chunk; the pools follow its 288-byte header.
        putU32(typeStringsAt, 288)
        putU32(typeStringsAt + 8, 288 + typePool.size)
        bytes(typePool)
        bytes(keyPool)
        for ((ti, type) in typeOrder.withIndex()) {
          val byName = entries.getValue(type)
          val names = byName.keys.sorted()
          val configs =
            byName.values
              .flatMap { it.values.map { (c, _) -> c } }
              .distinctBy { it.qualifiers }
              .sortedWith(ResourceConfig.order)
          val typeId = ti + 1
          chunk(0x0202, 16) {
            u8(typeId).u8(0).u16(configs.size).u32(names.size)
            for (n in names) {
              var flags = 0
              for ((c, _) in byName.getValue(n).values) flags = flags or c.changeFlags
              u32(flags)
            }
          }
          for (config in configs) {
            chunk(0x0201, 20 + ResourceConfig.SIZE) {
              u8(typeId).u8(0).u16(0).u32(names.size)
              u32(20 + ResourceConfig.SIZE + 4 * names.size)
              bytes(config.bytes)
              val body = ByteSink(names.size * 16)
              for (n in names) {
                val v = byName.getValue(n)[config.qualifiers]?.second
                if (v == null) {
                  u32(-1)
                } else {
                  u32(body.size)
                  body.u16(8).u16(0).u32(keyNames.indexOf(n))
                  when (v) {
                    is ResourceValue.Ref -> {
                      val id =
                        idOf(v.type, v.name)
                          ?: throw PackagingException(
                            "resources",
                            "string/$n: alias target @${v.type}/${v.name} does not exist in the bundle",
                          )
                      body.u16(8).u8(0).u8(TYPE_REFERENCE).u32(id)
                    }
                    else -> {
                      val text = (v as? ResourceValue.File)?.path ?: (v as ResourceValue.Text).text
                      body.u16(8).u8(0).u8(TYPE_STRING).u32(valueList.binarySearch(text))
                    }
                  }
                }
              }
              bytes(body.toByteArray())
            }
          }
        }
      }
    }
    return out.toByteArray()
  }

  companion object {
    /** aapt2's `ResourceType` order, which decides the type ids. */
    private val TYPE_ORDER =
      listOf(
        "anim",
        "animator",
        "array",
        "attr",
        "bool",
        "color",
        "dimen",
        "drawable",
        "font",
        "fraction",
        "id",
        "integer",
        "interpolator",
        "layout",
        "menu",
        "mipmap",
        "navigation",
        "plurals",
        "raw",
        "string",
        "style",
        "transition",
        "xml",
      )

    /** Directory types whose files are packaged by path. */
    private val FILE_TYPES =
      setOf(
        "anim",
        "animator",
        "color",
        "drawable",
        "font",
        "interpolator",
        "layout",
        "menu",
        "mipmap",
        "navigation",
        "raw",
        "transition",
        "xml",
      )

    const val TYPE_STRING = 0x03
    const val TYPE_REFERENCE = 0x01
    private val ALIAS = Regex("@([a-z]+)/([A-Za-z_][A-Za-z0-9_.]*)")
    private val NAME = Regex("[A-Za-z_][A-Za-z0-9_]*")

    /** Reads every `res/...` file of [files]. */
    fun fromBundle(packageName: String, files: Map<String, ByteArray>): ResourceTable {
      val entries =
        LinkedHashMap<
          String,
          LinkedHashMap<String, LinkedHashMap<String, Pair<ResourceConfig, ResourceValue>>>,
        >()

      fun put(
        type: String,
        name: String,
        config: ResourceConfig,
        value: ResourceValue,
        path: String,
      ) {
        val byConfig = entries.getOrPut(type) { LinkedHashMap() }.getOrPut(name) { LinkedHashMap() }
        if (config.qualifiers in byConfig) {
          throw PackagingException("resources", "$path: duplicate resource $type/$name")
        }
        byConfig[config.qualifiers] = config to value
      }

      for ((path, bytes) in files.entries.sortedBy { it.key }) {
        if (!path.startsWith("res/")) continue
        val parts = path.removePrefix("res/").split('/')
        if (parts.size != 2 || parts[1].isEmpty()) {
          throw PackagingException(
            "resources",
            "$path: resources must be res/<type>[-qualifiers]/<file>",
          )
        }
        val dir = parts[0]
        val file = parts[1]
        val type = dir.substringBefore('-')
        val config = ResourceConfig.parse(dir.substringAfter('-', ""), path)
        if (type == "values") {
          for ((name, value) in readStrings(path, bytes)) {
            put("string", name, config, value, path)
          }
          continue
        }
        if (type !in FILE_TYPES) {
          throw PackagingException(
            "resources",
            "$path: unsupported resource type '$type' (supported: values, ${FILE_TYPES.sorted().joinToString()})",
          )
        }
        val stem = file.substringBefore('.')
        if (!NAME.matches(stem)) {
          throw PackagingException("resources", "$path: '$stem' is not a valid resource name")
        }
        if (file.endsWith(".9.png")) {
          throw PackagingException("resources", "$path: nine-patch images are not supported")
        }
        put(type, stem, config, ResourceValue.File(path), path)
      }
      return ResourceTable(packageName, entries)
    }

    /** `<resources><string name="x">text</string></resources>`; any other element is an error. */
    private fun readStrings(path: String, bytes: ByteArray): List<Pair<String, ResourceValue>> {
      val doc =
        WffXmlParser.parseOrNull(bytes.decodeToString())
          ?: throw PackagingException("resources", "$path is not well-formed XML")
      if (doc.root.name != "resources") {
        throw PackagingException(
          "resources",
          "$path: root must be <resources>, found <${doc.root.name}>",
        )
      }
      return doc.root.childElements.map { e ->
        if (e.name != "string") {
          throw PackagingException(
            "resources",
            "$path: <${e.name}> is not supported; only <string> values are packaged",
          )
        }
        if (e.childElements.isNotEmpty()) {
          throw PackagingException("resources", "$path: styled text in <string> is not supported")
        }
        val name =
          e.attr("name") ?: throw PackagingException("resources", "$path: <string> has no name")
        if (!NAME.matches(name)) {
          throw PackagingException("resources", "$path: '$name' is not a valid resource name")
        }
        // An escaped `\@` stays text; only an unescaped bare @type/name is an alias.
        val raw = e.textContent().trim()
        val alias = ALIAS.matchEntire(raw)
        name to
          if (alias != null) ResourceValue.Ref(alias.groupValues[1], alias.groupValues[2])
          else ResourceValue.Text(unescape(e.textContent()))
      }
    }

    /**
     * Android's string-resource text rules: whitespace outside double quotes collapses to one space
     * and the ends are trimmed, and a backslash escapes (`\n`, `\t`, `\'`, `\"`, `\@`, `\?`, `\\`,
     * `\uXXXX`). The inverse of `WffBundleLayout.androidStringEscape`.
     */
    fun unescape(raw: String): String {
      val out = StringBuilder()
      var quoted = false
      var i = 0
      var pendingSpace = false
      fun emit(c: Char) {
        if (pendingSpace && out.isNotEmpty()) out.append(' ')
        pendingSpace = false
        out.append(c)
      }
      while (i < raw.length) {
        val c = raw[i]
        when {
          c == '\\' && i + 1 < raw.length -> {
            val n = raw[++i]
            when (n) {
              'n' -> emit('\n')
              't' -> emit('\t')
              'u' -> {
                val hex = raw.substring(i + 1, minOf(i + 5, raw.length))
                emit(
                  (hex.toIntOrNull(16)
                      ?: throw PackagingException("resources", "bad \\u escape in '$raw'"))
                    .toChar(),
                )
                i += 4
              }
              else -> emit(n)
            }
          }
          c == '"' -> quoted = !quoted
          c.isWhitespace() && !quoted -> pendingSpace = true
          else -> emit(c)
        }
        i++
      }
      return out.toString()
    }
  }
}
