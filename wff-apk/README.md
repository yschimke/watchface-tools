# wff-apk

Kotlin Multiplatform (`jvm`, `wasmJs`) module that packages a Watch Face Format **source bundle**
(`WffBundleLayout.sourceFiles()`) into a signed, installable APK **without aapt2, zipalign or
apksig**, so the same code runs in the browser (the UI Builder's export, Watch Face Push) and on the
JVM. Its only dependency is `wff-core`.

```kotlin
val bundle = WffBundleLayout.sourceFiles(wffBundle)
val apk: WffApk =
  WffApkBuilder.build(                                   // suspend: WebCrypto signs asynchronously
    bundle,
    WffApkOptions(PackageTarget.WatchFacePush("com.example.market", "my_face"), versionCode = 12),
    signer,                                              // see "Signing" below
  )
apk.apk                  // the bytes
apk.signerCertificateSha256
```

On the JVM `WffApkBuilder.buildBlocking(...)` and `generateDebugApkSignerBlocking()` avoid a
coroutine dependency (there is none; `suspend` is the Kotlin standard library's).

## What it does

| Step | |
| --- | --- |
| `ApkPreflight` | The option, manifest-rewrite and Watch Face Push rules, **shared with `wff-packager`** (it calls the same function): one face, allowed entries and manifest tags, `minSdk` against the declared format version, `hasCode="false"`, package naming, plus `wff-core`'s check of the face against its declared version. |
| `BinaryXml` | `AndroidManifest.xml` and `res/xml/*.xml` to Android binary XML (AXML), attributes sorted by framework resource id, typed the way aapt2 types them (int, boolean, colour, float, `@type/name` reference, string). |
| `ResourceTable` | `resources.arsc` for `res/`: file resources by path, `<string>` values, qualified configurations (language and region, density, night, round, `ldrtl`, `vNN`), ids assigned as aapt2 assigns them (`0x7f` + type order + name order). |
| `Zip` | Entries deflated (own DEFLATE, fixed Huffman) or stored; `resources.arsc` and every stored entry start on a 4-byte boundary (what `zipalign -p 4` does); fixed timestamps, so the output is reproducible for a given signer. |
| `ApkSigningBlock` | APK Signature Scheme **v2 and v3** (`WffApkOptions.signV3`), SHA-256, ECDSA P-256 or RSA PKCS#1 v1.5. No v1: Wear OS 4+ never needs it. |

Everything above is plain Kotlin (SHA-256, CRC-32, DEFLATE and the DER it needs are in this
module); the dependency floor a consumer inherits is `wff-core` and the Kotlin standard library.

## Cheap report: `WffEstimate`

`WffEstimate.report(bundle)` returns the same `PackagerReport` (JSON `toJson()`) as
`wff-packager validate`, with `mode` `estimate`: the wff-core check and `WffMemoryEstimator`'s
per-resource memory estimate, with suggestions. It is pure and cheap (image headers only), so an
editor can run it on every change; the rules are in
[`wff-packager/README.md`](../wff-packager/README.md#the-cheap-estimate-estimate-wffestimate).

## Signing

`ApkSigner` is the one platform seam: a private key plus its certificate chain.

| | |
| --- | --- |
| JVM | `JcaApkSigner(privateKey, x509Chain)`: RSA or EC P-256 from a `KeyStore`. |
| Browser | `webCryptoApkSigner(cryptoKey, algorithm, derCertificates)` or `webCryptoApkSigner(pkcs8, ...)`: WebCrypto, so the key can be non-extractable. |
| Preview | `generateDebugApkSigner()`: a fresh ECDSA P-256 key behind a self-signed certificate. For sideloading and previews only: every call is a new identity and Android refuses an update signed by a different one. |

Published faces are signed by one long-lived key and the build should pin its certificate
(`WffApkOptions.expectedCertificateSha256`), as `wff-packager/README.md` describes for the JVM tool.
Never generate or commit a key for anything published.

## What it does not do

- **Run Google's validators.** The Watch Face Push validator (which issues the validation token),
  the XSD validator and the memory-footprint evaluator are a JVM-only, obfuscated artifact.
  `wff-packager`'s `Validators` accept any APK file, so the JVM side can validate what this module
  built before it is pushed. The tests in `wff-packager` do exactly that for every sample face.
- **Compile arbitrary Android resources.** Supported: `values*/` `<string>` entries, files under the
  standard type directories, and XML under `res/xml` and `res/raw` (raw is copied verbatim). XML
  anywhere else (vector drawables, layouts) needs attribute definitions from `android.jar`, so it is
  rejected with a message rather than compiled wrongly. Dimension and fraction attribute values,
  styled strings, plurals, arrays and nine-patch images are rejected the same way.
- **Re-encode images.** PNGs go in as given; aapt2 would crunch them.

## Tests

- `commonTest` (JVM and the browser): structure of the produced APK read back by an independent zip
  and `resources.arsc` reader; signing block layout; error cases.
- `jvmTest`: SHA-256, CRC-32 and DEFLATE against the JDK; zip entries read by `java.util.zip`.
- `wasmJsTest` (`wasmJsBrowserTest`, headless Chrome): SHA-256 against WebCrypto, DEFLATE output
  inflated by the browser's `DecompressionStream`, and the v2 signature verified by WebCrypto.
- `wff-packager`'s `PureApkTest`: apksig verification (v2 and v3), Google's validators, and, with an
  Android SDK, `aapt2 dump xmltree | resources | badging` of the pure APK equal to those of the
  aapt2-built one for every sample face.
