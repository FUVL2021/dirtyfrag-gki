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
# Keystore path and password come from outside the tree: a release key sitting
# on shared storage with its password inlined in this script is readable by
# anything that can read /sdcard. There is deliberately no built-in fallback
# password - a silent default is how a release ends up signed with a key whose
# passphrase is in the repo.
KS_SECRETS="${DFROOT_SECRETS:-$HOME/.dfroot-secrets}"
if [ -f "$KS_SECRETS/keystore.jks" ]; then
    KS="$KS_SECRETS/keystore.jks"
fi
if [ -n "${DFROOT_KS_PASS:-}" ]; then
    KS_PASS="$DFROOT_KS_PASS"
elif [ -f "$KS_SECRETS/password" ]; then
    KS_PASS="$(cat "$KS_SECRETS/password")"
else
    KS_PASS=""
fi
KS_ALIAS="${DFROOT_KS_ALIAS:-df-ksu}"

say() { printf '\033[1;36m══  %s  ══\033[0m\n' "$*"; }
die() { printf '\033[1;31merror: %s\033[0m\n' "$*" >&2; exit 1; }

for f in "$AJ" "$R8" "$APKSIGNER"; do
    [ -e "$f" ] || die "missing $f"
done
[ -d "$ROOT/build/lib/arm64-v8a" ] || die "no native libs; run build_native.sh first"
[ -f "$ROOT/build/lib/arm64-v8a/libdfroot.so" ] || die "libdfroot.so not built"

# Regenerate the KMI tables and KmiModules.java from jni/ko/. The Makefile
# does this, but this script is the path actually used to produce releases, and
# a stale KmiModules.java means the settings screen lists the wrong modules.
if command -v python3 >/dev/null 2>&1; then
    python3 "$APP/src/main/jni/gen_kmi_inc.py" "$APP/src/main/jni" \
        || die "gen_kmi_inc.py failed"
else
    [ -f "$APP/src/main/java/df/root/KmiModules.java" ] \
        || die "python3 missing and KmiModules.java not generated"
fi

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/lib/arm64-v8a" "$OUT"

# ---------------------------------------------------------------------------
say "javac"
# ---------------------------------------------------------------------------
# Compile, keeping the full log, and fail the build if javac failed.
# The previous form was `javac ... | grep -v 'bootstrap class path' || true`,
# which discarded the exit status twice over: the pipe reports grep's status,
# and `|| true` swallowed even that. Compile errors then scrolled past and the
# script carried on to package a stale or empty classes/ directory.
JAVAC_LOG="$WORK/javac.log"
if ! javac -encoding UTF-8 -source 8 -target 8 -nowarn -cp "$AJ" -d "$WORK/classes" \
        "$APP"/src/main/java/df/root/*.java > "$JAVAC_LOG" 2>&1; then
    grep -v 'bootstrap class path' "$JAVAC_LOG" >&2 || true
    die "javac failed (full log: $JAVAC_LOG)"
fi
grep -v 'bootstrap class path' "$JAVAC_LOG" || true

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
    --version-code "${VERSION_CODE:-37}" \
    --version-name "${VERSION_NAME:-3.7}"

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
# No password means no signing. Generating a key on the fly with an empty
# passphrase would produce an APK that cannot be installed over the previous
# build, which looks like a broken release rather than a missing secret.
if [ -z "$KS_PASS" ]; then
    die "keystore password not set: export DFROOT_KS_PASS or write $KS_SECRETS/password"
fi
if [ ! -f "$KS" ]; then
    die "no keystore at $KS (set DFROOT_SECRETS to the directory holding keystore.jks)"
fi

java -jar "$APKSIGNER" sign \
    --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
    --ks-key-alias "$KS_ALIAS" --min-sdk-version 31 \
    --out "$OUT/DFRoot.apk" "$WORK/aligned.apk"

java -jar "$APKSIGNER" verify --print-certs "$OUT/DFRoot.apk" | head -2

say "done"
ls -la "$OUT/DFRoot.apk"