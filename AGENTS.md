# AGENTS.md

Rules for every coding agent working in this repository.

- **Git history must never attribute work to an AI.** No `Co-authored-by:` trailer naming an agent
  (Claude, Codex, ChatGPT, Copilot, Gemini) or using an agent email, and no agent as author or
  committer identity. Commits are attributed to the human committer only. Links to an agent session
  are fine. Scrub the PR title and body too.
- Install the local hooks once per clone with `scripts/install-git-hooks.sh`; CI's
  `Reject agent attribution` check runs the same detector.
- **Branch names are always `agent/…`** (never `claude/…`, `codex/…`, …).
- **Conventional Commits** for commit subjects and PR titles (`feat:`, `fix:`, `docs:`, `test:`,
  `build:`, `ci:`, `chore:`). Use `feat:`/`fix:` only for changes that affect the published library.
- **Run `./gradlew ktfmtFormat` before committing** Kotlin changes and stage the result.
- Run Gradle through `build-brief` when it is installed (`build-brief ./gradlew :wff-core:jvmTest`).
- `wff-core` `commonMain` must stay free of JVM-only code and of dependencies: the browser editor
  runs it under Kotlin/Wasm. Check with `./gradlew :wff-core:compileKotlinWasmJs`.
- `WffSchemaData.kt` is generated from Google's XSDs; never edit it by hand. Regenerate it with
  `tools/wff-xsd/generate_schema.py` (see `tools/wff-xsd/README.md`) when a new format version ships.
- **Merge only when explicitly requested.** Opening and updating PRs is automatic; merging is not.

## Review guidelines

- **Do not report commit author, committer or `Co-authored-by:` findings.** A review sandbox applies
  the pull request as a new commit under its own identity (for example `Codex`), so an identity read
  from a local `git log` is the reviewer's, not the author's. Attribution is a human and CI concern;
  leave it out of reviews entirely.

## Releasing

Set up like compose-preview-contracts; the rules and rationale are in
[`docs/VERSIONING.md`](docs/VERSIONING.md).

- `release-please` owns the version: `.release-please-manifest.json` is the LAST released version,
  and it rewrites `watchfaceToolsVersion` in `gradle.properties` between the
  `x-release-please-*` markers. Never edit either by hand. Builds are `-SNAPSHOT` unless run with
  `-Prelease`. Its first release is `0.1.0` (`initial-version` in `release-please-config.json`).
- Publishing is `build-logic` (`watchface.maven-publishing`, `watchface.maven-publishing-platform`).
  A new published module applies the plugin in its build script (matched with its closing quote) and
  sets `description`; `:bom` picks it up. `:push-app` is never published.
- A release uploads only the modules the plan says changed, plus the BOM
  (`.github/scripts/maven-publish-plan.sh`, `-Pwatchface.publishSet`). Changing the plan, the
  publish plugins or the release workflows means running `python3 -m unittest discover -s
  .github/scripts/tests`, `./gradlew -p build-logic test` and
  `.github/scripts/tests/print-publish-tasks-test.sh`.
- Merging the release PR publishes to Maven Central. A published version is permanent, so only
  merge it when the user asks.
- Publishing needs five repository secrets: `SIGNING_KEY`, `SIGNING_KEY_ID`,
  `SIGNING_KEY_PASSWORD`, `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`.
- APK signing keys are never committed or generated per machine for anything published. A
  marketplace signs every face with one shared key from a secret
  (`SigningConfig.fromEnvironment`, `WFF_SIGNING_*`) and pins its certificate
  (`expectedCertificateSha256`); see `wff-packager/README.md` → Signing keys. These are distinct
  from the Maven `SIGNING_KEY*` GPG secrets above.
- The push app ships to Google Play's internal track from the same release
  (`play-release.yml`, called by `release-please.yml`), opt-in through the `PLAY_PUBLISH` repository
  variable. Its version code follows `watchfaceToolsVersion`; setup and secrets are in
  [`docs/PLAY_PUBLISHING.md`](docs/PLAY_PUBLISHING.md).
