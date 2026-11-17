# AGENTS.md

- Git history attributes work only to the human committer. Never add an AI `Co-authored-by:`
  trailer or use an agent identity as author or committer, in a commit or in a PR title or body.
- Branch names use `agent/...`.
- Commit subjects and PR titles use Conventional Commits.
- Run `./gradlew ktfmtFormat` before committing Kotlin changes, through `build-brief`.
- Merge only when explicitly requested.
