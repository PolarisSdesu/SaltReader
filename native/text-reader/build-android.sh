#!/usr/bin/env bash
# Cross-compiles libtext_reader.so for Android (arm64-v8a + x86_64) and copies it into jniLibs.
# Requires: rustup targets aarch64-linux-android / x86_64-linux-android, Android NDK.
set -euo pipefail
cd "$(dirname "$0")"
# Auto-detect NDK + host prebuilt tag (Apple Silicon / Linux / macOS Intel).
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
NDK_ROOT="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK_ROOT" ]]; then
  NDK_ROOT="$(ls -d "$SDK_ROOT"/ndk/* 2>/dev/null | sort -V | tail -n 1 || true)"
fi
if [[ -z "$NDK_ROOT" || ! -d "$NDK_ROOT" ]]; then
  echo "error: Android NDK not found (set ANDROID_NDK_HOME)" >&2
  exit 1
fi
case "$(uname -s)" in
  Darwin) HOST_TAG="darwin-x86_64" ;;
  Linux) HOST_TAG="linux-x86_64" ;;
  *) echo "error: unsupported host $(uname -s)" >&2; exit 1 ;;
esac
NDK="$NDK_ROOT/toolchains/llvm/prebuilt/$HOST_TAG"
if [[ ! -x "$NDK/bin/aarch64-linux-android26-clang" ]]; then
  echo "error: clang not found under $NDK/bin" >&2
  exit 1
fi
export CC_aarch64_linux_android="$NDK/bin/aarch64-linux-android26-clang"
export AR_aarch64_linux_android="$NDK/bin/llvm-ar"
export CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER="$CC_aarch64_linux_android"
export CC_x86_64_linux_android="$NDK/bin/x86_64-linux-android26-clang"
export AR_x86_64_linux_android="$NDK/bin/llvm-ar"
export CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER="$CC_x86_64_linux_android"
cargo build --release --locked --target aarch64-linux-android --target x86_64-linux-android
ROOT="$(cd ../.. && pwd)"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a" "$ROOT/app/src/main/jniLibs/x86_64"
cp target/aarch64-linux-android/release/libtext_reader.so "$ROOT/app/src/main/jniLibs/arm64-v8a/"
cp target/x86_64-linux-android/release/libtext_reader.so "$ROOT/app/src/main/jniLibs/x86_64/"
echo "copied libtext_reader.so to app/src/main/jniLibs"
