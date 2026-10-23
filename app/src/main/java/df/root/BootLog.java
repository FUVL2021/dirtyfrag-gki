package df.root;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Full event log for everything that can lead to a run.
 *
 * <p>Written to the app's own files directory, which survives a reboot and does
 * not need storage permission. logcat is not enough here: the interesting part
 * happens in the first seconds after boot, when the ring buffer may still be
 * filling, and some ROMs drop early kernel/app lines. A file on disk is the only
 * record still there when the user goes looking afterwards.
 *
 * <p>Every write is flushed immediately. The process can be killed without
 * warning — the whole point of this log is to diagnose exactly that — so
 * buffering would lose the very lines that matter.
 *
 * <p>Deliberately no synchronisation: writes come from the receiver, the
 * service worker and the UI thread, append-only, and a torn line is harmless.
 */
public final class BootLog {

    private static final String TAG = "DFRoot/Boot";

    /** Keep the file small enough to paste into a chat in one go. */
    private static final long MAX_BYTES = 256 * 1024;
    private static final String NAME = "boot.log";

    private BootLog() {
    }

    /** The log file, for the UI to read and for the user to pull. */
    public static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), NAME);
    }

    /** Record one event. Never throws: logging must not break the thing it logs. */
    public static void i(Context context, String msg) {
        write(context, "I", msg, null);
        Log.i(TAG, msg);
    }

    public static void w(Context context, String msg) {
        write(context, "W", msg, null);
        Log.w(TAG, msg);
    }

    public static void e(Context context, String msg, Throwable t) {
        write(context, "E", msg, t);
        Log.e(TAG, msg, t);
    }

    /**
     * Record where this run was started from.
     *
     * <p>The recurring question "it ran and I did not ask it to" cannot be
     * answered from an entry point alone: a receiver, a service, a UI tap and a
     * stray thread all end up in the same run call. The call stack is the only
     * thing that says which one it was, so it is captured eagerly here rather
     * than reconstructed later.
     *
     * <p>Filtered to this package's frames: the interesting answer is always the
     * first few, and the full stack is still in logcat.
     */
    public static void origin(Context context, String what) {
        if (context == null) {
            return;
        }
        StringBuilder sb = new StringBuilder("origin: ").append(what);
        try {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            int shown = 0;
            for (StackTraceElement el : st) {
                String cls = el.getClassName();
                // Skip the logging machinery at the top of every stack.
                if (cls.equals(Thread.class.getName()) || cls.equals(BootLog.class.getName())) {
                    continue;
                }
                if (!cls.startsWith("df.root")) {
                    continue;
                }
                sb.append("\n    at ").append(cls).append('.').append(el.getMethodName())
                        .append('(').append(el.getFileName()).append(':')
                        .append(el.getLineNumber()).append(')');
                if (++shown >= 8) {
                    sb.append("\n    ...");
                    break;
                }
            }
            if (shown == 0) {
                sb.append("\n    (no df.root frame - invoked from outside the app)");
            }
        } catch (RuntimeException e) {
            sb.append("\n    (stack unavailable: ").append(e).append(')');
        }
        i(context, sb.toString());
    }

    /**
     * Start a new session, keeping the previous one.
     *
     * <p>Called once per run so a fresh run cannot be confused with the
     * leftovers of the last one, while still leaving the old content in place
     * for comparison when the new run fails early.
     */
    public static void sessionStart(Context context, String reason) {
        write(context, "=", "--- session start: " + reason + " ---", null);
    }

    /**
     * Mark the start of a process.
     *
     * <p>Distinguishes "the app was already running and did something" from
     * "the app was started by something". A process start immediately before a
     * run is the signature of a broadcast or service launch, not a UI tap.
     */
    public static void processStart(Context context, String why) {
        String uptime;
        try {
            uptime = (android.os.SystemClock.elapsedRealtime() / 1000) + "s";
        } catch (RuntimeException e) {
            uptime = "?";
        }
        write(context, "P", "--- process start (pid " + android.os.Process.myPid()
                + ", uptime " + uptime + "): " + why + " ---", null);
    }

    /**
     * Delete the log file.
     *
     * <p>Called only from settings, behind a confirmation: this file is the sole
     * record of runs that happened while the screen was off.
     */
    public static void clear(Context context) {
        File f = file(context);
        if (f.exists() && !f.delete()) {
            Log.w(TAG, "could not delete " + f);
        }
    }

    /** Current contents, for the in-app viewer. */
    public static String read(Context context) {
        File f = file(context);
        if (!f.exists()) {
            return "";
        }
        try {
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int off = 0;
                while (off < buf.length) {
                    int n = in.read(buf, off, buf.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
            }
            return new String(buf, "UTF-8");
        } catch (IOException | RuntimeException e) {
            return "(读取失败: " + e + ")";
        }
    }

    private static void write(Context context, String level, String msg, Throwable t) {
        if (context == null) {
            return;
        }
        File f = file(context);
        try {
            if (f.length() > MAX_BYTES) {
                // Cheap rotation: truncate in place rather than renaming, so the
                // path the user was told to look at never changes.
                try (FileOutputStream trunc = new FileOutputStream(f, false)) {
                    trunc.write("--- truncated, older entries dropped ---\n".getBytes("UTF-8"));
                }
            }
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            StringBuilder line = new StringBuilder();
            line.append(stamp).append(' ').append(level).append(' ')
                    .append(android.os.Process.myPid()).append(' ')
                    .append(msg).append('\n');
            if (t != null) {
                line.append("    ").append(t.getClass().getName());
                if (t.getMessage() != null) {
                    line.append(": ").append(t.getMessage());
                }
                line.append('\n');
                for (StackTraceElement el : t.getStackTrace()) {
                    line.append("      at ").append(el).append('\n');
                }
            }
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                Writer w = new OutputStreamWriter(out, "UTF-8");
                w.write(line.toString());
                w.flush();
            }
        } catch (IOException | RuntimeException ignored) {
            // Storage full, or the directory vanished mid-write. Fall back to
            // logcat only.
        }
    }
}