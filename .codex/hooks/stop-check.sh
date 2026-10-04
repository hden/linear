#!/bin/sh

block() {
  printf '%s\n' '{"decision":"block","reason":"The required static checks did not pass. Inspect the diagnostics from `docker compose run --rm --no-deps -T -i=false lint bb lint` and fix the reported source violations. If tooling is unavailable, prepare it with `docker compose build app`; incomplete checks are not a pass."}'
}

root=$(git rev-parse --show-toplevel 2>/dev/null) || { block; exit 0; }
cd "$root" || { block; exit 0; }

if ! docker compose run --rm --no-deps -T -i=false lint timeout 30s bb lint >&2; then
  block
  exit 0
fi

printf '%s\n' '{"continue":true}'
