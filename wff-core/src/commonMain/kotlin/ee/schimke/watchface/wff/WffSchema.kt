package ee.schimke.watchface.wff

/**
 * Value kind of an attribute. Informational: numeric and colour attributes also accept expressions
 * (`[CONFIGURATION.x]`, `[HOUR_0_23] * 15`), so the validator does not check values.
 */
public enum class WffAttributeType {
  INT,
  FLOAT,
  COLOR,
  STRING,
  BOOLEAN,
  ENUM,
  /** Always an expression (`Compare.expression`, `Transform.value`, ...). */
  EXPRESSION,
  /** Name of a resource (`res/drawable`, `res/font`, ...) without extension. */
  RESOURCE,
  /** Identifier referenced elsewhere in the document. */
  ID,
}

public data class WffAttributeSpec(
  val name: String,
  val type: WffAttributeType,
  val required: Boolean = false,
  val sinceVersion: WffVersion = WffVersion.V1,
  val enumValues: List<String> = emptyList(),
)

/** A child [id] allowed under some parent, and the format version that first allowed it there. */
public data class WffChildSpec(val id: String, val sinceVersion: WffVersion = WffVersion.V1)

/**
 * One element as allowed in one context. The same XML name can have several specs (for example
 * `ListConfiguration` under `UserConfigurations` declares options, while under `Scene` it selects
 * layout), so specs have a unique [id] and parents list their children by id.
 */
public data class WffElementSpec(
  val id: String,
  val name: String,
  val sinceVersion: WffVersion,
  val attributes: List<WffAttributeSpec>,
  val children: List<WffChildSpec>,
  val allowsText: Boolean = false,
) {
  val childIds: List<String>
    get() = children.map { it.id }

  public fun attribute(name: String): WffAttributeSpec? = attributes.firstOrNull { it.name == name }
}

/**
 * Table of Watch Face Format elements, their attributes and children, with the format version that
 * introduced each. Generated from Google's XSDs (github.com/google/watchface,
 * `third_party/wff/specification/documents/<version>`) by `tools/wff-xsd/generate_schema.py`; see
 * [GENERATED_SCHEMA]. Abstract XSD elements (`DrawElement`, `BoundingShape`, ...) are expanded to
 * the members of their substitution groups.
 */
public object WffSchema {
  public const val ROOT_ID: String = "WatchFace"

  public val elements: Map<String, WffElementSpec> by lazy {
    GENERATED_SCHEMA.associateBy { it.id }
  }

  public val root: WffElementSpec
    get() = elements.getValue(ROOT_ID)

  /** Every spec whose XML name is [name], across contexts. */
  public fun named(name: String): List<WffElementSpec> = elements.values.filter { it.name == name }

  public fun isKnownName(name: String): Boolean = elements.values.any { it.name == name }

  /** The spec for child [name] of [parent], or null when [parent] does not allow it. */
  public fun child(parent: WffElementSpec, name: String): WffElementSpec? =
    childEdge(parent, name)?.let { elements.getValue(it.id) }

  /**
   * The version from which child [name] may appear under [parent]: the later of when the child
   * element exists and when [parent] started allowing it. Null when [parent] does not allow it.
   */
  public fun childSince(parent: WffElementSpec, name: String): WffVersion? {
    val edge = childEdge(parent, name) ?: return null
    return maxOf(edge.sinceVersion, elements.getValue(edge.id).sinceVersion)
  }

  private fun childEdge(parent: WffElementSpec, name: String): WffChildSpec? =
    parent.children.firstOrNull { elements.getValue(it.id).name == name }
}
