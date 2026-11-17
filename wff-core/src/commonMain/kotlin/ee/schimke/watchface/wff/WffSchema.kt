package ee.schimke.watchface.wff

import ee.schimke.watchface.wff.WffVersion.V1
import ee.schimke.watchface.wff.WffVersion.V2
import ee.schimke.watchface.wff.WffVersion.V3

/**
 * Value kind of an attribute. Informational in Phase 0: numeric and colour attributes also accept
 * expressions (`[CONFIGURATION.x]`, `[HOUR_0_23] * 15`), so the validator does not check values.
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
  val sinceVersion: WffVersion = V1,
  val enumValues: List<String> = emptyList(),
)

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
  val childIds: List<String>,
  val allowsText: Boolean = false,
) {
  public fun attribute(name: String): WffAttributeSpec? = attributes.firstOrNull { it.name == name }
}

/**
 * Table of known Watch Face Format elements, their attributes and the format version that
 * introduced each. Built from the public documentation at
 * https://developer.android.com/training/wearables/wff; entries marked `// verify` are best
 * recollection and should be checked against the Google XSD (the planned `wff-validator` module).
 */
public object WffSchema {
  public const val ROOT_ID: String = "WatchFace"

  public val elements: Map<String, WffElementSpec> by lazy { buildSchema() }

  public val root: WffElementSpec
    get() = elements.getValue(ROOT_ID)

  /** Every spec whose XML name is [name], across contexts. */
  public fun named(name: String): List<WffElementSpec> = elements.values.filter { it.name == name }

  public fun isKnownName(name: String): Boolean = elements.values.any { it.name == name }

  /** The spec for child [name] of [parent], or null when [parent] does not allow it. */
  public fun child(parent: WffElementSpec, name: String): WffElementSpec? =
    parent.childIds.asSequence().map { elements.getValue(it) }.firstOrNull { it.name == name }

  // ---- DSL -------------------------------------------------------------------------------------

  private class Builder {
    val out = LinkedHashMap<String, WffElementSpec>()

    fun el(
      id: String,
      since: WffVersion = V1,
      text: Boolean = false,
      attrs: List<WffAttributeSpec> = emptyList(),
      children: List<String> = emptyList(),
      name: String = id.substringAfterLast('.'),
    ) {
      check(id !in out) { "duplicate schema id $id" }
      out[id] = WffElementSpec(id, name, since, attrs, children.distinct(), text)
    }
  }

  private fun a(
    name: String,
    type: WffAttributeType,
    req: Boolean = false,
    since: WffVersion = V1,
    values: List<String> = emptyList(),
  ) = WffAttributeSpec(name, type, req, since, values)

  private fun int(n: String, req: Boolean = false, since: WffVersion = V1) =
    a(n, WffAttributeType.INT, req, since)

  private fun flt(n: String, req: Boolean = false, since: WffVersion = V1) =
    a(n, WffAttributeType.FLOAT, req, since)

  private fun str(n: String, req: Boolean = false, since: WffVersion = V1) =
    a(n, WffAttributeType.STRING, req, since)

  private fun color(n: String, req: Boolean = false, since: WffVersion = V1) =
    a(n, WffAttributeType.COLOR, req, since)

  private fun bool(n: String, req: Boolean = false, since: WffVersion = V1) =
    a(n, WffAttributeType.BOOLEAN, req, since, listOf("TRUE", "FALSE"))

  private fun expr(n: String, req: Boolean = false, since: WffVersion = V1) =
    a(n, WffAttributeType.EXPRESSION, req, since)

  private fun res(n: String, req: Boolean = false, since: WffVersion = V1) =
    a(n, WffAttributeType.RESOURCE, req, since)

  private fun id(n: String, req: Boolean = false) = a(n, WffAttributeType.ID, req)

  private fun enum(n: String, vararg v: String, req: Boolean = false, since: WffVersion = V1) =
    a(n, WffAttributeType.ENUM, req, since, v.toList())

  private val box = listOf(int("x"), int("y"), int("width"), int("height"))
  private val transformAttrs =
    listOf(int("angle"), flt("pivotX"), flt("pivotY"), int("alpha"), str("name"))
  private val handAttrs =
    listOf(
      res("resource", req = true),
      int("x"),
      int("y"),
      int("width"),
      int("height"),
      flt("pivotX"),
      flt("pivotY"),
      int("angle"),
      color("tintColor"),
    )

