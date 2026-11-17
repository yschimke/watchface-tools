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
- **Attribute kinds** are inferred from XSD types and names: enumerations, `booleanType`, integer
  and float bases, `resourcePathType`, colours.

The XSDs are Copyright Samsung Electronics and licensed under the Apache License 2.0; only facts
derived from them are committed here.
