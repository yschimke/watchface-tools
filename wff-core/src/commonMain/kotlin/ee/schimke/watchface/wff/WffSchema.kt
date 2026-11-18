package ee.schimke.watchface.wff

/**
 * Value kind of an attribute: a coarse label for tooling. What a value may actually be is
 * [WffAttributeSpec.rule], generated from the XSD type.
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

/**
 * What an attribute (or text) value may be, as the XSD type says. Facets other than enumeration,
 * pattern and numeric bounds are not modelled. [WffValueChecker] evaluates a rule.
 */
public sealed interface WffValueRule {
  /** One of [values]; a value in [since] needs at least that format version (`"X" to 2`). */
  public data class Enumeration(
    val values: List<String>,
    val since: Map<String, Int> = emptyMap(),
  ) : WffValueRule

  /** The whole value matches [regex] (XSD patterns are anchored). */
  public data class Pattern(val regex: String) : WffValueRule {
    internal val compiled: Regex by lazy { Regex(regex) }
  }

  /** A literal integer or float, optionally bounded. */
  public data class Number(
    val integer: Boolean,
    val min: Double? = null,
    val minExclusive: Boolean = false,
    val max: Double? = null,
    val maxExclusive: Boolean = false,
  ) : WffValueRule

  /** An arithmetic expression (`[HOUR_0_23] * 15`): syntax, data sources and functions checked. */
  public data object Expression : WffValueRule

  /** Any one of [members]. */
  public data class Union(val members: List<WffValueRule>) : WffValueRule

  /** A whitespace-separated list whose items each match [item]. */
  public data class ListOf(val item: WffValueRule) : WffValueRule

  /** Unconstrained text. */
  public data object Anything : WffValueRule
}

public data class WffAttributeSpec(
  val name: String,
  val type: WffAttributeType,
  val required: Boolean = false,
  val sinceVersion: WffVersion = WffVersion.V1,
  val enumValues: List<String> = emptyList(),
  /** Null when the XSD leaves the value unconstrained. */
  val rule: WffValueRule? = null,
)

/**
 * A child [id] allowed under some parent, and the format version that first allowed it there.
 * [minOccurs] and [maxOccurs] are how many times the XSD lets the child appear (`-1` for no limit
 * is [maxOccurs]'s default); they are the loosest bounds across versions, and a child that is one
 * of several alternatives has [minOccurs] 0.
 */
public data class WffChildSpec(
  val id: String,
  val sinceVersion: WffVersion = WffVersion.V1,
  val minOccurs: Int = 0,
  val maxOccurs: Int = UNBOUNDED,
) {
  public companion object {
    public const val UNBOUNDED: Int = -1
  }
}

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
  /** Bounds on the number of element children in total; [maxChildren] is -1 for no limit. */
  val minChildren: Int = 0,
  val maxChildren: Int = WffChildSpec.UNBOUNDED,
  /** For simple-content elements (`Expression`): what the text may be. */
  val textRule: WffValueRule? = null,
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

  /**
   * The children [parent] cannot be without: each [WffChildSpec] with [WffChildSpec.minOccurs]
   * above zero (`Template` needs a `Parameter`). A child that is one of several alternatives is not
   * listed; [parent]'s [WffElementSpec.minChildren] bounds those. Use [mayRemoveChild] to decide
   * whether a particular node can be deleted.
   */
  public fun requiredChildren(parent: WffElementSpec): List<WffChildSpec> =
    parent.children.filter { it.minOccurs > 0 }

  /**
   * Whether removing one child named [childName] from [parent], whose element children are now
   * [siblingNames] (document order, [childName] included), still satisfies the content model's
   * minimum counts: the child keeps its [WffChildSpec.minOccurs] and the parent keeps its
   * [WffElementSpec.minChildren]. False when [childName] is not in [siblingNames]. Unknown children
   * (passthrough) can always be removed. Maximum counts and order are not a concern when removing.
   */
  public fun mayRemoveChild(
    parent: WffElementSpec,
    childName: String,
    siblingNames: List<String>,
  ): Boolean {
    if (childName !in siblingNames) return false
    val edge = childEdge(parent, childName) ?: return true
    if (siblingNames.count { it == childName } - 1 < edge.minOccurs) return false
    return siblingNames.size - 1 >= parent.minChildren
  }

  private fun childEdge(parent: WffElementSpec, name: String): WffChildSpec? =
    parent.children.firstOrNull { elements.getValue(it.id).name == name }
}
