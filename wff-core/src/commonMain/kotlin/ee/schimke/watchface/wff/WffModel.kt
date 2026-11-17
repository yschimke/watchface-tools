package ee.schimke.watchface.wff

/** A single XML attribute. Attribute order within an element is significant and preserved. */
public data class WffAttribute(val name: String, val value: String)

/** Any node that can appear inside a [WffElement]. */
public sealed interface WffNode

/**
 * A generic, lossless XML element: name, ordered attributes and ordered children. The typed schema
 * ([WffSchema]) is a view over this tree, so elements the schema does not know survive a round trip
 * untouched.
 */
public data class WffElement(
  val name: String,
  val attributes: List<WffAttribute> = emptyList(),
  val children: List<WffNode> = emptyList(),
) : WffNode {
  /** Value of attribute [name], or null. */
  public fun attr(name: String): String? = attributes.firstOrNull { it.name == name }?.value

  public val childElements: List<WffElement>
    get() = children.filterIsInstance<WffElement>()

  public fun childElements(name: String): List<WffElement> =
    children.filterIsInstance<WffElement>().filter { it.name == name }

  public fun firstChild(name: String): WffElement? =
    children.firstOrNull { it is WffElement && it.name == name } as WffElement?

  /** Concatenated text and CDATA content of direct children. */
  public fun textContent(): String = buildString {
    for (c in children) {
      when (c) {
        is WffText -> append(c.text)
        is WffCData -> append(c.text)
        else -> {}
      }
    }
  }

  /** Sets (or appends) an attribute, keeping the position of an existing one. */
  public fun withAttribute(name: String, value: String): WffElement {
    val i = attributes.indexOfFirst { it.name == name }
    val next =
      if (i < 0) attributes + WffAttribute(name, value)
      else attributes.toMutableList().also { it[i] = WffAttribute(name, value) }
    return copy(attributes = next)
  }

  public fun withoutAttribute(name: String): WffElement =
    copy(attributes = attributes.filterNot { it.name == name })

  public fun withChildren(children: List<WffNode>): WffElement = copy(children = children)

  public fun addChild(child: WffNode): WffElement = copy(children = children + child)
}

/** Character data. Stored unescaped. */
public data class WffText(val text: String) : WffNode

/** `<!-- text -->`; [text] is the raw content between the delimiters. */
public data class WffComment(val text: String) : WffNode

/** `<![CDATA[ text ]]>`; [text] is the raw content. */
public data class WffCData(val text: String) : WffNode

/**
 * A parsed document: the root element plus comments before and after it (watch faces usually carry
 * a licence header there). The XML declaration is not stored; the writer always emits `<?xml
 * version="1.0" encoding="utf-8"?>`.
 */
public data class WffDocument(
  val root: WffElement,
  val prolog: List<WffComment> = emptyList(),
  val epilog: List<WffComment> = emptyList(),
) {
  public fun withRoot(root: WffElement): WffDocument = copy(root = root)
}

/** Thrown for malformed XML. [line] and [column] are 1-based. */
public class WffParseException(message: String, public val line: Int, public val column: Int) :
  IllegalArgumentException("$message (line $line, column $column)")
