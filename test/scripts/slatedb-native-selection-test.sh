#!/bin/sh

set -eu

project_root=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
fixture_root=$(mktemp -d)
trap 'rm -rf "$fixture_root"' EXIT HUP INT TERM

fixture_project="$fixture_root/project with spaces"
fixture_home="$fixture_root/home with spaces"
fixture_bin="$fixture_root/bin"
mkdir -p "$fixture_project/scripts" "$fixture_bin" "$fixture_root/content/darwin-aarch64"
cp "$project_root/scripts/init.sh" "$fixture_project/scripts/init.sh"

old_jar="$fixture_home/.m2/repository/io/slatedb/slatedb-uniffi/0.15.0/slatedb-uniffi-0.15.0.jar"
selected_jar="$fixture_home/.m2/repository/io/slatedb/slatedb-uniffi/0.17.0/slatedb-uniffi-0.17.0.jar"
mkdir -p "$(dirname "$old_jar")" "$(dirname "$selected_jar")"
printf 'old native library\n' > "$fixture_root/content/darwin-aarch64/libslatedb_uniffi.dylib"
jar --create --file "$old_jar" -C "$fixture_root/content" .
printf 'resolved native library\n' > "$fixture_root/content/darwin-aarch64/libslatedb_uniffi.dylib"
jar --create --file "$selected_jar" -C "$fixture_root/content" .

cat > "$fixture_bin/uname" <<'SH'
#!/bin/sh
case "$1" in
  -s) printf 'Darwin\n' ;;
  -m) printf 'arm64\n' ;;
  *) exit 1 ;;
esac
SH

cat > "$fixture_bin/clojure" <<'SH'
#!/bin/sh
set -eu
test "$1" = '-Spath'
test "$PWD" = "$FIXTURE_PROJECT"
printf 'src:%s:test\n' "$SELECTED_JAR"
SH
chmod +x "$fixture_bin/uname" "$fixture_bin/clojure"

env HOME="$fixture_home" PATH="$fixture_bin:$PATH" \
  FIXTURE_PROJECT="$fixture_project" SELECTED_JAR="$selected_jar" \
  sh "$fixture_project/scripts/init.sh"
cmp "$fixture_root/content/darwin-aarch64/libslatedb_uniffi.dylib" \
  "$fixture_project/libslatedb_uniffi.dylib"
printf 'SlateDB extraction uses the resolved JAR when cached versions coexist.\n'
