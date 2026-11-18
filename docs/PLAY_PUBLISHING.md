# Play publishing: the push app

The Wear OS marketplace app (`:push-app`, package `ee.schimke.watchface.push`) ships through Google
Play's **Wear OS internal** testing track (`wear:internal`), with `COMPLETED` status, so testers
get each release without a separate rollout. Production promotion stays a Play Console action. The setup mirrors
[yschimke/cadence](https://github.com/yschimke/cadence/blob/main/docs/play-publishing.md): Gradle
Play Publisher in the app's build, and a release workflow that signs with the upload key.

## How a release reaches Play

1. Merging the `chore(main): release X.Y.Z` pull request cuts the release (see [AGENTS.md → Releasing](../AGENTS.md#releasing)).
2. `release-please.yml` then calls [`play-release.yml`](../.github/workflows/play-release.yml) for
   the tag, **beside** the Maven publish rather than after it, so neither can block the other.
3. It builds `:push-app:bundleRelease` signed with the upload key, runs
   `:push-app:publishReleaseBundle` (`wear:internal`) and `:push-app:publishReleaseListing` (the
   en-GB title and descriptions in `push-app/src/main/play/`), and keeps the bundle as a workflow
   artifact.

Publishing is **opt-in**: releases publish only while the repository variable `PLAY_PUBLISH` is
`true` (release-please.yml passes it as the Play workflow's `publish_play`). Until then the workflow still builds the bundle, which proves the release tag builds. Set
`PLAY_PUBLISH_LISTING=false` to skip listing uploads. Listings are app-wide, so an edit also changes
the production listing.

A manual **Play release** dispatch builds a tag; tick `publish_play` to publish it as well, whatever
`PLAY_PUBLISH` says. Workflow changes apply from the selected workflow ref, and the app sources come
from the tag.

## Versions

The app version is the repository's: release-please rewrites `watchfaceToolsVersion` in
`gradle.properties`, and `push-app/build.gradle.kts` derives the version code from it as
`major * 10_000 + minor * 100 + patch` (0.2.0 is 200, 1.4.12 is 10412). Every release therefore
uploads a higher code. Play rejects a code it has already seen, so a published tag can't be
published twice; cut a new release instead.

Each release publishes the app even if `push-app/` did not change. On the internal track that is
harmless, and it keeps one version per release.

## One-time setup

1. **Create the app in Play Console** as a Wear OS app with package `ee.schimke.watchface.push`.
   Use Play App Signing. Play holds the app signing key, and CI only ever has the *upload* key.
2. **Create an upload key** and keep a backup; it can be reset through Play support, but that takes
   days.
   ```
   keytool -genkeypair -keystore push-app-upload.jks -storetype PKCS12 -alias upload \
     -keyalg RSA -keysize 4096 -validity 10950 -dname "CN=Faces upload"
   ```
3. **Make the first upload by hand.** The Play Developer API can't create an app, and the first
   bundle sets its package and upload certificate. Add the four `PUSH_APP_*` secrets from step 5
   first, so the bundle is signed with the upload key. Then run the **Play release** workflow for
   the latest tag without `publish_play`. Download the `push-app-vX.Y.Z` artifact, and upload its `.aab` to the
   Wear OS internal track in Play Console.
4. **Add a service account** with access to this app's testing releases and store presence (Play
   Console → Users and permissions), and create a JSON key for it.
5. **Add the repository secrets**, then set the repository variable `PLAY_PUBLISH=true`:

   | Secret | |
   | --- | --- |
   | `PUSH_APP_UPLOAD_KEYSTORE` | base64 of the upload keystore (`base64 -w0 push-app-upload.jks`) |
   | `PUSH_APP_KEYSTORE_PASSWORD` | its store password |
   | `PUSH_APP_KEY_ALIAS` | the key alias (`upload` above) |
   | `PUSH_APP_KEY_PASSWORD` | the key password |
   | `PLAY_SERVICE_ACCOUNT_JSON` | the service account's raw JSON key |

   From fish, without the values passing through your shell history:
   ```fish
   base64 -w0 push-app-upload.jks | gh secret set PUSH_APP_UPLOAD_KEYSTORE -R yschimke/watchface-tools
   gh secret set PUSH_APP_KEYSTORE_PASSWORD -R yschimke/watchface-tools   # prompts
   gh secret set PUSH_APP_KEY_ALIAS -R yschimke/watchface-tools -b upload
   gh secret set PUSH_APP_KEY_PASSWORD -R yschimke/watchface-tools        # prompts
   gh secret set PLAY_SERVICE_ACCOUNT_JSON -R yschimke/watchface-tools < service-account.json
   gh variable set PLAY_PUBLISH -R yschimke/watchface-tools -b true
   ```
   A publishing run fails early, naming any secret that is missing.
6. **Complete the Console forms** that Gradle Play Publisher doesn't manage: privacy policy, Data
   safety (the app stores a device token and talks to the site), content rating, app category, and
   internal testers. Wear OS store listings also need **Wear screenshots**; add them under
   `push-app/src/main/play/listings/en-GB/graphics/wear-screenshots/` (the README screenshots in
   `docs/screenshots/` are a starting point) or in the Console.

If Play still marks the app as a draft, finish its Console setup. Until then a `COMPLETED` release
from the API may be rejected.

## The upload key is not the watch face key

Two unrelated keys sign two unrelated things:

- The **upload key** above signs the push app's bundle for Play.
- The **marketplace key** signs every watch face APK the site pushes. wff-packager reads it from
  `WFF_SIGNING_*` (see [`wff-packager/README.md`](../wff-packager/README.md#signing-keys)), and the
  site's host provisions it.

Keep them separate: rotating or losing one must never touch the other.
