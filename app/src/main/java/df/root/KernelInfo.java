package df.root;

import java.util.Locale;

/**
 * Kernel identification for the UI layer.
 *
 * <p>The authoritative parse happens in native code ({@code ko_select.c}) right
 * before the module is patched in, because that is the only place the decision
 * actually matters. This class exists so the app can show the user what will
 * happen before they press the button, and so a pasted log carries the same
 * information the native side used.
 *
 * <p>Both sides implement the same rules: kernel major.minor is the hard
 * filter (vermagic is built from it), android release breaks ties, and an
 * unmatched release falls back to the first module with the same kernel
 * version. Keep them in sync — {@code ko_select_test} covers the native side.
 */
public final class KernelInfo {

    /** GKI KMI string, e.g. {@code android13-5.15}. */
    public final String kmi;
    public final int androidRelease;
    public final int kernelMajor;
    public final int kernelMinor;
    public final String rawRelease;

    /** True when this build has no module for the running kernel. */
    public final boolean unsupported;

    /** True when the release number did not match and a same-kernel fallback was used. */
    public final boolean fallback;

    private KernelInfo(String kmi, int androidRelease, int major, int minor,
                       String rawRelease, boolean unsupported, boolean fallback) {
        this.kmi = kmi;
        this.androidRelease = androidRelease;
        this.kernelMajor = major;
        this.kernelMinor = minor;
        this.rawRelease = rawRelease;
        this.unsupported = unsupported;
        this.fallback = fallback;
    }

    /**
     * Module names bundled in this build, mirroring {@code jni/ko/}.
     * Parsed from the same naming contract the build uses:
     * {@code dfroot-android<release>-<major>.<minor>.ko}.
     */
    private static final String[] MODULES = {
        "dfroot-android12-5.10.ko",
        "dfroot-android13-5.10.ko",
        "dfroot-android13-5.15.ko",
        "dfroot-android14-5.15.ko",
        "dfroot-android14-6.1.ko",
        "dfroot-android15-6.6.ko",
        "dfroot-android16-6.12.ko",
        "dfroot-android17-6.18.ko",
    };

    /** Reads {@code uname -r} through {@code System.getProperty}, no subprocess needed. */
    public static String rawKernelRelease() {
        String v = System.getProperty("os.version");
        return v == null ? "unknown" : v;
    }

    public static KernelInfo detect() {
        return parse(rawKernelRelease());
    }

    /**
     * Parse a kernel release string and resolve it against the bundled modules.
     * Visible for testing — the tests feed synthetic strings through it.
     */
    public static KernelInfo parse(String release) {
        if (release == null) {
            return new KernelInfo("(none)", 0, 0, 0, "unknown", true, false);
        }

        int major = 0, minor = 0, android = 0;

        // "5.15.202-android13-..." -> major.minor is the first pair of numbers.
        int dot = release.indexOf('.');
        if (dot > 0) {
            try {
                major = Integer.parseInt(release.substring(0, dot).trim());
                int end = dot + 1;
                while (end < release.length() && Character.isDigit(release.charAt(end))) {
                    end++;
                }
                minor = Integer.parseInt(release.substring(dot + 1, end));
            } catch (NumberFormatException e) {
                major = minor = 0;
            }
        }

        // "androidNN" is the release tag; the digits run until a non-digit.
        int tag = release.indexOf("android");
        if (tag >= 0) {
            int p = tag + "android".length();
            int end = p;
            while (end < release.length() && Character.isDigit(release.charAt(end))) {
                end++;
            }
            if (end > p) {
                try {
                    android = Integer.parseInt(release.substring(p, end));
                } catch (NumberFormatException e) {
                    android = 0;
                }
            }
        }

        if (major == 0 || android == 0) {
            // Not a GKI kernel, or a vendor string we cannot parse.
            return new KernelInfo("(none)", android, major, minor, release, true, false);
        }

        // Resolve against the bundle, same rules as ko_image_select().
        String exact = null, sameKernel = null;
        for (String name : MODULES) {
            int[] id = parseModuleName(name);
            if (id == null || id[1] != major || id[2] != minor) {
                continue;
            }
            if (sameKernel == null) {
                sameKernel = name;
            }
            if (id[0] == android) {
                exact = name;
                break;
            }
        }

        String chosen = exact != null ? exact : sameKernel;
        if (chosen == null) {
            return new KernelInfo("(none)", android, major, minor, release, true, false);
        }

        // Report the module that was chosen, not the kernel's release number.
        // On a fallback those differ, and the whole point of the fallback flag
        // is that the user can see which module will actually be used.
        int[] chosenId = parseModuleName(chosen);
        int chosenAndroid = chosenId != null ? chosenId[0] : android;

        return new KernelInfo(
                String.format(Locale.US, "android%d-%d.%d", chosenAndroid, major, minor),
                android, major, minor, release,
                false,
                exact == null);
    }

    /** {@code dfroot-android13-5.15.ko} -> {@code {13, 5, 15}} or null. */
    static int[] parseModuleName(String name) {
        if (!name.startsWith("dfroot-android") || !name.endsWith(".ko")) {
            return null;
        }
        String body = name.substring("dfroot-android".length(), name.length() - 3);
        int dash = body.indexOf('-');
        if (dash <= 0) {
            return null;
        }
        int dot = body.indexOf('.', dash);
        if (dot <= dash) {
            return null;
        }
        try {
            return new int[]{
                    Integer.parseInt(body.substring(0, dash)),
                    Integer.parseInt(body.substring(dash + 1, dot)),
                    Integer.parseInt(body.substring(dot + 1)),
            };
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Human readable one-liner for the UI and the run log. */
    public String describe() {
        if (unsupported) {
            return "内核不支持 — 没有匹配的内置模块 " + rawRelease;
        }
        if (fallback) {
            return kmi + "（回退匹配 — 版本号未精确匹配）";
        }
        return kmi;
    }

    // ------------------------------------------------------------------
    // Build inventory
    // ------------------------------------------------------------------

    /** One entry per bundled module, for the "what does this build ship" view. */
    public static final class Module {
        public final String name;
        public final int android;
        public final int major;
        public final int minor;
        /** True for the module {@link #detect()} would pick on this kernel. */
        public boolean selected;

        Module(String name, int android, int major, int minor) {
            this.name = name;
            this.android = android;
            this.major = major;
            this.minor = minor;
        }

        public String kmi() {
            return String.format(Locale.US, "android%d-%d.%d", android, major, minor);
        }
    }

    /** Every module in this build, in the same order the native table uses. */
    public static Module[] modules() {
        Module[] out = new Module[MODULES.length];
        for (int i = 0; i < MODULES.length; i++) {
            int[] id = parseModuleName(MODULES[i]);
            out[i] = new Module(MODULES[i], id[0], id[1], id[2]);
        }
        return out;
    }

    /**
     * Mark which module this kernel resolves to. Same comparison the
     * selection uses, so the marker cannot disagree with the run.
     */
    public Module[] modulesWithSelection() {
        Module[] list = modules();
        if (unsupported) {
            return list;
        }
        for (Module m : list) {
            if (m.kmi().equals(kmi)) {
                m.selected = true;
                break;
            }
        }
        return list;
    }
}