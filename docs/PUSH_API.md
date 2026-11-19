# Watch Face Push site API (v1)

Contract between the Wear OS push app (`:push-app`) and the hosted watch-face site
(compose-preview-server, `preview.coo.ee`). The client is implemented in
`push-app/.../data/PushApi.kt`; an in-process fake of this contract lives in `FakePushServer.kt`
and is what debug builds and the tests run against.

All paths are under `/api/watchface-push/v1`. JSON bodies, UTF-8.

## Catalog servers

Any site that implements this API can serve designs. The app starts on **`https://ui.coo.ee`**
and its server picker (on the link screen) also offers `https://preview.coo.ee`. Any other server
can be typed in on the watch. Builds can change both: `-Ppush.baseUrl=…` for the default and
`-Ppush.servers=a,b` for the list. Debug builds also take `--es base_url` on launch.

- Addresses are https only. Debug builds also accept `http://` for a server on the developer's
  machine. User info, a query or a fragment is refused.
- A device token belongs to the server that issued it, and is only ever sent to that server. Choosing
  another server shows the link screen for it. Switching back to a server whose token the watch
  still holds is linked again without a new code. The watch holds one token at a time, so linking a
  second server replaces the first server's token.
- The server can only be changed while unlinked. To move a linked watch, unlink first.

## Naming rule (shared with the packager)

Every APK the site serves must have package name

```
<marketplacePackage>.watchfacepush.<faceName>
```

where `<marketplacePackage>` is the push app's applicationId, **`ee.schimke.watchface.push`**
(single source of truth: `watchface.marketplacePackage` in the root `gradle.properties`, exposed to
the app as `BuildConfig.MARKETPLACE_PACKAGE` / `PushConstants.PACKAGE_PREFIX`). The Watch Face Push
API rejects any other package. `faceName` is `[A-Za-z0-9_]+`; the packager derives it from the
design id. The client refuses designs whose `packageName` does not follow the rule.

To produce a conforming APK with `:wff-packager`, pass
`--push-marketplace ee.schimke.watchface.push --face-name <faceName>` (see `wff-packager/README.md`).

`:wff-packager` output contract consumed here: APK bytes, push validation token (generated with
`<marketplacePackage>` as `--package_name`), and the package name above. Per the push docs the APK
must be `hasCode=false`, `minSdk >= 33`, and contain only `AndroidManifest.xml`, `resources.arsc`,
`res/**`, `META-INF/**`.

## Authentication

Linking is optional. Without it the watch browses the server's **public** designs: `GET /designs`
and the public designs' `GET /designs/{id}/apk` work with no `Authorization` header. Linking adds the
account's own (private) designs. On the watch, an unlinked watch opens on the public list, which
offers "Sign in for your designs" (the device-code screen below); swiping back returns to the list.

Account linking uses the OAuth device-code pattern so nothing is typed on the watch.

1. The watch calls `POST /device-codes`, shows `code` and a QR of `verificationUrl`.
2. The user opens `verificationUrl` (`https://<site>/link?code=ABCD-2345`) on a signed-in phone or
   laptop and confirms "Link <deviceName>?".
3. The watch polls until approved and stores `deviceToken`.
4. Subsequent calls send `Authorization: Bearer <deviceToken>`. A `401` means the token was revoked
   (user unlinked the device on the site): the watch discards it and shows the link screen.

The device token is long-lived, scoped to *read the account's published designs and download their
APKs*, and revocable from the site. It is stored on the watch in DataStore, encrypted with an
Android Keystore AES-GCM key.

### `POST /device-codes`

Request (public, rate limit by IP):

```json
{ "deviceName": "Pixel Watch 4", "wearOsApi": 36 }
```

Response `200`:

```json
{
  "code": "AB7K-92QX",
  "pollSecret": "b1d9…",
  "verificationUrl": "https://preview.coo.ee/link?code=AB7K-92QX",
  "expiresInSeconds": 600,
  "pollIntervalSeconds": 5
}
```

`code` is short and unambiguous (no `0/O/1/I`), single use. `pollSecret` is random, ≥128 bits,
never shown to the user; only the watch can poll with it.

### `POST /device-codes/{code}/poll`

Request: `{ "pollSecret": "b1d9…" }`

Response `200` with `status`:

| `status` | Meaning | Extra fields |
| --- | --- | --- |
| `pending` | User has not confirmed yet. Poll again after `pollIntervalSeconds`. | |
| `approved` | Confirmed. Returned **once**; the code is then consumed. | `deviceToken`, `accountName` |
| `denied` | User declined on the site. | |
| `expired` | Past `expiresInSeconds`. | |

