package df.root;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

/**
 * What this build is, for the UI and for pasted logs.
 *
 * <p>A user reporting a failure has to be able to say which build they ran.
 * Without a version line every report starts with a round trip asking for
 * {@code versionName}, which is not something anyone remembers.
 *
 * <p>This deliberately reports the app version, not the Android release. The
 * Android release of the *system* has no bearing on module selection; the
 * {@code androidNN} that matters is the tag inside the kernel string, and that
 * is handled by {@link KernelInfo}.
 */
public final class BuildInfo {

    private BuildInfo() {
    }

    public static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    public static long versionCode(Context ctx) {
        try {
            // minSdk is 31, so getLongVersionCode is always available.
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0)
                    .getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** One line: {@code v3.7 (37) · arm64-v8a · sdk 34}. */
    public static String summary(Context ctx) {
        return "v" + versionName(ctx)
                + " (" + versionCode(ctx) + ")"
                + " · " + Build.SUPPORTED_ABIS[0]
                + " · sdk " + Build.VERSION.SDK_INT;
    }
}