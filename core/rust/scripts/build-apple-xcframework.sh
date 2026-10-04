#!/usr/bin/env bash
# Packages so-core for the iOS app (docs/REWRITE_PLAN.md §9.5, repo B: prebuilt XCFramework).
# macOS only (needs xcodebuild + the Apple SDKs).
#
# Usage: core/rust/scripts/build-apple-xcframework.sh
#
# Steps:
#   1. cargo build -p so-core --lib --release for aarch64-apple-ios (device) and
#      aarch64-apple-ios-sim (Apple Silicon simulator). Intel simulators are out of scope.
#   2. Swift bindings via scripts/generate-bindings.sh (in-repo uniffi-bindgen, host dylib).
#   3. xcodebuild -create-xcframework over the two static libraries.
#
# Output layout ($APPLE_OUT, default core/rust/build/apple — gitignored):
#   SoCore.xcframework/            libso_core.a slices: ios-arm64, ios-arm64-simulator
#   include/SoCoreFFI.h            C FFI header (generated)
#   include/module.modulemap       clang module `SoCoreFFI` (renamed from SoCoreFFI.modulemap)
#   Sources/SoCore/SoCore.swift    generated Swift API (module `SoCore`)
#
# The C module is deliberately NOT embedded in the XCFramework: Xcode copies XCFramework headers
# into a shared include dir, and a second definition of `SoCoreFFI` on the search path would be a
# module redefinition error. apps/ios/project.yml points SWIFT_INCLUDE_PATHS at include/ instead.
#
# Environment overrides:
#   APPLE_OUT                    output root (see above)
#   IPHONEOS_DEPLOYMENT_TARGET   min iOS for the Rust objects (default 17.0, matches the app)
set -euo pipefail

if [[ "$(uname -s)" != Darwin ]]; then
  echo "build-apple-xcframework.sh: macOS only (needs xcodebuild + iOS SDKs)" >&2
  exit 1
fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OUT="${APPLE_OUT:-$ROOT/build/apple}"
TARGETS="aarch64-apple-ios aarch64-apple-ios-sim"
export IPHONEOS_DEPLOYMENT_TARGET="${IPHONEOS_DEPLOYMENT_TARGET:-17.0}"

INSTALLED="$(rustup target list --installed 2>/dev/null || true)"
for TARGET in $TARGETS; do
  if ! grep -qx "$TARGET" <<<"$INSTALLED"; then
    echo "missing Rust target $TARGET — run: rustup target add aarch64-apple-ios aarch64-apple-ios-sim" >&2
    exit 1
  fi
done

for TARGET in $TARGETS; do
  echo "==> cargo build so-core ($TARGET, release)"
  cargo build -p so-core --lib --release --target "$TARGET"
  test -s "target/$TARGET/release/libso_core.a"
done

BINDINGS_TMP="$(mktemp -d)"
trap 'rm -rf "$BINDINGS_TMP"' EXIT
echo "==> Swift bindings"
BINDINGS_OUT="$BINDINGS_TMP" BINDINGS_LANGUAGES=swift "$ROOT/scripts/generate-bindings.sh"

echo "==> Assembling $OUT"
rm -rf "${OUT:?}"
mkdir -p "$OUT/include" "$OUT/Sources/SoCore"
cp "$BINDINGS_TMP/swift/SoCoreFFI.h" "$OUT/include/SoCoreFFI.h"
cp "$BINDINGS_TMP/swift/SoCoreFFI.modulemap" "$OUT/include/module.modulemap"
cp "$BINDINGS_TMP/swift/SoCore.swift" "$OUT/Sources/SoCore/SoCore.swift"

xcodebuild -create-xcframework \
  -library "target/aarch64-apple-ios/release/libso_core.a" \
  -library "target/aarch64-apple-ios-sim/release/libso_core.a" \
  -output "$OUT/SoCore.xcframework"

test -f "$OUT/SoCore.xcframework/Info.plist"
echo "Apple artifacts in $OUT:"
find "$OUT" -maxdepth 3 | sed "s|$ROOT/||" | sort