`404`/`410`: unknown, consumed or expired code (the client treats both as expired). A wrong
`pollSecret` is `404`. Servers may answer `429` with `Retry-After`; the client keeps polling.

## Designs

### `GET /designs`

Auth optional. Anonymous: every **public** design. With a device token: the public designs plus the
ones the account has **published** for watch install (not drafts), newest first. A wrong or revoked
token is `401`, never a silent fallback to the anonymous list. Response `200`:

```json
{
  "designs": [
    {
      "id": "aurora",
      "title": "Aurora",
      "description": "Analog face with a soft gradient sky.",
      "previewUrl": "https://preview.coo.ee/previews/aurora.png",
      "version": 3,
      "versionName": "1.3",
      "wffVersion": 4,
      "packageName": "ee.schimke.watchface.push.watchfacepush.aurora",
      "apkUrl": "https://preview.coo.ee/api/watchface-push/v1/designs/aurora/apk",
      "sha256": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
      "sizeBytes": 48211,
      "validationToken": "…"
    }
  ]
}
```

| Field | Notes |
| --- | --- |
| `id` | Stable design id. |
| `title`, `description` | Display only. |
| `previewUrl` | PNG, square, ≥ 320 px. Public or signed; no auth header is sent to other hosts. |
| `version` | Monotonic integer **equal to the APK `versionCode`**. The watch compares it with the installed `versionCode` from `listWatchFaces` to offer "Update". |
| `versionName` | Display only. |
| `wffVersion` | Minimum Watch Face Format version (1–5), i.e. the `com.google.wear.watchface.format.version` the APK declares. The watch refuses designs newer than it supports. |
| `packageName` | Must equal the APK package and follow the naming rule. Lets the watch match a design to an installed slot. |
| `apkUrl` | Where to download. May be on the API host (token sent) or a CDN (no token sent). |
| `sha256` | Lowercase hex SHA-256 of the exact APK bytes served. The watch verifies it before calling the push API. |
| `validationToken` | Watch Face Push validator token for *this APK* and the marketplace package. Tokens do not expire but are tied to the APK; re-issue when `version` changes. |

### Builds in progress

A site that builds APKs in the background lists designs (and catalog faces) that are not built
yet **only when asked**: `GET /designs?includePending=1`. Apps from before this field had a
stricter model and would reject such an entry, so a request without the parameter lists only built
entries. Every entry may carry an optional `build` object. The top-level fields always describe the
newest **built** version; `build` describes the newest **requested** one:

```json
{ "id": "comet", "title": "Comet", "previewUrl": "…", "wffVersion": 2,
  "packageName": "ee.schimke.watchface.push.watchfacepush.comet", "visibility": "private",
  "build": { "state": "pending", "scheduledAtEpochMillis": 1800000180000, "automatic": true } }
```

| Field | Notes |
| --- | --- |
| `build.state` | `pending`, `queued`, `building`, `built`, `refused` or `failed`. The watch reads an unknown state as building. |
| `build.scheduledAtEpochMillis` | `pending`: when the build starts ("Builds in N min"). |
| `build.queueAhead` | `queued`: builds ahead of this one ("Queued (N ahead)"). |
| `build.stage` | `building`: `packaging` today (`validating`, `signing`, `exporting` reserved); shown as is ("Building… packaging"). |
| `build.version` | The built version; may be sent only once `state` is `built`. |
| `build.reason` | `refused` / `failed`: a short sentence, shown after "Build failed:" / "Build refused:". |

The server may also send `startedAtEpochMillis`, `elapsedMillis`, `diagnostics[]` (for `refused`),
`nextRetryAtEpochMillis` and `automatic`; the watch ignores them for now.

- No `build` means built (older servers).
- An entry is installable only with `apkUrl`, `sha256` and `validationToken` all present. An unbuilt
  entry has none of them, nor `version` or `sizeBytes`; `previewUrl` may be absent too. The watch
  lists it with its build state and does not offer Install.
- While v3 is built and v4 builds, the entry carries v3's `version`, `apkUrl`, `sha256` and
  `validationToken` and `build: { state: "building", version: 4 }`: the watch installs v3 and shows
  "Update available" only once v4 is built (the top-level `version` moved).
