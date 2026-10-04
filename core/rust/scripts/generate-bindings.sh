#!/usr/bin/env bash
# Generates Kotlin + Swift UniFFI bindings for so-core into core/rust/bindings/
# (gitignored) and fails if generation fails or expected outputs are missing.
#
# Usage: core/rust/scripts/generate-bindings.sh [--release]
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

OUT="$ROOT/bindings"
rm -rf "$OUT"
mkdir -p "$OUT/kotlin" "$OUT/swift"

cargo build -p so-core --lib "${CARGO_FLAGS[@]}"

for LANGUAGE in kotlin swift; do
  cargo run -q -p so-core --features cli --bin uniffi-bindgen "${CARGO_FLAGS[@]}" -- \
    generate --no-format --library "$LIB" --language "$LANGUAGE" --out-dir "$OUT/$LANGUAGE"
done

check() {
  local file="$1" needle="$2"
  [[ -s "$file" ]] || { echo "missing or empty: $file" >&2; exit 1; }
  grep -Eq "$needle" "$file" || { echo "'$needle' not found in $file" >&2; exit 1; }
}

KOTLIN="$OUT/kotlin/com/filip982/socore/so_core.kt"
check "$KOTLIN" "suspend fun .getTopUsers."
check "$KOTLIN" "fun .newCore."
check "$KOTLIN" "interface FollowObserver"
SWIFT="$OUT/swift/SoCore.swift"
check "$SWIFT" "func getTopUsers\(\) ?async throws"
check "$SWIFT" "public func newCore\(baseUrl: String, storagePath: String\)"
check "$SWIFT" "public protocol FollowObserver"
check "$OUT/swift/SoCoreFFI.h" "uniffi_so_core_fn_func_new_core"
check "$OUT/swift/SoCoreFFI.modulemap" "SoCoreFFI"

echo "Bindings generated in $OUT:"
find "$OUT" -type f | sed "s|$ROOT/||" | sort
