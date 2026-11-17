# Watch Face Push site API (v1)

Contract between the Wear OS push app (`:push-app`) and the hosted watch-face site
(compose-preview-server, `preview.coo.ee`). The client is implemented in
`push-app/.../data/PushApi.kt`; an in-process fake of this contract lives in `FakePushServer.kt`
and is what debug builds and the tests run against.

All paths are under `/api/watchface-push/v1`. JSON bodies, UTF-8. The base URL is configurable
(`-Ppush.baseUrl=...` at build time, or `--es base_url` on a debug launch).

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

Auth required. The designs the account has **published** for watch install (not drafts), newest
first. Response `200`:

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

### `GET /designs/{id}/apk`

Auth required. `200` with `application/vnd.android.package-archive`, body = the signed APK whose
SHA-256 equals `sha256` in the listing. Must be immutable per `version` (use `ETag` and
`Cache-Control: private`). `404` if unpublished.

## Telemetry (optional)

### `POST /installs`

Auth required. Fire-and-forget; failures are ignored by the client and never block an install.

```json
{ "designId": "aurora", "version": 3, "result": "installed", "wearOsApi": 36 }
```

`result`: `installed` (new slot), `updated` (same package), `replaced` (single-slot watch swapped a
different design), `failed` (with `errorKind`, one of `SLOT_LIMIT_REACHED`,
`INVALID_VALIDATION_TOKEN`, `INVALID_PACKAGE_NAME`, `MALFORMED_APK`, `UNEXPECTED_CONTENT`,
`INVALID_SLOT`, `UNKNOWN`). Response `204`.

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
    get: { security: [{ device: [] }], summary: Published designs }
  /designs/{id}/apk:
    get: { security: [{ device: [] }], summary: Download the signed APK }
  /installs:
    post: { security: [{ device: [] }], summary: Install telemetry (optional) }
```