- While any listed entry is building, the watch lists again about every 30 s, only while the app is
  visible (well within the server's per-address and per-token read limits), and keeps the list on a
  failed poll.

### Catalog faces

Besides accounts' designs, the anonymous listing carries the faces of public catalogs on
preview.coo.ee (for example `wear-os-samples-watchfaces` and `bonstio-casino-gshark`). They come
**after** the designs, and only catalogs under an allowed licence are listed: Apache-2.0, MIT,
BSD-2-Clause, BSD-3-Clause, CC0-1.0, Unlicense or CC-BY-4.0. The extra fields are optional on every
entry:

| Field | Notes |
| --- | --- |
| `source` | `design` or `catalog`. Absent means `design`. |
| `catalog` | `{ "system": "wear-os-samples-watchfaces", "title": "Wear OS samples" }`, only for `catalog`. The watch groups catalog faces by `system`, under `title`. |
| `attribution` | `{ "repoUrl", "sourceUrl", "license" }`, only for `catalog`. `license` is an SPDX id. The detail screen shows the catalog, the licence and the source. |
| `visibility` | `public` (display only). |

For a catalog face:
- `id` is `catalog:<system>/<previewId>`. It contains `/`, so clients never build a URL or a route
  from it.
- `apkUrl` and `previewUrl` are absolute and built by the server, under
  `/api/watchface-push/v1/catalog-faces/<system>/<previewId>/apk?v=…`. Use them exactly as given.
- `packageName` is `<marketplacePackage>.watchfacepush.c_<system>_<preview>_<10 hex>`.
- `version`, `sha256`, `validationToken`, `wffVersion` and `sizeBytes` mean the same as for designs.

### `GET /designs/{id}/apk`

Auth optional for a public design; a private one without a token is `401` (the watch says "This face
is private. Link your account."). A private design requested with another account's valid token is `404`, not `401`:
a `401` tells a healthy watch to discard its token. `200` with `application/vnd.android.package-archive`, body = the
signed APK whose SHA-256 equals `sha256` in the listing. Must be immutable per `version` (use `ETag`
and `Cache-Control: private`). `404` if unpublished.

## Telemetry (optional)

### `POST /installs`

Auth required (an unlinked watch sends none). Fire-and-forget; failures are ignored by the client
and never block an install.

```json
{ "designId": "aurora", "version": 3, "result": "installed", "wearOsApi": 36,
  "packageName": "ee.schimke.watchface.push.watchfacepush.aurora",
  "activation": "manual", "activationError": "PERMISSION_MISSING",
  "deviceModel": "Pixel Watch 3", "wearOsVersion": "6", "watchFacePushApiVersion": "1.0.0",
  "appVersion": "0.7.0" }
```

`result`: `installed` (new slot), `updated` (same package), `replaced` (single-slot watch swapped a
different design), `failed` (with `errorKind`, one of `SLOT_LIMIT_REACHED`,
`INVALID_VALIDATION_TOKEN`, `INVALID_PACKAGE_NAME`, `MALFORMED_APK`, `UNEXPECTED_CONTENT`,
`INVALID_SLOT`, `UNKNOWN`), or `activation`: a second event for the same face, sent when the user
grants the permission after the install and the app then activates it. Response `204`.

Every field after `result` is optional; older apps send only the first five, and the server must
accept events with any of them missing (or with fields it does not know).

- `packageName`: the face's package (the installed one; the design's for `failed`).
- `activation`, sent once activation has finished (absent for `failed`): `activated`;
  `not_requested` (the user did not ask); `manual` (the user must pick the face themselves:
  the permission is not held, or `setWatchFaceAsActive` was already used, with
  `activationError` `PERMISSION_MISSING` or `MAX_ACTIVATION_ATTEMPTS`); `failed` (any other
  refusal, `activationError` is its kind, such as `INVALID_SLOT`).
- `deviceModel` (`Build.MODEL`), `wearOsVersion` (the Wear OS release for the API level, absent
  for one the app does not know), `watchFacePushApiVersion` (the `androidx.wear.watchfacepush`
  library version), `appVersion` (the push app's versionName).

## Errors

Non-2xx responses may carry `{ "error": "code", "message": "…" }`; the client only relies on the
status code (`401` unlink, `404`/`410` expired code, anything else is shown as a generic failure).

## OpenAPI sketch

```yaml
openapi: 3.0.3
info: { title: Watch Face Push, version: "1" }
servers: [{ url: https://preview.coo.ee/api/watchface-push/v1 }]
components:
  securitySchemes:
    device: { type: http, scheme: bearer }
paths:
  /device-codes:
    post: { summary: Start device-code linking, responses: { "200": { description: DeviceCode } } }
  /device-codes/{code}/poll:
    post: { summary: Poll for approval, parameters: [{ name: code, in: path, required: true, schema: { type: string } }] }
  /designs:
    get: { security: [{}, { device: [] }], summary: Public designs, plus the account's own when linked }
  /designs/{id}/apk:
    get: { security: [{}, { device: [] }], summary: Download the signed APK (public ones anonymously) }
  /installs:
    post: { security: [{ device: [] }], summary: Install telemetry (optional) }
```
