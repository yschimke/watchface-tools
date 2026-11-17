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

- `release-please` owns the version: `.release-please-manifest.json` is the LAST released version,
  and it rewrites `watchfaceToolsVersion` in `gradle.properties` between the
  `x-release-please-*` markers. Never edit either by hand. Builds are `-SNAPSHOT` unless run with
  `-Prelease`.
- Merging the release PR publishes `wff-core` and `wff-packager` to Maven Central. A published
  version is permanent, so only merge it when the user asks.
- Publishing needs five repository secrets: `SIGNING_KEY`, `SIGNING_KEY_ID`,
  `SIGNING_KEY_PASSWORD`, `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`.
