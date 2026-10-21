package df.root;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Starts the autorun worker after a reboot, if the user turned autorun on.
 *
 * <p>This receiver deliberately does no work of its own. It used to call the
 * exploit directly on a background thread with {@code goAsync()}, which gives
 * roughly ten seconds of process lifetime — not enough for an su probe plus a
 * full run, so the thread was killed mid-flight and nothing was recorded. The
 * work now lives in {@link AutorunService}, which has no such budget.
 *
 * <p>Gating is by preference only, never by component-enabled state: a receiver
 * declared {@code enabled="false"} in the manifest is not delivered broadcasts
 * on some ROMs even after {@code setComponentEnabledSetting()} turns it back
 * on, so the component stays enabled and returns early instead.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "DFRoot/Boot";
    static final String PREFS = "dfroot";
    static final String KEY_AUTORUN = "autorun";

    /** Outcome of the last autorun, so the UI can explain itself. */
    static final String KEY_LAST_RESULT = "last_boot_result";

    /**
     * Whether this boot may still retry a failed run.
     *
     * <p>Defaults to true so the very first boot gets its retry without any
     * setup. Consumed by the service before retrying and set back to true after
     * a second failure, which gives every boot one retry rather than one retry
     * per install.
     */
    static final String KEY_RETRY_LEFT = "retry_once_left";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();

        // Log before any decision, and log the ignored cases too. "The receiver
        // was never called" and "it was called and bailed out" look identical
        // from the outside, and telling them apart is the whole point.
        BootLog.i(context, "receiver: action=" + action
                + " autorun=" + autorunOn(context)
                + " ver=" + versionOf(context));

        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            BootLog.i(context, "package replaced; autorun=" + autorunOn(context));
            return;
        }
        boolean isBoot = Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(action);
        if (!isBoot) {
            BootLog.i(context, "ignored: not a boot broadcast");
            return;
        }
        if (!autorunOn(context)) {
            BootLog.i(context, "boot: autorun disabled, nothing to do");
            return;
        }

        BootLog.sessionStart(context, "boot broadcast " + action);
        BootLog.i(context, "boot: handing off to service");
        try {
            AutorunService.start(context);
            BootLog.i(context, "boot: service start returned normally");
        } catch (Exception e) {
            // A foreground service start can be refused (background start
            // restrictions, missing notification permission on some builds).
            // Record it instead of letting it escape a receiver.
            BootLog.e(context, "boot: could not start autorun service", e);
            setLastResult(context, "failed: service start");
            setAutorun(context, false);
        }
    }

    static boolean autorunOn(Context context) {
        return prefs(context).getBoolean(KEY_AUTORUN, false);
    }

    /**
     * Version string for the log header.
     *
     * <p>Read from PackageManager rather than BuildConfig: this project is built
     * with a hand-rolled aapt2 + javac + d8 pipeline and has no generated
     * BuildConfig class.
     */
    private static String versionOf(Context context) {
        try {
            android.content.pm.PackageInfo pi = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return pi.versionName + "(" + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "?";
        }
    }

    static void setAutorun(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_AUTORUN, on).commit();
    }

    /**
     * Wait for su to become usable, polling until the deadline.
     *
     * <p>Needed because the exploit finishing does not mean the su daemon is
     * serving yet: the module is loaded and SELinux is dropped inside the same
     * run, and the daemon that answers {@code su} comes up asynchronously
     * afterwards. A single immediate check races against that and reports "no
     * root" on a run that actually succeeded.
     *
     * <p>Each attempt is cheap, but the first one may have to wait out a su
     * prompt that cannot be shown at boot, so the per-attempt timeout is kept
     * short and the loop bounded by {@code deadlineMs} rather than by a count.
     */
    static boolean waitForSu(Context context, long deadlineMs, Log sink) {
        long start = System.currentTimeMillis();
        int attempt = 0;
        while (System.currentTimeMillis() - start < deadlineMs) {
            attempt++;
            ExploitRunner.ShellResult r = ExploitRunner.suId(4);
            if (r != null && r.rc == 0 && r.output.contains("uid=0")) {
                sink.i("worker: su available after "
                        + (System.currentTimeMillis() - start) + "ms (" + attempt + " attempts)");
                return true;
            }
            try {
                // 1s between attempts: enough for the daemon to finish binding,
                // short enough that a successful boot still sweeps promptly.
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        sink.i("worker: su unavailable after " + deadlineMs + "ms (" + attempt + " attempts)");
        return false;
    }

    /**
     * Minimal logging surface, so this helper does not depend on BootLog's
     * concrete type and stays usable from a service without a Context dance.
     */
    interface Log {
        void i(String message);
    }

    static boolean retryOnceLeft(Context context) {
        return prefs(context).getBoolean(KEY_RETRY_LEFT, true);
    }

    static void setRetryOnceLeft(Context context, boolean left) {
        prefs(context).edit().putBoolean(KEY_RETRY_LEFT, left).commit();
    }

    static void setLastResult(Context context, String result) {
        prefs(context).edit().putString(KEY_LAST_RESULT, result).commit();
    }

    static String lastResult(Context context) {
        return prefs(context).getString(KEY_LAST_RESULT, null);
    }

    static void clearLastResult(Context context) {
        prefs(context).edit().remove(KEY_LAST_RESULT).commit();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}