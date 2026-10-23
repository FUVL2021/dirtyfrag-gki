package df.root;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * Runs the exploit once, in the background, with the lifetime it actually needs.
 *
 * <p>This exists because a {@link android.content.BroadcastReceiver} cannot do
 * the job. {@code goAsync()} buys roughly ten seconds after {@code onReceive}
 * returns, and the su probe alone is allowed eight of them; the exploit that
 * follows takes longer still. Measured on the fuxi target, the thread was
 * killed part-way and no result was ever persisted — the run looked like it
 * never happened.
 *
 * <p>As a foreground service there is no such budget. The service is started
 * from the boot receiver, keeps the process alive for the duration of the run,
 * writes the outcome, and stops itself.
 *
 * <p>Everything here assumes the process may be killed at any moment: results
 * go to prefs with {@code commit()} (synchronous) rather than {@code apply()},
 * because the service stops itself immediately afterwards and an async flush
 * can lose the write.
 */
public class AutorunService extends Service {

    private static final String TAG = "DFRoot/Boot";
    private static final String CHANNEL = "dfroot_autorun";
    private static final int NOTIFICATION_ID = 1;

    /** Separate channel and id: this one must survive the service stopping. */
    private static final String CHANNEL_ALERT = "dfroot_alert";
    private static final int ALERT_NOTIFICATION_ID = 2;

    /** Set when the service was started from the boot receiver. */
    static final String EXTRA_FROM_BOOT = "from_boot";

    /**
     * How long to wait for su after a successful run, before giving up and
     * telling the user.
     *
     * <p>Twenty seconds because the exploit returning is not the same as the su
     * daemon being ready: the module and SELinux drop land inside the run, but
     * the daemon starts answering afterwards, and on a cold boot that gap is
     * the widest it ever gets.
     */
    private static final long SU_WAIT_MS = 20_000L;

