# AGENTS.md

Rules for every coding agent working in this repository.

- **Git history must never attribute work to an AI.** No `Co-authored-by:` trailer naming an agent
  (Claude, Codex, ChatGPT, Copilot, Gemini) or using an agent email, and no agent as author or
  committer identity. Commits are attributed to the human committer only. Links to an agent session
  are fine. Scrub the PR title and body too.
- **Branch names are always `agent/…`** (never `claude/…`, `codex/…`, …).
- **Conventional Commits** for commit subjects and PR titles (`feat:`, `fix:`, `docs:`, `test:`,
  `build:`, `ci:`, `chore:`). Use `feat:`/`fix:` only for changes that affect the published library.
- **Run `./gradlew ktfmtFormat` before committing** Kotlin changes and stage the result.
- Run Gradle through `build-brief` when it is installed (`build-brief ./gradlew :wff-core:jvmTest`).
- `wff-core` `commonMain` must stay free of JVM-only code and of dependencies: the browser editor
  runs it under Kotlin/Wasm. Check with `./gradlew :wff-core:compileKotlinWasmJs`.
- Schema entries whose version or shape is not certain carry a `// verify` comment; keep it until
  checked against Google's XSD.
