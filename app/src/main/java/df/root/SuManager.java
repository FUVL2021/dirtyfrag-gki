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
        KERNELSU("me.weishu.kernelsu", "KernelSU"),
        SUKISU("com.sukisu.ultra", "SukiSU Ultra"),
        RESUKISU("com.resukisu.resukisu", "ReSukiSU");

        /** Manifest package of the manager app. */
        public final String pkg;
        /** Name shown in the picker and in the log. */
        public final String label;

        Impl(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
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
    }

    private SuManager() {
    }

    /**
     * Managers installed right now, in {@link Impl} declaration order.
     *
     * <p>Requires the daemon binary to be present, not just the package: a
     * manager halfway through an update has an APK but no extracted library,
     * and staging would fail later with a less useful message.
     */
    public static List<Impl> installed(Context ctx) {
        List<Impl> out = new ArrayList<>(3);
        for (Impl i : Impl.values()) {
            if (i.usable(ctx)) {
                out.add(i);
            }
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
                // at staging.
                return i.usable(ctx) ? i : null;
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
        prefs(ctx).edit().remove(KEY_CHOSEN).commit();
    }

    /**
     * What the UI should show about the current state, in one line.
     *
     * <p>Distinguishes "not installed" from "installed but not chosen" from
     * "chosen", because the fix is different in each case: install one, pick
     * one, or nothing at all.
     */
    public static String describe(Context ctx) {
        List<Impl> have = installed(ctx);
        if (have.isEmpty()) {
            return "未检测到（未安装 KernelSU / SukiSU / ReSukiSU）";
        }
        Impl c = chosen(ctx);
        if (c != null) {
            return c.label + "（已选择）";
        }
        if (have.size() == 1) {
            return have.get(0).label + "（唯一，直接使用）";
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
