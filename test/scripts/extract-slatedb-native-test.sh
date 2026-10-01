#!/bin/sh

set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
slatedb_jar=$(cd "$project_root" && clojure -Spath | tr ':' '\n' \
  | awk '/\/slatedb-uniffi-[^/]+\.jar$/ { print; exit }')

case "$(uname -m)" in
  arm64 | aarch64) platform=darwin-aarch64 ;;
  x86_64) platform=darwin-x86-64 ;;
  *)
    printf 'Unsupported macOS architecture: %s\n' "$(uname -m)" >&2
    exit 1
    ;;
esac

expected_library=$(mktemp)
trap 'rm -f "$expected_library"' EXIT

unzip -p "$slatedb_jar" "$platform/libslatedb_uniffi.dylib" > "$expected_library"
cmp "$expected_library" "$project_root/libslatedb_uniffi.dylib"
