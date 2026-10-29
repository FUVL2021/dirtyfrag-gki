package df.root;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Which su manager to hand root to, and where its {@code ksud} lives.
 *
 * <p>Three managers ship the same daemon binary under the same library name:
 * KernelSU, SukiSU Ultra and ReSukiSU. All three expose it as
 * {@code libksud.so} in their own native library dir, so "which manager" is
 * the only thing that has to be decided before a run — the path follows from
 * it and is never hardcoded.
 *
 * <p>Hardcoding the path would be wrong even for a single manager: the install
 * dir is {@code /data/app/~~<random>==/<pkg>-<hash>==}, so it changes on every
 * reinstall, every update, and on any device without a shared install history.
 * {@link ApplicationInfo#nativeLibraryDir} is the only correct source, and it
 * is what {@link Impl#ksudSource(Context)} reads.
 *
 * <p>Selection policy, in the order it is applied:
 *
 * <ol>
 *   <li>none installed — refuse, and say which packages were looked for;</li>
 *   <li>exactly one installed — use it, no question asked;</li>
 *   <li>two or more installed — use the user's saved choice if it is still
 *       installed; otherwise ask. Never pick for the user: the three are
 *       interchangeable in binary terms but not in behaviour (each starts its
 *       own daemon and shows its own grant prompt), and guessing wrong means
 *       the user gets root through a manager they did not expect.</li>
 * </ol>
 *
 * <p>The saved choice is remembered, so the second and every later run is
 * silent. It is re-validated on every read: uninstalling the chosen manager
 * falls back to "ask again" rather than failing at the staging step.
 */
public final class SuManager {

    private static final String TAG = "DFRoot/SuManager";

    /** Preference file, shared with the autorun and last-result keys. */
    private static final String PREFS = "dfroot";

    /**
     * The chosen manager's package name.
     *
     * <p>Stored in credential-encrypted prefs, not the device-encrypted file
     * bootstrap reads. bootstrap only needs the resolved value, which
     * {@link ExploitRunner} writes into {@code dfroot.xml} at staging time, so
     * this copy exists only for the UI and is written by the app itself.
     */
    static final String KEY_CHOSEN = "su_manager_pkg";

    /**
     * The managers this build knows how to drive, in the order they are
     * offered when a choice is needed.
     *
     * <p>One entry per package. Everything else is derived from the entry:
     * staging reads {@code <nativeLibraryDir>/libksud.so}, so no path appears
     * here and none needs updating when a manager changes its install layout.
     */
    public enum Impl {
        KERNELSU("me.weishu.kernelsu", "KernelSU",
                "/data/app/~~wlfUtSA-TD3_MgXHKNNbVg==/me.weishu.kernelsu-h31qFYD5vQK60UW-52-49Q==/lib/arm64/libksud.so"),
        SUKISU("com.sukisu.ultra", "SukiSU Ultra",
                "/data/app/~~tB2oDs7L-eRQ278mwZWHZg==/com.sukisu.ultra-HRdOa40-w6StXip_W7AzaQ==/lib/arm64/libksud.so"),
        /**
         * Recorded from a device where ReSukiSU was installed; that install is
         * gone now (its {@code ~~<random>==} dir no longer exists), so the
         * literal is cleared rather than left as a path that resolves to
         * nothing. Fill it back in if it is ever installed again on this
         * device — {@code pm path com.resukisu.resukisu} gives the dir.
         */
        RESUKISU("com.resukisu.resukisu", "ReSukiSU", null);

        /**
         * A manager this build knows nothing about — KernelSU-Next, APatch, or
         * anything else that ships a {@code ksud}-style daemon.
         *
         * <p>Not an {@link Impl} entry itself: it has no package and no baked
         * path, so it lives outside the enum and is offered as an extra row in
         * the picker. Its path always comes from the user, stored under the
         * same override key scheme with a fixed pseudo-package.
         */
        public static final String CUSTOM_PKG = "custom";

        /** Manifest package of the manager app. */
        public final String pkg;
        /** Name shown in the picker and in the log. */
        public final String label;

        /**
         * Known-good {@code libksud.so} path on this device, or {@code null}.
         *
         * <p>Used only after the package manager has failed to answer. The
         * {@code ~~<random>==} and {@code -<hash>==} segments are install-time
         * salts that change on every reinstall, so this path is not portably
         * correct — it is correct for the device it was recorded from, which is
         * the situation this exists for: a vendor ROM hides the manager package
         * so {@code nativeLibraryDir} can never be resolved, but the path is
         * still right because nothing has been reinstalled since.
         *
         * <p>Every use is verified with {@link File#isFile()} first, so a stale
         * entry after a reinstall degrades to "not found" instead of being
         * copied and failing later at exec time.
         */
        public final String bakedPath;

        Impl(String pkg, String label, String bakedPath) {
            this.pkg = pkg;
            this.label = label;
            this.bakedPath = bakedPath;
        }

        /**
         * Where this manager keeps {@code libksud.so}, or {@code null} when it
         * is not installed or ships no such library.
         *
         * <p>Resolved through the package manager on every call rather than
         * cached: an update reinstalls the APK into a new directory, so a
         * cached path stops existing without any signal.
         */
        public File ksudSource(Context ctx) {
            try {
                ApplicationInfo ai = ctx.getPackageManager().getApplicationInfo(pkg, 0);
                File f = new File(ai.nativeLibraryDir, "libksud.so");
                return f.exists() ? f : null;
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }

        /** Installed and its daemon binary is reachable. */
        public boolean usable(Context ctx) {
            return ksudSource(ctx) != null;
        }

        /**
         * A manual override path for this manager, or {@code null}.
         *
         * <p>Needed because the package manager is not always answerable: some
         * vendor ROMs answer {@code getApplicationInfo()} with
         * NameNotFoundException for packages that are plainly installed
         * (MIUI/HyperOS keep an app-list privacy switch, and the {@code <queries>}
         * entries in the manifest do not override it). When that happens
         * {@code nativeLibraryDir} is unreachable too, so the only way to stage
         * a run is to let the user type the path they can see themselves.
         *
         * <p>Stored per-manager so switching between two installed managers does
         * not carry one manager's override over to the other.
         */
        private String overrideKey() {
            return "su_path_override_" + pkg;
        }

        public void setOverride(Context ctx, String path) {
            SharedPreferences.Editor e = prefs(ctx).edit();
            if (path == null || path.trim().isEmpty()) {
                e.remove(overrideKey());
            } else {
                e.putString(overrideKey(), path.trim());
            }
            e.commit();
        }

        public String override(Context ctx) {
            return prefs(ctx).getString(overrideKey(), null);
        }

        /**
         * Where to read {@code libksud.so} from.
         *
         * <p>Three sources, in order:
         *
         * <ol>
         *   <li>the user's manual override — an explicit instruction, so it wins
         *       even when detection also works;</li>
         *   <li>{@code nativeLibraryDir} from the package manager — always
         *       correct when it works, and the only source that survives a
         *       reinstall;</li>
         *   <li>{@link #bakedPath} — recorded from this device. Used when the
         *       package manager refuses to answer.</li>
         * </ol>
         *
         * <p>No {@code isFile()} gate on the baked path. The app cannot read
         * {@code /data/app} at all: every install dir is {@code system:system
         * 0771}, so an unprivileged process cannot even stat into it. A check
         * here therefore always fails, and the path that is actually correct
         * would be rejected — which is exactly the bug this replaced. The copy
         * happens at staging, after root is in hand, so readability at this
         * point is not a useful signal.
         *
         * @return a path to try, or {@code null} when the manager is not
         *         present at all.
         */
        public File ksudSourceWithOverride(Context ctx) {
            String ov = override(ctx);
            if (ov != null && !ov.isEmpty()) {
                return new File(ov);
            }
            File resolved = ksudSource(ctx);
            if (resolved != null) {
                return resolved;
            }
            if (probeInstalled(ctx)) {
                return bakedPath != null ? new File(bakedPath) : null;
            }
            return null;
        }

        /**
         * True when the package manager can see this manager's APK.
         *
         * <p>Package visibility only — the app's own {@code <queries>} entries
         * cover the three known managers, so this answers "is it installed"
         * without going anywhere near {@code /data/app}.
         */
        public boolean probeInstalled(Context ctx) {
            try {
                ctx.getPackageManager().getApplicationInfo(pkg, 0);
                return true;
            } catch (PackageManager.NameNotFoundException e) {
                return false;
            }
        }

        /**
         * True when this manager resolves only through the baked path, i.e. the
         * package manager could not answer.
         */
        public boolean viaOverrideOnly(Context ctx) {
            return ksudSource(ctx) == null && ksudSourceWithOverride(ctx) != null;
        }
    }

    private SuManager() {
    }

    /**
     * Managers installed right now, in {@link Impl} declaration order.
     *
     * <p>Requires the daemon binary to be present, not just the package: a
     * manager halfway through an update has an APK but no extracted library,
     * and staging would fail later with a less useful message.
     *
     * <p>A manual path override counts as "installed" even when the package is
     * invisible to this app — that is the whole point of the override. Without
     * this the picker would refuse to record a choice the user already made on
     * a ROM that hides the manager package.
     */
    public static List<Impl> installed(Context ctx) {
        List<Impl> out = new ArrayList<>(3);
        for (Impl i : Impl.values()) {
            if (i.ksudSourceWithOverride(ctx) != null) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Every manager this build can drive, whether detected or not.
     *
     * <p>Backs the manual picker. Detecting nothing is a normal outcome on a
     * ROM that hides the manager package, and refusing to let the user say
     * "it is installed, use it" would leave them with no way forward.
     */
    public static List<Impl> all() {
        List<Impl> out = new ArrayList<>(3);
        for (Impl i : Impl.values()) {
            out.add(i);
        }
        return out;
    }

    /** The user's saved choice, or {@code null} if none or no longer installed. */
    public static Impl chosen(Context ctx) {
        String saved = prefs(ctx).getString(KEY_CHOSEN, null);
        if (saved == null || saved.isEmpty()) {
            return null;
        }
        for (Impl i : Impl.values()) {
            if (i.pkg.equals(saved)) {
                // Re-validated: a manager can be uninstalled between runs, and
                // a stale choice has to read as "not chosen" rather than fail
                // at staging. The override counts here for the same reason it
                // counts in installed(): on a ROM that hides the package, the
                // override is the only evidence the manager exists at all.
                return i.ksudSourceWithOverride(ctx) != null ? i : null;
            }
        }
        return null;
    }

    /** Remember a choice. Commit, not apply: the next step reads it back. */
    public static void remember(Context ctx, Impl impl) {
        prefs(ctx).edit().putString(KEY_CHOSEN, impl.pkg).commit();
        Log.i(TAG, "chosen su manager: " + impl.pkg);
    }

    /** Forget the choice, so the next run asks again. */
    public static void forget(Context ctx) {
        prefs(ctx).edit().remove(KEY_CHOSEN).remove(customKey()).commit();
    }

    /**
     * Path the user typed for a manager outside {@link Impl}, or {@code null}.
     *
     * <p>Stored separately from the per-manager overrides because there is no
     * manager to key it by: the user is saying "use this binary" with no
     * package attached.
     */
    public static String customPath(Context ctx) {
        return prefs(ctx).getString(customKey(), null);
    }

    public static void setCustomPath(Context ctx, String path) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (path == null || path.trim().isEmpty()) {
            e.remove(customKey());
        } else {
            e.putString(customKey(), path.trim());
        }
        e.commit();
    }

    /** True when the user's saved choice is the custom entry. */
    public static boolean customChosen(Context ctx) {
        return Impl.CUSTOM_PKG.equals(prefs(ctx).getString(KEY_CHOSEN, null));
    }

    /** Remember the custom entry as the choice. */
    public static void rememberCustom(Context ctx) {
        prefs(ctx).edit().putString(KEY_CHOSEN, Impl.CUSTOM_PKG).commit();
        Log.i(TAG, "chosen su manager: custom");
    }

    /** The custom path to stage, or {@code null} when unset. */
    public static File customSource(Context ctx) {
        String p = customPath(ctx);
        return (p == null || p.isEmpty()) ? null : new File(p);
    }

    private static String customKey() {
        return "su_path_override_" + Impl.CUSTOM_PKG;
    }

    /**
     * What the UI should show about the current state, in one line.
     *
     * <p>Distinguishes "not installed" from "installed but not chosen" from
     * "chosen", because the fix is different in each case: install one, pick
     * one, or nothing at all.
     */
    public static String describe(Context ctx) {
        if (customChosen(ctx)) {
            String p = customPath(ctx);
            return p != null && !p.isEmpty()
                    ? "自定义（" + p + "）"
                    : "自定义（未填路径）";
        }
        List<Impl> have = installed(ctx);
        if (have.isEmpty()) {
            return "未检测到（可手动选择，或选「自定义」填路径）";
        }
        Impl c = chosen(ctx);
        if (c != null) {
            // Say when the choice does not rest on a live package-manager
            // lookup: if a run fails later this is the first thing to check.
            return c.label + (c.viaOverrideOnly(ctx) ? "（已选择·内置路径）" : "（已选择）");
        }
        if (have.size() == 1) {
            Impl only = have.get(0);
            return only.label + (only.viaOverrideOnly(ctx)
                    ? "（唯一·内置路径）" : "（唯一，直接使用）");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < have.size(); i++) {
            if (i > 0) {
                sb.append(" / ");
            }
            sb.append(have.get(i).label);
        }
        sb.append("（需选择）");
        return sb.toString();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
