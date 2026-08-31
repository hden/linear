#!/bin/sh

case "$(cat)" in
  *'"stop_hook_active":true'* | *'"stop_hook_active": true'*)
    printf '%s\n' '{"continue":true}'
    exit 0
    ;;
esac

root=$(git rev-parse --show-toplevel 2>/dev/null) || exit 0
cd "$root" || exit 0

if grep -RIn --binary-files=without-match \
  --exclude='AGENTS.md' \
  --exclude='stop-check.sh' \
  'with-redefs' src test >&2; then
  printf '%s\n' '{"decision":"block","reason":"Remove `with-redefs` from src/ and test/."}'
  exit 0
fi

if ! bb lint >&2 || ! bb check >&2 || ! bb format:check >&2; then
  printf '%s\n' '{"decision":"block","reason":"Fix `bb lint`, `bb check`, and `bb format:check` before stopping."}'
  exit 0
fi

printf '%s\n' '{"continue":true}'
