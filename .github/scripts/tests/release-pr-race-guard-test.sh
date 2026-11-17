#!/usr/bin/env bash
# Drives release-pr-race-guard.sh in check mode against a fake `gh`.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
guard="$here/../release-pr-race-guard.sh"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
fail=0

run() { # $1 = manifest version, $2 = release draft answer, $3 = minutes since the manifest bump
  cat > "$tmp/gh" <<FAKE
#!/usr/bin/env bash
case "\$*" in
  *contents/.release-please-manifest.json*) printf '{".": "%s"}' "$1" ;;
  *releases/tags/*) [ -n "$2" ] && echo "$2" || exit 1 ;;
  *commits\?sha=*) date -u -d "-$3 minutes" +%Y-%m-%dT%H:%M:%SZ ;;
esac
FAKE
  chmod +x "$tmp/gh"
  : > "$tmp/out"
  PATH="$tmp:$PATH" GITHUB_REPOSITORY=o/r GITHUB_OUTPUT="$tmp/out" MODE=check "$guard" > /dev/null
  sed -n 's/^in_flight=//p' "$tmp/out"
}

check() { # $1 = name, $2 = expected, $3 = actual
  if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: expected $2, got $3"; fail=1; fi
}

check "bootstrap 0.0.0 with a fresh manifest is not in flight" false "$(run 0.0.0 "" 5)"
check "unpublished release bumped 5m ago is in flight" true "$(run 0.2.0 "" 5)"
check "unpublished release bumped 500m ago is stranded" false "$(run 0.2.0 "" 500)"
check "published release is not in flight" false "$(run 0.2.0 false 5)"
exit "$fail"
