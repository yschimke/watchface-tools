# wff-packager

JVM module (not KMP) that turns a WFF **source bundle** (`WffBundleLayout.sourceFiles()`:
`AndroidManifest.xml`, `res/raw/watchface.xml`, `res/xml/watch_face_info.xml`, drawables, fonts,
strings) into an installable, signed, validated APK that is ready for **Watch Face Push**.

```kotlin
val packager = WatchFacePackager()               // SDK from ANDROID_HOME / ANDROID_SDK_ROOT / local.properties
val out: PackagedWatchFace = packager.pack(
  WffBundleLayout.sourceFiles(bundle),
  PackagerOptions(
    target = PackageTarget.WatchFacePush("com.example.market", "my_face"), // or Sideload()
    versionCode = 12,                                                      // caller-managed, increasing
    signing = SigningConfig.Keystore(File("release.p12"), "pw"),           // default: generated debug key
  ),
)
out.apk; out.validationToken; out.memoryFootprint; out.diagnostics
```

```
wff-packager pack --bundle dir --out face.apk --version-code 12 \
    [--push-marketplace com.example.market [--face-name my_face]] \
    [--keystore release.p12 --keystore-pass pw [--key-alias a] [--key-pass pw]] \
    [--package com.example.sideload] [--previous-version-code 11] [--token-out token.txt] \
    [--report-out report.json]

wff-packager validate --bundle dir --report-out report.json \
    [--push-marketplace com.example.market [--face-name my_face]] [--package p] [--version-code n]
```

## Report (`--report-out`) and `validate`

`pack --report-out f.json` writes a JSON report of every check, on failure too (`pack` still stops at
the first failing stage, and the checks after it are `skipped`). `validate` runs **every** check
without stopping: the APK is still built and run through the memory evaluator and push validator
when the face fails `wff-core`, and the push validator still runs when the memory evaluator fails.
It signs with the debug key, writes no APK and exits 0 only when the report `passed`. Without
`--push-marketplace` it takes the marketplace from a bundle package `<market>.watchfacepush.<face>`;
otherwise the push validator is skipped. In code: `WatchFacePackager.validate(bundle, options)` and
`packWithReport(bundle, options)` (`PackOutcome`: the APK or null, the report, the diagnostics).

```json
{"schemaVersion":1,"wffVersion":1,"minimumWffVersion":1,"passed":false,"checks":[
 {"id":"wff-schema","title":"WFF schema (wff-core)","status":"passed","findings":[]},
 {"id":"wff-xsd","title":"Google WFF XSD validator","status":"failed","findings":[
   {"severity":"error","message":"cvc-complex-type.3.2.2: ...","line":37,"column":48}]},
 {"id":"memory-footprint","title":"Memory footprint","status":"failed","findings":[
   {"severity":"error","message":"Asset hour was not found in the watch face package"}]},
 {"id":"push-validator","title":"Watch Face Push validator","status":"failed","findings":[...],
  "passedChecks":["SECURITY/APK size validation","..."],"tokenIssued":false}]}
```

- The checks are always these four, in this order. `status` is `passed`, `failed` or `skipped`.
- A `skipped` check carries one finding saying why: a `warning` when that is expected (a sideload
  build has no push validator; `pack` stopped at an earlier failure), an `error` when the APK could
  not be built (the finding quotes the aapt2 / manifest / push-rules errors).
- `passed` is true when no check failed and no finding is an error.
- Findings: `severity` (`error`/`warning`) and `message`; `line`/`column` (Google's XSD
  validator) and `path` (`wff-core`, such as `/WatchFace/Scene/@backgroundColor`) when known.
- `memory` (`totalBytes`, `maxActiveBytes`, `maxAmbientBytes`, `activeLimitBytes`,
  `ambientLimitBytes`) is present when the evaluator ran; `passedChecks` and `tokenIssued` when the
  push validator ran. **The token is never in the report**; `pack` prints it and `--token-out` saves it.
- A face that names a drawable the bundle does not ship fails `memory-footprint` with `Asset <name>
  was not found in the watch face package` (and the push validator repeats it). `wff-core` and the XSD
  do not check resource references; the estimate below does, and names the closest bundled file.
- `mode` is `full` here and `estimate` for the cheap report.
- `memory-footprint` also carries `contributors`, the per-resource breakdown (Google's evaluator
  only gives totals), most expensive first:
  `{"resource":"drawable/case_steel","kind":"image","format":"png","width":1024,"height":1024,
  "fileBytes":81234,"decodedBytes":4194304,"activeBytes":4194304,"ambientBytes":0,
  "renderedMaxWidth":450,"renderedMaxHeight":450,"missing":false,"suggestion":"downscale ..."}`.
  `kind` is `image`, `font` or `other`; sizes are absent when unknown; `format` is absent when
  `missing`. Findings may carry a `suggestion` too.

