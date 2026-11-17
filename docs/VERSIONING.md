# Versioning and releases

watchface-tools publishes to Maven Central under group `ee.schimke.watchface`, on the same setup as
[compose-preview-contracts](https://github.com/yschimke/compose-preview-contracts): one version
line, a BOM, and a release that uploads only the modules that changed.

## One version line, release-please

`.release-please-manifest.json` is the LAST released version, never the next one.
release-please rewrites `watchfaceToolsVersion` in `gradle.properties` (between its
`x-release-please-*` markers) and the README snippet when it cuts a release. Builds are
`<version>-SNAPSHOT` unless run with `-Prelease`, which `release.yml` passes.

**The first release is `0.1.0`.** The manifest seeds `0.0.0` and there are no tags yet, and
release-please's `bump-minor-pre-major` only applies after a first release, so on its own its first
proposal would be `1.0.0`. `"initial-version": "0.1.0"` in `release-please-config.json` pins it. Remove
nothing from the manifest to "fix" this: writing `0.1.0` there says it is already out.

## What is published

| Coordinate | Module |
| --- | --- |
| `watchface-tools-bom` | `:bom`, a Gradle `java-platform` |
| `wff-core`, `wff-core-jvm`, `wff-core-wasm-js` | `:wff-core` (Kotlin Multiplatform) |
| `wff-packager` | `:wff-packager` |

`:push-app` is an app, not a library, and is never published. The modules are found, not listed: a
build script applying `watchface.maven-publishing` (from the `build-logic` included build) is
published, and `settings.gradle.kts` hands that list to `:bom`. Match it with its closing quote
(`watchface.maven-publishing")`): the platform plugin id `watchface.maven-publishing-platform` shares the
prefix, and a bare substring match makes the BOM constrain itself.

The BOM constrains every coordinate above, including a KMP module's `-jvm` and `-wasm-js` variants.
Each constraint carries the module's *effective* version (below), through the same
`PublishedVersions.resolve` that sets `project.version`, so the BOM and the POMs cannot disagree.

## A release publishes only what changed

Each coordinate-publish is about twenty files against a Central file-count limit shared by all of
yschimke's publishing repositories, so `release.yml` asks
[`.github/scripts/maven-publish-plan.sh`](../.github/scripts/maven-publish-plan.sh) which modules to
upload. A skipped module keeps the version it last published at; its POM and the BOM name it there.
The baseline is read from Maven Central (`maven-metadata.xml`) and written to an uncommitted
`publishing-manifest.json`. A module publishes when:

1. **something that ships from it changed** since the tag *it* last published at. Changes confined to
   its test source sets (`src/test`, `src/jvmTest`, ... anything ending in `Test`) do not count;
2. **a module it depends on publishes**, transitively (`wff-packager` depends on `wff-core`), so a
   POM never names a sibling version that was not uploaded; or
3. **a shared build input changed**: `build-logic/` (not its tests), `gradle/`, the wrapper,
   `settings.gradle.kts` or the root `build.gradle.kts`. The version catalog is diffed entry by
   entry instead: a changed alias dirties only the published modules whose build scripts name it.
   `push-app` publishes nothing, so an alias only it uses (and the root's `apply false` plugin
   declarations) dirties nothing. An alias used by build-logic, such as `kotlin`, publishes all.

**The BOM publishes whenever any module does**, at the tag version, and not at all when the plan is
empty. **Every uncertainty publishes**: Central refuses a second upload of a version, so a needless
publish costs quota while a wrongly skipped one cannot be repaired.

**Nothing is published yet, so the first release publishes everything.** A coordinate with no Central
baseline is "never published", and so is one the plan could not read.

**An empty plan is a real answer.** `-Pwatchface.publishSet=` (empty) lists no tasks and
`publish-to-central.sh` uploads nothing and succeeds. The property's *absence*, which is a
`workflow_dispatch` recovery run, means publish everything. The plan, `expected-poms.py` and
`publish-to-central.sh` are tested in `.github/scripts/tests/`, and `print-publish-tasks-test.sh` runs the
real `printPublishTasks`; `ci.yml` runs all of them.

## The release run

Merging the `chore(main): release X.Y.Z` PR is the whole release (`release-please.yml`, then
`release.yml`): check the tag against the manifest and `gradle.properties`, compute the plan, run
`check`, dry-run `publishToMavenLocal` and assert a **signed** POM for every expected coordinate, upload,
then un-draft the release. A published coordinate is permanent, so everything that can fail does so
first. The release PR also gets a comment listing what merging it would publish
(`release-pr-publish-plan.sh`), and `release-pr-race-guard.sh` keeps a release-PR pass from running
while a release is still a draft.

Required repository secrets: `SIGNING_KEY`, `SIGNING_KEY_ID`, `SIGNING_KEY_PASSWORD`,
`MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, and the `ee.schimke.watchface` namespace verified on
the Central Portal.
