# watchface-tools

Shared Kotlin library for the Wear OS [Watch Face Format](https://developer.android.com/training/wearables/wff)
(WFF, a.k.a. declarative watch faces): a lossless document model, a deterministic XML writer and
parser, a version-aware schema, and the bundle layout of a watch face resource package.

## Modules

| Module | Targets | Purpose |
| --- | --- | --- |
| `:wff-core` | Kotlin Multiplatform: `jvm`, `wasmJs` | Document model (`WffDocument`, `WffElement`, ...), canonical `WffXmlWriter`, `WffXmlParser`, `WffSchema`, `WffValidator`, `WffBundleLayout`. `commonMain` has no dependencies. |
| `:wff-apk` | Kotlin Multiplatform: `jvm`, `wasmJs` | Pure-Kotlin APK packaging: `WffApkBuilder` turns a source bundle into a signed, installable APK (binary XML, `resources.arsc`, aligned zip, APK Signature Scheme v2/v3) with no aapt2, zipalign or apksig, so it runs in the browser. Also the shared Watch Face Push rules (`PushRules`). See [`wff-apk/README.md`](wff-apk/README.md). |
| `:push-app` | Android (Wear OS 6) | Marketplace app that links to a site account (device code + QR) and installs designs via Watch Face Push. See [`push-app/README.md`](push-app/README.md) and [`docs/PUSH_API.md`](docs/PUSH_API.md). |
| `:mobile-app` | Android (phone, API 37) | Faces on the phone: a published face as a Remote Compose launcher widget, compiled by a design server and pushed again each minute. See [`mobile-app/README.md`](mobile-app/README.md). |

`wff-packager` (JVM) turns a source bundle into a signed, validated, Watch Face Push-ready APK with the Android SDK tools and Google's validators; `wff-apk` does the packaging in pure Kotlin for the browser and for JVMs without an SDK. And
`push-app` is the Wear OS marketplace app that installs published designs (an APK, not on Maven).

## What `wff-core` gives you

- **Lossless tree.** `WffXmlParser.parse(xml)` keeps element and attribute order, text, CDATA and
  comments (also around the root). Only the XML declaration, processing instructions, a DOCTYPE and
  ignorable whitespace are dropped.
- **Canonical writer.** `doc.toXml()` is deterministic: 2-space indent, attributes in stored order,
  escaped values. `write(parse(write(x))) == write(x)` byte for byte.
- **Schema + validation.** `WffSchema` lists every element, attribute and allowed child of format
  versions 1–5 with the version that introduced each, generated from Google's XSDs (see
  [`tools/wff-xsd`](tools/wff-xsd/README.md)). `WffValidator.check(doc, WffVersion.V2)` reports unknown
  elements (warning, kept as passthrough), elements/attributes newer than the target, missing
  required attributes and disallowed children, with paths like `/WatchFace/Scene/Group[2]/PartText`.
  `WffValidator.minimumVersion(doc)` returns the lowest version that supports a document.
- **Bundle layout.** `WffBundleLayout.sourceFiles(bundle)` returns the files of a source bundle
  (`AndroidManifest.xml`, `res/raw/watchface.xml`, `res/xml/watch_face_info.xml`, drawables, fonts,
  strings) as `Map<String, ByteArray>`.

```kotlin
val doc = WffXmlParser.parse(xml)
val problems = WffValidator.check(doc, WffVersion.V2)
val canonical = doc.toXml()
```

## Consumers

- [yschimke/compose-ui-builder](https://github.com/yschimke/compose-ui-builder): visual builder; the
  browser editor emits WFF XML live from `wasmJs`.
- [yschimke/compose-ai-tools](https://github.com/yschimke/compose-ai-tools): render tools that
  discover and render watch faces; they replace their lossy render-only model's XML reading with this one.

## Versioning

Published to Maven Central under group `ee.schimke.watchface` with semantic versioning; `0.x`
while the model and schema API settle.

```kotlin
dependencies {
  implementation(platform("ee.schimke.watchface:watchface-tools-bom:0.11.0")) // x-release-please-version
  implementation("ee.schimke.watchface:wff-core")
  implementation("ee.schimke.watchface:wff-apk")      // pure-Kotlin packaging (JVM and Kotlin/Wasm)
  implementation("ee.schimke.watchface:wff-packager") // aapt2 + Google's validators (JVM)
}
```

The BOM constrains every published coordinate, so the modules stay aligned without naming a version
each. A release only uploads the modules that changed, so the BOM may point a module at an earlier
version than its own (see [`docs/VERSIONING.md`](docs/VERSIONING.md)).

`wff-core` is Kotlin Multiplatform (`wff-core-jvm`, `wff-core-wasm-js`) with no dependencies.
`wff-packager` additionally needs Google Maven (`google()`, for
`com.google.android.wearable.watchface.validator:validator-push`) and JitPack restricted to
`com.github.xgouchet` (the validator's `AXML` dependency is only published there):

```kotlin
repositories {
  google()
  mavenCentral()
  maven("https://jitpack.io") { content { includeGroup("com.github.xgouchet") } }
}
```

Releases are cut by release-please: merging its `chore(main): release X.Y.Z` PR tags a draft
release, `release.yml` checks, dry-runs and publishes the changed modules and the BOM to Central, then
un-drafts it. Do not confuse the library version with the WFF format version
(`WffVersion.V1..V5`), which is data the library models.

## Status and caveats

The schema is generated from the official XSDs, so element and attribute versions are
authoritative, and so are the attribute value types: enumerations (with the version each value
arrived in), integers and floats with their bounds, colours and other patterns, and the content
model's required and maximum child counts. Expressions are parsed with the grammar of Google's
`dwf-validator`, and the data sources and functions they use are checked against a table built from
the XSDs and that validator's `VersionRegistry`, so `minimumVersion` accounts for `[WEATHER.*]` and
friends. Sources that Google's registry has no entry for are not guessed at: a name that is neither
in the table nor under `CONFIGURATION.`, `COMPLICATION.` or `REFERENCE.` is an error. Google's XSD
validator in `wff-packager` stays the authority; `WffValidator` is the early, path-carrying
approximation (it does not check element order). `WffBundleLayout.minSdkFor` for versions 3-5 is
unconfirmed (`// verify`).

## Build

```
build-brief ./gradlew :wff-core:jvmTest
./gradlew :wff-core:compileKotlinWasmJs
./gradlew ktfmtFormat
```

Test fixtures under `wff-core/src/jvmTest/resources/wear-os-samples` come from
[android/wear-os-samples](https://github.com/android/wear-os-samples) (Apache-2.0, see the NOTICE there).

## License

Apache License 2.0, see [LICENSE](LICENSE).
