#!/usr/bin/env bash
#
# build_native.sh - compile libdfroot.so, libbootstrap.so and splicehelper.
#
# Runs on the device itself, under Termux's clang. The NDK proper is not
# required: Termux ships clang with an aarch64-linux-android target and a
# working sysroot, which is all these three binaries need.
#
# Must be run from an Android shell (adb shell / su), not from the proot
# container - the compiler and sysroot live in Termux's private data dir.
set -euo pipefail

# Root of the checkout, resolved from this script's location so the tree can
# live anywhere. DFROOT overrides it for the case where the script is copied
# elsewhere (it used to default to one hard-coded /sdcard path).
ROOT="${DFROOT:-$(cd "$(dirname "$0")/.." && pwd)}"
JNI="$ROOT/app/src/main/jni"
OUT="$ROOT/build/lib/arm64-v8a"

CLANG="${CLANG:-/data/data/com.termux/files/usr/bin/clang}"
STRIP="${STRIP:-/data/data/com.termux/files/usr/bin/llvm-strip}"

TARGET=aarch64-linux-android24
CFLAGS="-O2 -fPIE -Wall -Wextra"
# Termux's clang driver records its own library dir as RPATH on every link and
# there is no flag to suppress it. That path is unreadable from init's context,
# which is where bootstrap is exec'd, so the loader must not be pointed at it.
# LDFLAGS stays plain here; tools/strip_rpath.py rewrites the tag afterwards.
LDFLAGS="-pie -Wl,-z,max-page-size=16384"

say() { printf '\033[1;36m══  %s  ══\033[0m\n' "$*"; }
die() { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }

[ -x "$CLANG" ] || die "clang not found at $CLANG"
[ -d "$JNI/ko" ] || die "no modules in $JNI/ko"

WORK="$(mktemp -d /tmp/dfroot-native.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

# ---------------------------------------------------------------------------
say "generate module table"
# ---------------------------------------------------------------------------
# The generated .inc files are normally produced by the Linux/proot side,
# which has python3. Regenerate only when a generator is available; otherwise
# trust whatever was checked in alongside the sources.
if command -v python3 >/dev/null 2>&1; then
    python3 "$JNI/gen_kmi_inc.py" "$JNI"
else
    [ -f "$JNI/kmi_table.inc" ] && [ -f "$JNI/kmi_blobs.inc" ] \
        || die "kmi table missing and no python3 to generate it"
    echo "  python3 absent, using existing kmi_table.inc / kmi_blobs.inc"
fi

# ---------------------------------------------------------------------------
say "splicehelper"
# ---------------------------------------------------------------------------
# Freestanding: no libc, no startup files. It is written into crash_dump64's
# page cache one page at a time, so size is the whole point.
"$CLANG" --target="$TARGET" "$JNI/splicehelper.c" \
    -o "$JNI/splicehelper" \
    -nodefaultlibs -nostartfiles -ffreestanding -static
"$STRIP" "$JNI/splicehelper" 2>/dev/null || true
printf '  built splicehelper\n'

# ---------------------------------------------------------------------------
say "objects"
# ---------------------------------------------------------------------------
for src in exp.c ko_select.c elf_parser.c; do
    printf '  %s\n' "$src"
    "$CLANG" -c $CFLAGS -I"$JNI" "$JNI/$src" -o "$WORK/${src%.c}.o"
done
printf '  libcxx.S\n'
"$CLANG" -c -I"$JNI" "$JNI/libcxx.S" -o "$WORK/libcxx.o"

# ---------------------------------------------------------------------------
say "link"
# ---------------------------------------------------------------------------
"$CLANG" -o "$OUT/libdfroot.so" \
    "$WORK/exp.o" "$WORK/ko_select.o" "$WORK/elf_parser.o" "$WORK/libcxx.o" \
    -llog $LDFLAGS

"$CLANG" $CFLAGS "$JNI/bootstrap.c" -o "$OUT/libbootstrap.so" $LDFLAGS

"$STRIP" --strip-unneeded "$OUT/libdfroot.so" "$OUT/libbootstrap.so" 2>/dev/null || true

# RPATH cleanup happens on the packaging side (tools/build_apk.sh), which has
# python3; the Android shell this script runs in does not.

say "done"
ls -la "$OUT"