### The cheap estimate (`estimate`, `WffEstimate`)

`wff-packager estimate --bundle dir --report-out f.json`, or `WffEstimate.report(bundle)` from
`:wff-apk` (pure Kotlin, JVM and Kotlin/Wasm, so an editor can run it in the browser on every edit).
It runs the wff-core check and `WffMemoryEstimator`: no SDK, aapt2 or Google validator, and images
are read from their PNG/JPEG/WebP headers only, never decoded. The same JSON, with `mode`
`estimate`; `wff-xsd` and `push-validator` are `skipped` with a warning. Its rules:

- an image costs `width * height * 4` bytes in active mode when the face draws it, and in ambient
  mode unless it or an ancestor has `<Variant mode="AMBIENT" target="alpha" value="0"/>`; bitmap
  font glyphs count where text uses that font; bundled fonts cost their file size;
- the limits are Google's suggested Play settings (100 MB active, 10 MB ambient); over one is an
  error naming the largest contributors;
- a drawn image missing from the bundle is an error with the element path (Google's validators
  fail it too); a missing configuration icon is only a warning (they accept it);
- suggestions: downscale an image to the largest size it is drawn at (when it is over 10% larger in
  both dimensions and that saves at least 16 KB), hide a full-colour image in ambient or draw a
  greyscale one (not for a tinted image), remove a drawable nothing references.

It approximates Google's evaluator, which stays the authority before publishing. In Kotlin/Wasm it
adds about 39 KB to an optimized binary that already has wff-core's parser and validator (13 KB
gzipped: 284 KB to 323 KB, 99.6 KB to 112.6 KB).

## Relation to `wff-apk`

`:wff-apk` (Kotlin Multiplatform, also runs in the browser) builds the same kind of APK in pure
Kotlin: binary XML, `resources.arsc`, aligned zip and v2/v3 signing, with no aapt2, zipalign or
apksig. The two share the checks that run before any APK exists (`ApkPreflight`, `PushRules`,
`PackageTarget`, the diagnostics), which moved to `wff-apk` and keep their `ee.schimke.watchface.packager`
package, so this module's API is unchanged. This module stays the one that runs **Google's
validators**, which are JVM-only and issue the validation token; they accept any APK file, so an APK
built by `WffApkBuilder` can be validated here exactly like an aapt2-built one (`PureApkTest` does it for
every sample). aapt2 remains the reference for resource semantics: `PureApkTest` compares
`aapt2 dump xmltree`, `resources` and `badging` of both APKs.

Should the packager fall back to the pure path when no SDK is present? Not yet. The pure path covers
the subset of resources a face uses and rejects the rest, so a silent fallback would make the same
bundle succeed or fail depending on the machine. Offer it as an explicit option (`--pure`) first,
and make it the default only after it has built the whole published corpus with the same validator
verdicts.

## Pipeline

1. Rewrite the manifest's `package` / `versionCode` from the options; check the push rules and the
   watch face against its declared WFF version (`wff-core` validator).
2. `aapt2 compile --dir res` + `aapt2 link -I android.jar --manifest ...` (mirrors `ensureWffApk` in
   compose-ai-tools).
3. `zipalign -p 4`, then sign in-process with `com.android.tools.build:apksig` (v2+v3; Wear OS 4+
   never needs v1) and verify the result.
4. Run Google's validators on the signed APK and fail on any finding.

## Watch Face Push rules (enforced with specific diagnostics)

- one WFF watch face per APK (`res/raw/watchface.xml`);
- APK entries limited to `AndroidManifest.xml`, `resources.arsc`, `res/...`, `META-INF/...`;
- manifest tags limited to `manifest`, `uses-feature`, `uses-sdk`, `application`, `property`,
  `meta-data`; `minSdk >= 33`; `android:hasCode="false"`;
- package `<marketplacePackage>.watchfacepush.<faceName>` (a plain package for `Sideload`).

These are enforced for sideload builds too, so one bundle can yield both flavours. Google's
validator remains the authority; our checks exist for earlier, clearer errors. `minSdkVersion` must
be at least the API level the declared format version needs (`WffBundleLayout.minSdkFor`: 34 for
version 2), and never below 33.

Google's WFF XSD validator runs for both targets. It only logs its findings, so `GoogleWffValidator`
reads them from `java.util.logging` while it runs and reports each as an `error[wff-validator]`
with the `[Line n:Column m]: cvc-...` text, which fails packaging.

