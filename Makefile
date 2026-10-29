# DFRoot — build entry point
#
# Two independent halves:
#   make lkm    modules, one per GKI KMI, needs a kernel build container
#   make apk    the app, needs an Android SDK + NDK
#
# They are separated because the module build wants a kernel tree and the app
# build wants the SDK, and almost nobody has both in the same shell.

SHELL       := bash
.SHELLFLAGS := -euo pipefail -c
MAKEFLAGS   += --no-print-directory

ARCH   := aarch64
ABI    := arm64-v8a
ENGINE ?= podman

# Every GKI KMI this project ships a module for. Adding one here plus a
# corresponding kernel image is the whole procedure; the app picks up the new
# module automatically because gen_kmi_inc.py scans jni/ko/.
KMIS :=        \
    android12-5.10  \
    android13-5.10  \
    android13-5.15  \
    android14-5.15  \
    android14-6.1   \
    android15-6.6   \
    android16-6.12  \
    android17-6.18

DDK_WF      := .github/workflows/build.yml
DDK_IMAGE   ?= $(shell awk '/image: ghcr\.io\//{gsub(/.*image: /,"");gsub(/:.*$$/,"");print;exit}' $(DDK_WF) 2>/dev/null)
DDK_RELEASE ?= $(shell awk '/image: ghcr\.io\/ylarod\/ddk-min:/{sub(/.*-/,"");print;exit}' $(DDK_WF) 2>/dev/null)

# Signing material lives outside the tree; see tools/build_apk.sh for the
# same lookup. Override with DFROOT_SECRETS / DFROOT_KS_PASS / DFROOT_KS_ALIAS.
DFROOT_SECRETS ?= $(HOME)/.dfroot-secrets
SIGN_PASS  ?= $(shell cat $(DFROOT_SECRETS)/password 2>/dev/null || echo df-ksu)
SIGN_ALIAS ?= df-ksu
KEYSTORE   ?= $(firstword $(wildcard $(DFROOT_SECRETS)/keystore.jks keystore.jks))

