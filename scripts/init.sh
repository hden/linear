#!/bin/sh

set -eu

if [ "$(uname -s)" != "Darwin" ]; then
  printf 'SlateDB native library extraction is only supported on macOS.\n' >&2
  exit 1
fi

case "$(uname -m)" in
  arm64 | aarch64) platform=darwin-aarch64 ;;
  x86_64) platform=darwin-x86-64 ;;
  *)
    printf 'Unsupported macOS architecture: %s\n' "$(uname -m)" >&2
    exit 1
    ;;
esac

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
slatedb_jar=$(cd "$project_root" && clojure -Spath | tr ':' '\n' \
  | awk '/\/slatedb-uniffi-[^/]+\.jar$/ { print; exit }')

if [ -z "$slatedb_jar" ]; then
  printf 'SlateDB UniFFI JAR was not found on the resolved classpath.\n' >&2
  exit 1
fi

temporary_library=$(mktemp "$project_root/.libslatedb_uniffi.dylib.XXXXXX")
trap 'rm -f "$temporary_library"' EXIT HUP INT TERM

unzip -p "$slatedb_jar" "$platform/libslatedb_uniffi.dylib" > "$temporary_library"
mv "$temporary_library" "$project_root/libslatedb_uniffi.dylib"
