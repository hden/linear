#!/bin/sh

set -eu

if [ -n "${SQLITE_LIBRARY:-}" ]; then
  sqlite_library=$SQLITE_LIBRARY
elif [ "$(uname -s)" = Darwin ]; then
  sqlite_library="$(brew --prefix sqlite)/lib/libsqlite3.dylib"
elif [ "$(uname -s)" = Linux ]; then
  sqlite_library=$(ldconfig -p 2>/dev/null | awk '/libsqlite3\.so\.0/ {print $NF; exit}')
else
  printf 'Set SQLITE_LIBRARY to an absolute SQLite library path.\n' >&2
  exit 1
fi

if [ -z "${sqlite_library:-}" ] || [ ! -f "$sqlite_library" ]; then
  printf 'SQLite library was not found: %s\n' "${sqlite_library:-unset}" >&2
  exit 1
fi

SQLITE_LIBRARY=$sqlite_library exec clojure -J--enable-native-access=ALL-UNNAMED "$@"