LKM_SRCS := lkm/dfroot.c lkm/Makefile
LKM_KOS  := $(KMIS:%=app/src/main/jni/ko/dfroot-%.ko)
APK_SRCS := $(wildcard app/src/main/java/df/root/*.java \
                        app/src/main/jni/*.c app/src/main/jni/*.h \
                        app/src/main/jni/*.S app/src/main/jni/*.inc \
                        app/src/main/jni/*.py \
                        app/src/main/res/layout/*.xml \
                        app/src/main/res/values/*.xml) \
            app/src/main/AndroidManifest.xml \
            app/build.gradle.kts \
            app/src/main/jni/CMakeLists.txt

APK := out/DFRoot.apk

.PHONY: all lkm apk test test-native test-java clean help

all: $(APK)

help:
	@printf 'targets:\n'
	@printf '  lkm          build one module per KMI (needs %s + DDK image)\n' '$(ENGINE)'
	@printf '  apk          build the signed APK (needs Android SDK/NDK)\n'
	@printf '  test-native  run the module-selection unit test on the host\n'
	@printf '  test-java    run the kernel-parse unit test on the JVM\n'
	@printf '  test         both of the above\n'
	@printf '  clean        remove build artefacts\n'

# ---------------------------------------------------------------------------
# Modules
#
# Each KMI is compiled in the matching DDK image so that vermagic and struct
# layouts come from the right kernel tree. -Os plus stripped unwind sections
# keeps the payload small: it is written into a vendor library page by page.
# ---------------------------------------------------------------------------
$(LKM_KOS): $(LKM_SRCS)
	@mkdir -p app/src/main/jni/ko
	@for kmi in $(KMIS); do \
	    printf '\n\033[1;33m▶  %s  %s\033[0m\n' "$(ENGINE)" "$$kmi"; \
	    $(ENGINE) run --rm --pid=host --network=none \
	        -v $(abspath .):/proj \
	        -e KMI=$$kmi \
	        $(DDK_IMAGE):$$kmi-$(DDK_RELEASE) \
	        bash -c 'set -e; \
	            cd /proj/lkm; \
	            make -j$$(nproc); \
	            llvm-objcopy --strip-unneeded \
	              -R .comment -R .note.gnu.build-id -R .note.gnu.property \
	              -R .note.Linux -R .note.GNU-stack \
	              -R .BTF -R .BTF.base -R .llvm_addrsig \
	              -R .hyp.text -R .hyp.bss -R .hyp.rodata -R .hyp.event_ids \
	              -R .hyp.patchable_function_entries -R .hyp.data dfroot.ko; \
	            cp dfroot.ko /proj/app/src/main/jni/ko/dfroot-'"$$kmi"'.ko; \
	            make clean; \
	            printf "\033[1;32m  ✔  $$kmi\033[0m\n"; \
	        ' || exit 1; \
	done;

lkm: $(LKM_KOS)

# ---------------------------------------------------------------------------
# App
# ---------------------------------------------------------------------------
keystore.jks:
	keytool -genkeypair -keystore $@ -storetype JKS \
	    -storepass $(SIGN_PASS) -keypass $(SIGN_PASS) \
	    -alias $(SIGN_ALIAS) -keyalg RSA -keysize 2048 -validity 10000 \
	    -dname "CN=DFRoot"

signing.properties: $(KEYSTORE)
	@printf 'KEYSTORE_FILE=%s\nKEYSTORE_PASSWORD=%s\nKEY_ALIAS=%s\nKEY_PASSWORD=%s\n' \
	    "$(abspath $(KEYSTORE))" "$(SIGN_PASS)" "$(SIGN_ALIAS)" "$(SIGN_PASS)" > $@
	@chmod 600 $@

$(APK): signing.properties $(LKM_KOS) $(APK_SRCS)
	@printf '\n\033[1;36m══  DFRoot APK  ══\033[0m\n'
	./gradlew :app:assembleRelease
	@mkdir -p $(dir $@)
	cp app/build/outputs/apk/release/*.apk $@
	@printf '\033[1;32m  ✔  %s\033[0m\n' "$@"

apk: $(APK)

# ---------------------------------------------------------------------------
# Tests
#
# The selection logic is the only version-sensitive part of the project, so it
# is the part that gets tests. Both suites run on the host and use the same
# fixture strings, which keeps the native and Java implementations from
# drifting apart.
# ---------------------------------------------------------------------------
JNI := app/src/main/jni

# The workspace is often on a noexec mount (sdcard), so binaries are built
# and run from a scratch dir rather than next to the sources.
TEST_DIR ?= /tmp/dfroot-test

test-native:
	@printf '\n\033[1;36m══  native selection test  ══\033[0m\n'
	@command -v python3 >/dev/null && python3 $(JNI)/gen_kmi_inc.py $(JNI)
	@mkdir -p $(TEST_DIR)
	$(CC) -std=c11 -Wall -Wextra -Werror -I$(JNI) \
	    $(JNI)/t_ko_select.c -o $(TEST_DIR)/t_ko_select
	$(TEST_DIR)/t_ko_select

test-java:
	@printf '\n\033[1;36m══  java parse test  ══\033[0m\n'
	@mkdir -p $(TEST_DIR)/java
	javac -d $(TEST_DIR)/java \
	    app/src/main/java/df/root/KernelInfo.java \
	    app/src/test/java/KernelInfoTest.java
	java -cp $(TEST_DIR)/java KernelInfoTest

test: test-native test-java

# ---------------------------------------------------------------------------
clean:
	rm -f app/src/main/jni/splicehelper
	rm -f app/src/main/jni/kmi_table.inc app/src/main/jni/kmi_blobs.inc
	rm -f lkm/*.o lkm/*.cmd lkm/.*.cmd lkm/*.mod lkm/*.mod.c lkm/*.lto.o
	rm -f lkm/modules.order lkm/Module.symvers lkm/dfroot.lds lkm/dfroot.ko
	rm -rf out app/.cxx app/build .gradle