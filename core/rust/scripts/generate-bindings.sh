#!/usr/bin/env bash
# Generates Kotlin + Swift UniFFI bindings for so-core into core/rust/bindings/
# (gitignored) and fails if generation fails or expected outputs are missing.
#
# Usage: core/rust/scripts/generate-bindings.sh [--release]
#
# Environment overrides (used by the Android Gradle build):
#   BINDINGS_OUT        output root (default: core/rust/bindings); each language
#                       is written to $BINDINGS_OUT/<language>
#   BINDINGS_LANGUAGES  space-separated subset of "kotlin swift" (default: both)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROFILE=debug
CARGO_FLAGS=()
if [[ "${1:-}" == "--release" ]]; then
  PROFILE=release
  CARGO_FLAGS+=(--release)
fi

case "$(uname -s)" in
  Darwin) LIB="target/$PROFILE/libso_core.dylib" ;;
  *)      LIB="target/$PROFILE/libso_core.so" ;;
esac

OUT="${BINDINGS_OUT:-$ROOT/bindings}"
LANGUAGES="${BINDINGS_LANGUAGES:-kotlin swift}"

cargo build -p so-core --lib ${CARGO_FLAGS[@]+"${CARGO_FLAGS[@]}"}

for LANGUAGE in $LANGUAGES; do
  case "$LANGUAGE" in
    kotlin|swift) ;;
    *) echo "unsupported language: $LANGUAGE" >&2; exit 2 ;;
  esac
  rm -rf "${OUT:?}/$LANGUAGE"
  mkdir -p "$OUT/$LANGUAGE"
  cargo run -q -p so-core --features cli --bin uniffi-bindgen ${CARGO_FLAGS[@]+"${CARGO_FLAGS[@]}"} -- \
    generate --no-format --library "$LIB" --language "$LANGUAGE" --out-dir "$OUT/$LANGUAGE"
done

check() {
  local file="$1" needle="$2"
  [[ -s "$file" ]] || { echo "missing or empty: $file" >&2; exit 1; }
  grep -Eq "$needle" "$file" || { echo "'$needle' not found in $file" >&2; exit 1; }
}

for LANGUAGE in $LANGUAGES; do
  if [[ "$LANGUAGE" == kotlin ]]; then
    KOTLIN="$OUT/kotlin/com/filip982/socore/so_core.kt"
    check "$KOTLIN" "suspend fun .getTopUsers."
    check "$KOTLIN" "fun .newCore."
    check "$KOTLIN" "interface FollowObserver"
    check "$KOTLIN" "fun .sortUsers."
    check "$KOTLIN" "enum class SortField"
  else
    SWIFT="$OUT/swift/SoCore.swift"
    check "$SWIFT" "func getTopUsers\(\) ?async throws"
    check "$SWIFT" "public func newCore\(baseUrl: String, storagePath: String\)"
    check "$SWIFT" "public protocol FollowObserver"
    check "$SWIFT" "public func sortUsers\(users: \[User\], field: SortField, direction: SortDirection\)"
    check "$OUT/swift/SoCoreFFI.h" "uniffi_so_core_fn_func_new_core"
    check "$OUT/swift/SoCoreFFI.modulemap" "SoCoreFFI"
  fi
done

echo "Bindings generated in $OUT:"
find "$OUT" -type f | sed "s|$ROOT/||" | sort
