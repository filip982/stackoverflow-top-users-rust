#!/usr/bin/env bash
# Optional Linux gate for the iOS app's platform-neutral Swift (no Xcode needed).
#
# Builds a throwaway SwiftPM package from the real sources — generated UniFFI bindings + FFI
# aliases (module SoCore, Swift 5 mode), the gateway/MVI stores/contracts/configuration
# (module StackOverflowUsers, Swift 6 strict concurrency) and the whole unit-test target — links
# the host so-core cdylib and runs the XCTest suite, contract tests included.
# SwiftUI views, the App entry point and XCUITests are NOT covered (Apple SDKs only); a two-symbol
# Combine shim stands in for ObservableObject/@Published.
#
# Requires a Swift 6 toolchain on PATH (swift.org Linux tarball) + cargo.
# Usage: apps/ios/scripts/linux-swift-check.sh [extra `swift test` args]
set -euo pipefail

IOS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$IOS_DIR/../.." && pwd)"
RUST_DIR="$REPO_ROOT/core/rust"
WORK="${LINUX_CHECK_DIR:-${TMPDIR:-/tmp}/so-ios-linux-check}"

command -v swift >/dev/null || { echo "swift not on PATH (install a Swift 6 Linux toolchain)" >&2; exit 1; }

BINDINGS="$WORK/bindings"
rm -rf "$WORK/Sources" "$WORK/Tests" "$BINDINGS"
BINDINGS_OUT="$BINDINGS" BINDINGS_LANGUAGES=swift "$RUST_DIR/scripts/generate-bindings.sh" >/dev/null

mkdir -p "$WORK"/Sources/{SoCoreFFI,SoCore,Combine,StackOverflowUsers} "$WORK/Tests/StackOverflowUsersTests"
cp "$BINDINGS/swift/SoCoreFFI.h" "$WORK/Sources/SoCoreFFI/"
printf 'module SoCoreFFI {\n    header "SoCoreFFI.h"\n    export *\n}\n' > "$WORK/Sources/SoCoreFFI/module.modulemap"
cp "$BINDINGS/swift/SoCore.swift" "$IOS_DIR/SoCoreSupport/FFIAliases.swift" "$WORK/Sources/SoCore/"
cat > "$WORK/Sources/Combine/Combine.swift" <<'EOF'
// Linux typecheck shim for the two Combine symbols the stores use.
public protocol ObservableObject: AnyObject {}
@propertyWrapper public struct Published<Value> {
    public var wrappedValue: Value
    public init(wrappedValue: Value) { self.wrappedValue = wrappedValue }
}
EOF

# Everything in the app target that doesn't import SwiftUI.
APP="$IOS_DIR/StackOverflowUsers"
grep -rL --include='*.swift' 'import SwiftUI' "$APP" | while read -r f; do cp "$f" "$WORK/Sources/StackOverflowUsers/"; done
find "$IOS_DIR/StackOverflowUsersTests" -name '*.swift' -exec cp {} "$WORK/Tests/StackOverflowUsersTests/" \;

LIB_DIR="$RUST_DIR/target/debug"
cat > "$WORK/Package.swift" <<EOF
// swift-tools-version:6.0
import PackageDescription
let package = Package(
    name: "so-ios-linux-check",
    targets: [
        .systemLibrary(name: "SoCoreFFI", path: "Sources/SoCoreFFI"),
        .target(
            name: "SoCore", dependencies: ["SoCoreFFI"], swiftSettings: [.swiftLanguageMode(.v5)],
            linkerSettings: [.unsafeFlags(["-L$LIB_DIR", "-lso_core", "-Xlinker", "-rpath", "-Xlinker", "$LIB_DIR"])]
        ),
        .target(name: "Combine"),
        .target(name: "StackOverflowUsers", dependencies: ["SoCore", "Combine"]),
        .testTarget(name: "StackOverflowUsersTests", dependencies: ["StackOverflowUsers", "SoCore"]),
    ]
)
EOF

cd "$WORK"
# Warnings from SwiftPM's generated Linux test-discovery file are expected and not ours.
swift build --build-tests 2>&1 | grep -E "(error|warning):" | grep -v -e '<unknown>' -e '\.derived/' || true
swift test "$@"
