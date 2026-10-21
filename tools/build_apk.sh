#!/usr/bin/env bash
#
# build_apk.sh - assemble and sign DFRoot.apk without Gradle.
#
# The project is normally built with Gradle, but this script exists because
# the APK is small enough that the whole pipeline fits in one screen, and
# because a machine with only an SDK (no Gradle daemon, no NDK) can still
# produce a working build. Native binaries are expected to be prebuilt in
# build/lib/arm64-v8a/ by build_native.sh.
#
# Requires: javac 17, an Android SDK with platforms/android-35/android.jar,
#           r8.jar (ships with cmdline-tools), aapt2, apksigner, zipalign.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$ROOT/app"
OUT="$ROOT/out"
WORK="${BUILD_DIR:-/tmp/dfroot-apkbuild}"

SDK="${ANDROID_SDK:-/root/android-sdk}"
AJ="$SDK/platforms/android-35/android.jar"
R8="$SDK/cmdline-tools/latest/lib/r8.jar"

# aapt2 is an x86-64 build here, so it runs under qemu.
AAPT2="${AAPT2:-qemu-x86_64-static /tmp/aapt_extract/prebuilt/linux/aapt2_64}"
APKSIGNER="${APKSIGNER:-/tmp/apksigner.jar}"
ZIPALIGN="${ZIPALIGN:-/usr/lib/android-sdk/build-tools/debian/zipalign}"

KS="$ROOT/keystore.jks"
KS_PASS=df-ksu
KS_ALIAS=df-ksu

say() { printf '\033[1;36m══  %s  ══\033[0m\n' "$*"; }
die() { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }

for f in "$AJ" "$R8" "$APKSIGNER"; do
    [ -e "$f" ] || die "missing $f"
done
[ -d "$ROOT/build/lib/arm64-v8a" ] || die "no native libs; run build_native.sh first"
[ -f "$ROOT/build/lib/arm64-v8a/libdfroot.so" ] || die "libdfroot.so not built"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/lib/arm64-v8a" "$OUT"

# ---------------------------------------------------------------------------
say "javac"
# ---------------------------------------------------------------------------
javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJ" -d "$WORK/classes" \
    "$APP"/src/main/java/df/root/*.java 2>&1 | grep -v 'bootstrap class path' || true

# ---------------------------------------------------------------------------
say "d8"
# ---------------------------------------------------------------------------
find "$WORK/classes" -name '*.class' -print0 \
    | xargs -0 java -cp "$R8" com.android.tools.r8.D8 \
        --lib "$AJ" --min-api 31 --output "$WORK"

# ---------------------------------------------------------------------------
say "aapt2 link"
# ---------------------------------------------------------------------------
# Resources are compiled to .flat first; `link -R <dir>` is not a thing —
# -R takes a single compiled file, so the directory has to be flattened
# into one here.
mkdir -p "$WORK/res"
if [ -d "$APP/src/main/res" ]; then
    $AAPT2 compile --dir "$APP/src/main/res" -o "$WORK/res/res.zip"
    RES_ARGS=( -R "$WORK/res/res.zip" )
else
    RES_ARGS=()
fi

$AAPT2 link -o "$WORK/base.apk" \
    -I "$AJ" \
    --manifest "$APP/src/main/AndroidManifest.xml" \
    "${RES_ARGS[@]}" \
    --min-sdk-version 31 \
    --target-sdk-version 35 \
    --version-code "${VERSION_CODE:-1}" \
    --version-name "${VERSION_NAME:-1.0}"

# ---------------------------------------------------------------------------
say "package"
# ---------------------------------------------------------------------------
# Native binaries are linked by Termux's clang, which stamps its own library
# path into them. Resources from /data/data/com.termux are not reachable from
# init's context, where bootstrap runs, so the tag has to be removed before
# packaging or the binary dies with a bare exit status 1.
python3 "$ROOT/tools/strip_rpath.py" "$ROOT"/build/lib/arm64-v8a/*.so

cp "$ROOT"/build/lib/arm64-v8a/*.so "$WORK/lib/arm64-v8a/"
(cd "$WORK" && python3 - <<'PY'
import zipfile, shutil, os
shutil.copy('base.apk', 'packed.apk')
with zipfile.ZipFile('packed.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('classes.dex', 'classes.dex')
    for f in sorted(os.listdir('lib/arm64-v8a')):
        z.write(os.path.join('lib/arm64-v8a', f), 'lib/arm64-v8a/' + f)
PY
)

# ---------------------------------------------------------------------------
say "zipalign"
# ---------------------------------------------------------------------------
"$ZIPALIGN" -f 4 "$WORK/packed.apk" "$WORK/aligned.apk"

# ---------------------------------------------------------------------------
say "sign"
# ---------------------------------------------------------------------------
if [ ! -f "$KS" ]; then
    keytool -genkeypair -keystore "$KS" -storetype JKS \
        -storepass "$KS_PASS" -keypass "$KS_PASS" \
        -alias "$KS_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=DFRoot"
fi

java -jar "$APKSIGNER" sign \
    --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --min-sdk-version 31 \
    --out "$OUT/DFRoot.apk" "$WORK/aligned.apk"

java -jar "$APKSIGNER" verify --print-certs "$OUT/DFRoot.apk" | head -2

say "done"
ls -la "$OUT/DFRoot.apk"