## Google's tooling: how it is distributed

| Tool | Distribution | Used here |
| --- | --- | --- |
| Watch Face Push validator (returns the validation token) | **Published**: `com.google.android.wearable.watchface.validator:validator-push` on Google Maven (`1.1.0-alpha01`; also `-android` and `-cli` variants) | `GooglePushValidator` |
| WFF XSD validator (`third_party/wff`, `com.samsung.watchface`) | Bundled inside `validator-push`. The standalone `wff-validator` / `memory-footprint` modules in github.com/google/watchface are only build-from-source (`group com.google.watchface`, published to `mavenLocal`/GitHub Packages, not Maven Central or Google Maven) | `GoogleWffValidator` |
| Memory-footprint evaluator (`play-validations/memory-footprint`) | Bundled inside `validator-push` (`com.google.wear.watchface.dfx.memory`), without the CLI `main` and `JvmImageProcessor`; we supply an ImageIO-based `ImageProcessor` equivalent to Google's | `GoogleMemoryFootprint` |

So there is no gap: one published artifact covers all three. It is an alpha, and obfuscated
(single-letter internals, Kotlin metadata stripped), so we only touch the public
`DwfValidatorFactory` / `WatchFaceLayoutEvaluator` / `WatchFaceXmlValidator` entry points. Its POM
depends on `com.github.xgouchet:AXML`, which only exists on JitPack (restricted to that group in
`settings.gradle.kts`).

The validation token comes **only** from `PushValidator` (`GooglePushValidator` in production).
`PushValidator`, `MemoryFootprintEvaluator` and `WffFormatValidator` are interfaces so tests can use
doubles (see `TestSupport.fakeValidators`); nothing else may mint a token. Tokens don't expire, but
Google asks that the validator be kept current, so bump `validatorPush` in `libs.versions.toml`
regularly.

## Signing keys

Pass a keystore for anything you publish. Without one, a per-installation debug key is generated on
first use under `$WFF_PACKAGER_HOME` (default `~/.config/wff-packager/debug.p12`, `0600`) and a
warning is emitted. Never commit keys (`*.p12`, `*.jks`, `*.keystore` are git-ignored).

### One shared marketplace key, from a secret

Android only installs an update signed with the same certificate as the installed APK. A site that
publishes faces for Watch Face Push therefore signs **every** face with one long-lived marketplace
key, and never with a key that a container rebuild can regenerate. Keep it in a secret, not on disk:

| Variable | |
| --- | --- |
| `WFF_SIGNING_KEYSTORE_B64` | the PKCS12 keystore, base64 (`base64 -w0 market.p12`) |
| `WFF_SIGNING_KEYSTORE_PASSWORD` | its store password |
| `WFF_SIGNING_KEY_ALIAS` | optional; defaults to the only key entry |
| `WFF_SIGNING_KEY_PASSWORD` | optional; defaults to the store password |
| `WFF_SIGNING_CERT_SHA256` | the certificate pin (below) |

```kotlin
val signing = SigningConfig.fromEnvironment() ?: error("no signing key configured")
PackagerOptions(
  target, versionCode, signing,
  expectedCertificateSha256 = SigningConfig.expectedCertificateFromEnvironment(),
)
```

or `wff-packager pack ... --keystore-from-env true`. `SigningConfig.InMemoryKeystore` takes the bytes
directly when the secret comes from somewhere other than the environment.

**Pin the certificate.** With `expectedCertificateSha256` (`--expect-cert-sha256`, or
`WFF_SIGNING_CERT_SHA256` with `--keystore-from-env`) packaging fails before signing if the key's
certificate is not the pinned one, so a wrong or rotated secret can't ship APKs that installed faces
refuse as updates. Colons and case are ignored. Every result carries `signerCertificateSha256`, and
the CLI prints it, so the first build tells you the value to pin:

```
keytool -list -v -keystore market.p12 | grep SHA256   # the same value
```

Generate the key once, with a long validity, and back it up: a lost marketplace key means no
published face can ever be updated.

```
keytool -genkeypair -keystore market.p12 -storetype PKCS12 -alias market \
  -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=<marketplace>"
```

## Android SDK

Needs `aapt2` and `zipalign` (build-tools) and `android.jar` (a platform), found via `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`:

```
sdkmanager "build-tools;36.0.0" "platforms;android-36"
```

SDK-dependent tests skip with that message when the SDK is absent; CI sets `WFF_REQUIRE_SDK=1` so
they fail instead.
