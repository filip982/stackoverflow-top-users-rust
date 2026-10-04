#!/usr/bin/env bash
# One-shot iOS setup (macOS): builds the Rust core XCFramework + Swift bindings, then generates
# StackOverflowUsers.xcodeproj with XcodeGen. Re-run after any change under core/rust/so-core.
#
# Usage: apps/ios/scripts/bootstrap.sh [--skip-core]
#   --skip-core   only regenerate the Xcode project (core/rust/build/apple must already exist)
set -euo pipefail

IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$IOS_DIR/../.." && pwd)"

if [[ "${1:-}" != "--skip-core" ]]; then
  "$REPO_ROOT/core/rust/scripts/build-apple-xcframework.sh"
fi

if [[ ! -f "$REPO_ROOT/core/rust/build/apple/Sources/SoCore/SoCore.swift" ]]; then
  echo "missing core/rust/build/apple — run without --skip-core first" >&2
  exit 1
fi

command -v xcodegen >/dev/null || { echo "xcodegen not found — brew install xcodegen" >&2; exit 1; }
cd "$IOS_DIR"
xcodegen generate
echo "Generated $IOS_DIR/StackOverflowUsers.xcodeproj"