  private val shapes = listOf("Arc", "Ellipse", "Line", "Rectangle", "RoundRectangle")
  private val parts = listOf("Group", "PartDraw", "PartImage", "PartText", "PartAnimatedImage")
  private val clocks = listOf("AnalogClock", "DigitalClock")
  private val behaviours = listOf("Variant", "Transform", "Gyro", "Launch")

  /** Visual content that can appear inside Group, Complication, conditional branches, options. */
  private val layout =
    parts + clocks + listOf("Condition", "Variant", "Gyro", "Launch", "Reference")

  private fun buildSchema(): Map<String, WffElementSpec> =
    Builder()
      .apply {
        el(
          "WatchFace",
          attrs =
            listOf(
              int("width", req = true),
              int("height", req = true),
              enum("clipShape", "NONE", "CIRCLE", "RECTANGLE", since = V2), // verify
            ),
          children =
            listOf(
              "Metadata",
              "BitmapFonts",
              "Localization",
              "UserConfigurations",
              "Scene",
              "Weather",
            ),
        )
        el("Metadata", attrs = listOf(str("key", req = true), str("value", req = true)))

        // ---- Scene and layout ----
        el(
          "Scene",
          attrs = listOf(color("backgroundColor")),
          children =
            layout +
              listOf(
                "ComplicationSlot",
                "Scene.ListConfiguration",
                "Scene.BooleanConfiguration",
                "Scene.ColorConfiguration",
              ),
        )
        el(
          "Group",
          attrs =
            box + transformAttrs + listOf(flt("scaleX", since = V2), flt("scaleY", since = V2)),
          children =
            layout + listOf("Transform", "Scene.ListConfiguration", "Scene.BooleanConfiguration"),
        )
        el(
          "PartDraw",
          attrs = box + transformAttrs + listOf(color("tintColor")),
          children = shapes + behaviours + listOf("Reference"),
        )
        el(
          "PartImage",
          attrs =
            box +
              transformAttrs +
              listOf(color("tintColor"), enum("renderMode", "SOURCE", "MASK", "ALL")),
          children = listOf("Image", "Photos", "Reference") + behaviours,
        )
        el(
          "PartText",
          attrs = box + transformAttrs + listOf(color("tintColor")),
          children = listOf("Text", "Localization", "Reference") + behaviours,
        )
        // verify: element name and structure (PartAnimatedImage + AnimatedImage)
        el(
          "PartAnimatedImage",
          attrs = box + transformAttrs + listOf(color("tintColor")),
          children = listOf("AnimatedImage") + behaviours,
        )
        el(
          "AnimatedImage",
          attrs =
            listOf(
              res("resource", req = true),
              enum("format", "WEBP", "GIF", "AVIF"), // verify
              enum("playbackMode", "ONCE", "LOOP", "BOUNCE"), // verify
            ),
          children = listOf("Variant"),
        )
        el("Image", attrs = listOf(res("resource", req = true)), children = listOf("Variant"))

        // ---- Shapes ----
        val strokeFill = listOf("Stroke", "WeightedStroke", "Fill", "Variant", "Transform")
        el(
          "Arc",
          attrs =
            listOf(
              flt("centerX", req = true),
              flt("centerY", req = true),
              flt("width", req = true),
              flt("height", req = true),
              flt("startAngle", req = true),
              flt("endAngle", req = true),
              enum("direction", "CLOCKWISE", "COUNTER_CLOCKWISE"),
            ),
          children = strokeFill,
        )
        el("Ellipse", attrs = box + transformAttrs.take(3), children = strokeFill)
        el(
          "Line",
          attrs = listOf(flt("startX"), flt("startY"), flt("endX"), flt("endY")),
          children = strokeFill,
        )
        el("Rectangle", attrs = box + transformAttrs.take(3), children = strokeFill)
        el(
          "RoundRectangle",
          attrs = box + listOf(flt("cornerRadiusX"), flt("cornerRadiusY")) + transformAttrs.take(3),
          children = strokeFill,
        )
        el(
          "Stroke",
          attrs =
            listOf(
              color("color", req = true),
              flt("thickness", req = true),
              enum("cap", "BUTT", "ROUND", "SQUARE"),
            ),
          children = listOf("Variant", "Transform"),
        )
        // verify: version
        el(
          "WeightedStroke",
          since = V2,
          attrs =
            listOf(
              flt("thickness", req = true),
              a("colors", WffAttributeType.STRING, req = true),
              enum("cap", "BUTT", "ROUND", "SQUARE"),
              bool("interpolate"),
              a("weights", WffAttributeType.STRING),
            ),
          children = listOf("Variant", "Transform"),
        )
        el(
          "Fill",
          attrs = listOf(color("color", req = true)),
          children = listOf("Variant", "Transform"),
        )

        // ---- Clocks ----
        el(
          "AnalogClock",
          attrs = box + transformAttrs,
          children = listOf("HourHand", "MinuteHand", "SecondHand", "Variant"),
        )
        el("HourHand", attrs = handAttrs, children = listOf("Variant"))
        el("MinuteHand", attrs = handAttrs, children = listOf("Variant"))
        el("SecondHand", attrs = handAttrs, children = listOf("Sweep", "Variant"))
        el(
          "Sweep",
          attrs = listOf(enum("frequency", "ON_TICK", "TWO", "FOUR", "EIGHT", "SIXTEEN")),
        ) // verify enum
        el(
          "DigitalClock",
          attrs = box + transformAttrs,
          children = listOf("TimeText", "Localization", "Variant"),
        )
        el(
          "TimeText",
          attrs =
            box +
              transformAttrs +
              listOf(
                str("format"),
                enum("hourFormat", "SYNC_TO_DEVICE", "12", "24"),
                enum("align", "START", "CENTER", "END"),
              ),
          children = listOf("Font", "Text.BitmapFont", "Variant", "Localization"),
        )
        el(
          "Localization",
          since = V2, // verify: may be V1
          attrs =
            listOf(
              enum("calendar", "GREGORIAN", "CHINESE", "HIJRI", "HEBREW"), // verify values
              str("timeZone"),
              str("dateTimePattern"),
              str("numberingSystem"),
            ),
        )

        // ---- Text ----
        el(
          "Text",
          attrs =
            listOf(
              enum("align", "START", "CENTER", "END"),
              bool("ellipsis"),
              int("maxLines", since = V2),
            ),
          children = listOf("Font", "Text.BitmapFont"),
        )
        el(
          "Font",
          attrs =
            listOf(
              str("family", req = true),
              flt("size", req = true),
              enum(
                "weight",
                "THIN",
                "EXTRA_LIGHT",
                "LIGHT",
                "NORMAL",
                "MEDIUM",
                "SEMI_BOLD",
                "BOLD",
                "EXTRA_BOLD",
                "BLACK",
              ),
              enum("slant", "NORMAL", "ITALIC"),
              color("color"),
              flt("letterSpacing", since = V2), // verify
            ),
          children = listOf("Outline", "Template", "Variant"),
          text = true, // ignorable whitespace only in practice
        )
        el(
          "Outline",
          attrs = listOf(color("color", req = true), flt("width", req = true)),
          children = listOf("Template"),
        )
        el("Template", text = true, children = listOf("Parameter"))
        el("Parameter", attrs = listOf(expr("expression", req = true)))
        el(
          "Text.BitmapFont",
          attrs = listOf(str("family", req = true), flt("size", req = true)),
          name = "BitmapFont",
          children = listOf("Template"),
        )
        el("BitmapFonts", since = V2, children = listOf("BitmapFonts.BitmapFont")) // verify
        el(
          "BitmapFonts.BitmapFont",
          since = V2,
          name = "BitmapFont",
          attrs = listOf(str("name", req = true)),
          children = listOf("Character", "Word"),
        )
        val glyph =
          listOf(
            str("name", req = true),
            res("resource", req = true),
            int("width", req = true),
            int("height", req = true),
          )
        el("Character", since = V2, attrs = glyph)
        el("Word", since = V2, attrs = glyph)

        // ---- Complications ----
        el(
          "ComplicationSlot",
          attrs =
            box +
              listOf(
                int("slotId", req = true),
                str("name"),
                str("displayName"),
                a("supportedTypes", WffAttributeType.STRING),
                bool("isCustomizable"),
              ),
          children =
            listOf(
              "DefaultProviderPolicy",
              "BoundingBox",
              "BoundingOval",
              "BoundingRoundBox",
              "Complication",
              "Variant",
            ),
        )
        el(
          "DefaultProviderPolicy",
          attrs =
            listOf(
              str("defaultSystemProvider"),
              str("defaultSystemProviderType"),
              str("primaryProvider"),
              str("primaryProviderType"),
              str("secondaryProvider"),
              str("secondaryProviderType"),
            ),
        )
        val bounds = box + listOf(flt("outlinePadding"))
        el("BoundingBox", attrs = bounds)
        el("BoundingOval", attrs = bounds)
        el("BoundingRoundBox", attrs = bounds + listOf(flt("cornerRadius")))
        el(
          "Complication",
          attrs = listOf(a("type", WffAttributeType.ENUM, req = true)),
          children = layout,
        )

        // ---- User configuration ----
        el(
          "UserConfigurations",
          children =
            listOf(
              "ListConfiguration",
              "BooleanConfiguration",
              "ColorConfiguration",
              "PhotosConfiguration",
              "Flavors",
            ),
        )
        val configAttrs =
          listOf(
            id("id", req = true),
            str("displayName"),
            res("icon"),
            str("screenReaderText"),
            str("defaultValue"),
          )
        el("ListConfiguration", attrs = configAttrs, children = listOf("ListOption"))
        el(
          "ListOption",
          attrs = listOf(id("id", req = true), str("displayName"), res("icon")),
        )
        el("BooleanConfiguration", attrs = configAttrs)
        el("ColorConfiguration", attrs = configAttrs, children = listOf("ColorOption"))
        el(
          "ColorOption",
          attrs =
            listOf(id("id", req = true), str("displayName"), a("colors", WffAttributeType.STRING)),
        )
        el(
          "PhotosConfiguration",
          since = V2,
          attrs = listOf(id("id", req = true), str("configType", req = true)),
        )
        el("Flavors", since = V2, attrs = listOf(str("defaultValue")), children = listOf("Flavor"))
        el(
          "Flavor",
          since = V2,
          attrs = listOf(id("id", req = true), str("displayName"), res("icon")),
          children = listOf("Configuration", "Flavor.ComplicationSlot"),
        )
        el(
          "Configuration",
          since = V2,
          attrs = listOf(id("id", req = true), str("optionId", req = true)),
        )
        el(
          "Flavor.ComplicationSlot",
          since = V2,
          name = "ComplicationSlot",
          attrs = listOf(int("slotId", req = true)),
          children = listOf("DefaultProviderPolicy"),
        )

        // ---- Scene-side configuration selectors (contain layout) ----
        el(
          "Scene.ListConfiguration",
          name = "ListConfiguration",
          attrs = listOf(id("id", req = true)),
          children = listOf("Scene.ListOption"),
        )
        el(
          "Scene.ListOption",
          name = "ListOption",
          attrs = listOf(id("id", req = true)),
          children = layout,
        )
        el(
          "Scene.BooleanConfiguration",
          name = "BooleanConfiguration",
          attrs = listOf(id("id", req = true)),
          children = listOf("Scene.BooleanOption"),
        )
        el(
          "Scene.BooleanOption",
          name = "BooleanOption",
          attrs = listOf(id("id", req = true)),
          children = layout,
        )
        el(
          "Scene.ColorConfiguration",
          name = "ColorConfiguration",
          attrs = listOf(id("id", req = true)),
          children = listOf("Scene.ListOption"), // verify
        )

        // ---- Conditions, expressions, behaviours ----
        el("Condition", children = listOf("Expressions", "Compare", "Default"))
        el("Expressions", children = listOf("Expression"))
        el("Expression", attrs = listOf(str("name", req = true)), text = true)
        el("Compare", attrs = listOf(expr("expression", req = true)), children = layout)
        el("Default", children = layout)
        el(
          "Variant",
          attrs =
            listOf(
              enum("mode", "AMBIENT", req = true),
              str("target", req = true),
              str("value", req = true),
            ),
        )
        el(
          "Transform",
          attrs =
            listOf(
              str("target", req = true),
              expr("value", req = true),
              str("easing", since = V3), // verify
            ),
        )
        // verify: Gyro / Launch / Reference versions and attributes
        el(
          "Gyro",
          since = V2,
          attrs =
            listOf(
              str("x"),
              str("y"),
              str("angle"),
              str("scaleX"),
              str("scaleY"),
              str("alpha"),
            ),
        )
        el(
          "Launch",
          since = V2,
          attrs = listOf(a("target", WffAttributeType.STRING, req = true), str("name")),
        )
        el(
          "Reference",
          since = V2,
          attrs =
            listOf(
              str("name", req = true),
              str("source", req = true),
              str("defaultValue"),
            ),
        )

        // ---- Photos and Weather ----
        el(
          "Photos",
          since = V2,
          attrs =
            listOf(
              enum("change", "TAP", "AUTOMATIC", "DAILY", "NEVER"), // verify values
              str("changeAfterEvery"),
              str("source", req = true),
              res("defaultImageResource", req = true),
            ),
        )
        // verify: Weather structure (WeatherForecast children)
        el(
          "Weather",
          since = V2,
          attrs = listOf(int("hours"), int("index")),
          children = listOf("Forecast"),
        )
        el("Forecast", since = V2, attrs = listOf(int("hours"), int("index")))
      }
      .out
}
