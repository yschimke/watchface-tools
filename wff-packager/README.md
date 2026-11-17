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
    [--package com.example.sideload] [--previous-version-code 11] [--token-out token.txt]
```

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
validator remains the authority; our checks exist for earlier, clearer errors.

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

## Android SDK

Needs `aapt2` and `zipalign` (build-tools) and `android.jar` (a platform), found via `ANDROID_HOME`,
`ANDROID_SDK_ROOT`, or `sdk.dir` in `local.properties`:

```
sdkmanager "build-tools;36.0.0" "platforms;android-36"
```

SDK-dependent tests skip with that message when the SDK is absent; CI sets `WFF_REQUIRE_SDK=1` so
they fail instead.
