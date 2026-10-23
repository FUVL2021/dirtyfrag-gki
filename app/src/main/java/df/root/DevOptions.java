package df.root;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The developer switch.
 *
 * <p>Diagnostics — the run log and its viewer — are needed by whoever is
 * debugging a device, and by nobody else. Left on the settings screen they are
 * two buttons a normal user will tap once, get a wall of technical text, and
 * never understand. So they are hidden behind a switch that is off by default,
 * which is a different thing from removing them: when a run misbehaves, the
 * log is the only record, and it has to remain reachable from the app.
 *
 * <p>Stored in device-encrypted storage for the same reason as everything else
 * the boot path reads: this flag is consulted while the user may still be
 * locked, and credential-encrypted preferences throw there.
 */
public final class DevOptions {

    private static final String PREFS = "dfroot_dev";
    private static final String KEY_ENABLED = "developer_mode";

    private DevOptions() {
    }

    private static SharedPreferences prefs(Context ctx) {
        Context de = ctx.createDeviceProtectedStorageContext();
        return (de == null ? ctx : de).getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context ctx) {
        try {
            return prefs(ctx).getBoolean(KEY_ENABLED, false);
        } catch (RuntimeException e) {
            // Storage not available yet; "off" is the safe answer and the
            // default anyway.
            return false;
        }
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, on).apply();
    }
}