    private volatile boolean running;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        boolean fromBoot = intent != null && intent.getBooleanExtra(EXTRA_FROM_BOOT, false);
        // Who asked for this service, and from where. Recorded before any
        // decision so a stray start shows up in the log even when it is
        // rejected below.
        BootLog.origin(this, "AutorunService.onStartCommand(fromBoot=" + fromBoot
                + ", startId=" + startId + ")");
        if (!fromBoot) {
            // Only the boot receiver starts this; anything else is a stray.
            BootLog.w(this, "service: started without from_boot, ignoring");
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        if (running) {
            // A second broadcast (LOCKED_BOOT then BOOT) must not start a
            // second exploit against the same kernel.
            BootLog.i(this, "service: already running, ignoring duplicate start");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        BootLog.i(this, "service: onStartCommand, starting foreground");
        running = true;
        // startForeground can throw on Android 12+ (background start
        // restrictions) and on 14+ (missing notification permission / FGS type).
        // Without this catch the exception escapes onStartCommand, the process
        // dies, and the log stops right here — which is exactly the symptom of
        // "boot autorun silently did nothing".
        try {
            startForeground(NOTIFICATION_ID, buildNotification("正在执行……"));
            BootLog.i(this, "service: foreground notification posted");
        } catch (Throwable t) {
            BootLog.e(this, "service: startForeground failed, aborting", t);
            BootReceiver.setLastResult(this, "failed: foreground");
            // Not a transient problem the user can fix by rebooting, so leave
            // autorun on rather than clearing it: this is a build/permission
            // defect, not a failed exploit.
            running = false;
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        PowerManager pm = getSystemService(PowerManager.class);
        final PowerManager.WakeLock wl = pm == null ? null
                : pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        final boolean held;
        if (wl != null) {
            boolean ok = false;
            try {
                wl.acquire(10 * 60 * 1000L);
                ok = true;
            } catch (SecurityException e) {
                // WAKE_LOCK is not granted automatically when the permission is
                // added in an upgrade, so this is reachable on a real device.
                // The run does not need it, so log and carry on.
                Log.w(TAG, "autorun: no WAKE_LOCK, continuing without it", e);
            }
            held = ok;
        } else {
            held = false;
        }

        new Thread(() -> {
            String result;
            boolean ok = false;
            try {
                BootLog.i(this, "worker: thread started");
                if (!BootReceiver.autorunOn(this)) {
                    result = "skipped: autorun off";
                    BootLog.i(this, "worker: autorun flag is off, nothing to do");
                } else {
                    // No residue sweep here. Before the run there is no root, so
                    // the check could only return UNKNOWN — a su round trip that
                    // can never succeed. The sweep belongs after the run, once
                    // su actually exists; it is done below.
                    //
                    // No su check here, on purpose. After a reboot the dfroot
                    // module is gone and KernelSU's runtime su is gone with it
                    // — that absence is the whole reason this autorun exists.
                    // Requiring root before running would make the feature
                    // impossible: it would skip every single boot.
                    KernelInfo info = KernelInfo.detect();
                    BootLog.i(this, "worker: kernel=" + info.rawRelease
                            + " unsupported=" + info.unsupported);
                    if (info.unsupported) {
                        result = "skipped: unsupported kernel";
                    } else {
                        BootLog.i(this, "worker: invoking exploit binary");
                        int rc = ExploitRunner.run(this, line -> BootLog.i(this, "exp| " + line));
                        BootLog.i(this, "worker: exploit returned rc=" + rc);

                        // Resolution failures are not exploit failures and must
                        // not be retried or treated as evidence of a broken
                        // device: they mean the user has to make a choice, which
                        // a boot-time service cannot ask for. Reported with their
                        // own prefix so the UI can say "open the app and pick".
                        if (rc == ExploitRunner.RC_NEED_CHOICE) {
                            result = "skipped: need choice";
                            BootLog.i(this, "worker: several su managers, none chosen;"
                                    + " user has to pick in the app");
                        } else if (rc == ExploitRunner.RC_NO_MANAGER) {
                            result = "skipped: no su manager";
                            BootLog.i(this, "worker: no su manager installed");
                        } else {
                        ok = rc == 0;

                        // One retry, on the theory that a failed run which did
                        // not take the device down is usually a transient
                        // coincidence rather than a real incompatibility: the
                        // kernel stayed up, so the race simply did not land.
                        // Bounded at one attempt — this is boot, and a loop that
                        // keeps re-running a failing exploit is exactly the
                        // behaviour the autorun interlock exists to prevent.
                        //
                        // Only after a run that returned, never after the
                        // process died: an rc means the binary gave control
                        // back, which is what makes a retry safe to attempt.
                        if (!ok && BootReceiver.retryOnceLeft(this)) {
                            BootReceiver.setRetryOnceLeft(this, false);
                            BootLog.i(this, "worker: retrying once (previous rc=" + rc + ")");
                            int rc2 = ExploitRunner.run(this, line -> BootLog.i(this, "exp| " + line));
                            BootLog.i(this, "worker: retry returned rc=" + rc2);
                            ok = rc2 == 0;
                            rc = rc2;
                        }

                        if (!ok) {
                            // Give the next boot a fresh pair of attempts,
                            // otherwise a single bad boot would use up the
                            // budget permanently.
                            BootReceiver.setRetryOnceLeft(this, true);
                        }
                        result = ok ? "ok" : "failed: rc=" + rc;
                        }
                    }
                }
            } catch (Throwable t) {
                // Throwable, not Exception: a native link failure arrives as an
                // Error and still has to be recorded.
                BootLog.e(this, "worker: autorun failed", t);
                result = "failed: " + t.getClass().getSimpleName();
            } finally {
                if (held && wl != null && wl.isHeld()) {
                    wl.release();
                }
            }

            // ---- Post-run sweep ------------------------------------------------
            // Only after the exploit has run: before that there is no su, so a
            // check could not do anything but return UNKNOWN. Running the
            // exploit is what creates both the residue and the means to remove
            // it, which is why the order has to be run -> wait -> clean.
            boolean suUp = false;
            if (result.equals("ok")) {
                BootLog.i(this, "worker: waiting up to " + SU_WAIT_MS + "ms for su");
                suUp = BootReceiver.waitForSu(this, SU_WAIT_MS,
                        msg -> BootLog.i(this, msg));
            }
            if (suUp) {
                ExploitRunner.Residue r = ExploitRunner.detectResidue();
                BootLog.i(this, "worker: residue check: " + ExploitRunner.describe(r));
                if (r != ExploitRunner.Residue.CLEAN
                        && r != ExploitRunner.Residue.UNKNOWN) {
                    BootLog.i(this, "worker: cleaning residue");
                    ExploitRunner.cleanupWith(this, line -> BootLog.i(this, "clean| " + line));
                    BootLog.i(this, "worker: residue after cleanup: "
                            + ExploitRunner.describe(ExploitRunner.detectResidue()));
                }
                result = "ok";
            } else if (result.equals("ok")) {
                // The exploit claimed success but su never answered. Say so
                // instead of reporting a clean boot: the user needs to know the
                // device is in an unknown state and can still clean it by hand.
                BootLog.i(this, "worker: exploit ok but su never came up, notifying");
                notifySuMissing();
                result = "ok: no su after " + (SU_WAIT_MS / 1000) + "s";
            }

            boolean ranExploit = result.startsWith("ok") || result.startsWith("failed");
            BootReceiver.setLastResult(this, result);
            BootLog.i(this, "worker: result=" + result);
            if (ranExploit && !ok) {
                // The interlock stays, but it now fires only after the retry
                // also failed, and the run returned rather than took the device
                // down. A single failed attempt is not evidence of a broken
                // device — the race simply did not land — so one bad boot no
                // longer silently switches autorun off for good.
                BootLog.i(this, "worker: " + result + " after retry, clearing autorun");
                BootReceiver.setAutorun(this, false);
            }

            running = false;
            BootLog.i(this, "worker: done, stopping service");
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
        }, "dfroot-autorun").start();

        // Not sticky: restarting this after the process died would re-run an
        // exploit whose half-finished state we no longer know.
        return START_NOT_STICKY;
    }

    /**
     * Tell the user su never appeared after a run that otherwise succeeded.
     *
     * <p>A separate, cancellable notification rather than an update to the
     * ongoing one: the ongoing notification is removed a few lines later when
     * the service stops, and this is exactly the case where the message must
     * outlive the service. Tapping it opens the app, where the manual cleanup
     * button and the full log are.
     */
    private void notifySuMissing() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ALERT, "开机自启动问题", NotificationManager.IMPORTANCE_HIGH);
            ch.setShowBadge(true);
            nm.createNotificationChannel(ch);
        }
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL_ALERT)
                .setContentTitle("Vin的提权工具：未获得 root")
                .setContentText("执行已完成但 su 无响应，请打开应用手动清理或授权")
                .setStyle(new Notification.BigTextStyle().bigText(
                        "执行已完成，但在 " + (SU_WAIT_MS / 1000) + " 秒内 su 始终无响应。"
                        + "设备可能残留 dfroot 模块或处于 permissive 状态。"
                        + "请打开应用点「清理」，或在授权框出现时允许 su。"))
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(pi)
                .build();
        nm.notify(ALERT_NOTIFICATION_ID, n);
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL, "开机自启动", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        return new Notification.Builder(this, CHANNEL)
                .setContentTitle("Vin的提权工具 开机自启动")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Called by the boot receiver; keeps the call site short. */
    static void start(Context context) {
        Intent i = new Intent(context, AutorunService.class)
                .putExtra(EXTRA_FROM_BOOT, true);
        context.startForegroundService(i);
    }
}