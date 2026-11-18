# Generating the WFF schema table

`wff-core`'s schema (`wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffSchemaData.kt`) is
generated from the Watch Face Format XSDs in
[google/watchface](https://github.com/google/watchface), under
`third_party/wff/specification/documents/<version>/`. Each version's XSDs are complete, so the
generator records, for every element, attribute and parent→child edge, the first version that
allows it.

```
git clone https://github.com/google/watchface /tmp/watchface
python3 -I tools/wff-xsd/generate_schema.py /tmp/watchface \
  > wff-core/src/commonMain/kotlin/ee/schimke/watchface/wff/WffSchemaData.kt
./gradlew ktfmtFormat :wff-core:jvmTest
```

When a new format version appears, add it to `WffVersion` (the
`generatedSchemaCoversTheLatestVersion` test fails until you do) and to
`WffBundleLayout.minSdkFor`.

What the generator does:

- **Ids.** An element's id is its XML name, unless one name has several different definitions;
  then a local definition is qualified by its parent's id (`UserConfigurations.ListConfiguration`
  versus the in-scene `ListConfiguration`).
- **Substitution groups.** Abstract elements (`DrawElement`, `TextElement`, `ImageElement`,
  `AnimatableImageElement`, `BoundingShape`) are replaced by their members (`Arc`, `Text`, `Image`,
  `BoundingBox`, ...).
- **Value rules.** Each attribute's XSD type is evaluated into a `WffValueRule` (enumeration,
  pattern, number with bounds, expression, union, list). An enumeration value records the first
  version that has it. Unions with free text collapse to "anything". Patterns drop escapes that
  Kotlin/Wasm's regex engine rejects.
- **Content model.** Per element, the generator folds `sequence`/`choice`/`all` into the loosest
  min and max occurrence of each child and of the children in total (`minChildren`, `maxChildren`),
  so `Template` needs a `Parameter` and a scene-level `ListOption` holds exactly one child.
- **Child order.** Only a literal `xs:sequence` that occurs once is ordered; `all`, `choice`,
  repeated groups, group references and base-plus-extension types collapse to one unordered slot,
  and `xs:any` disables the check. (Group references and extensions are left out on evidence:
  Google's own Wear OS samples put `Transform` after the shape inside `PartDraw`, whose XSD
  extends an `all` base with an `all`; Google's validator accepts it.) Each child gets its slot
  index in the latest version (`GENERATED_CHILD_ORDER`), and any child whose relative order differs
  in an older version is dropped, so a recorded order holds in every version. That leaves a
  handful of elements: `SecondHand` and `TimeText` (`Variant` before the hand/font).
- **Data sources and functions.** The first version in which each name appears in `sourceType.xsd`
  and `_functionType`, overridden by Google's own table
  (`tools/dwf-validator/.../VersionRegistry.kt` in the same checkout) where it has an entry; the
  older XSDs misname some weather sources. Names it lacks are not invented. Google's public
  data-source table (developer.android.com/training/wearables/wff/common/attributes/source-type)
  does not list `LANGUAGE_CODE`, `LANGUAGE_COUNTRY_CODE`, `LANGUAGE_TEXT_DIRECTION`,
  `AMPM_STRING_ENG`, `AMPM_STRING_SHORT` or `WEATHER.TEMPERATURE_HIGH`/`_LOW` either (it documents
  `WEATHER.DAY_TEMPERATURE_HIGH`/`_LOW`, version 2), so those keep the XSD's first version.
- **Attribute kinds** are inferred from XSD types and names: enumerations, `booleanType`, integer
  and float bases, `resourcePathType`, colours.

The XSDs are Copyright Samsung Electronics and licensed under the Apache License 2.0; only facts
derived from them are committed here.
