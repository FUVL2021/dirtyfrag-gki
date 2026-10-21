package df.root;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Append-only text log for the boot autorun path.
 *
 * <p>Written to the app's own files directory, which survives a reboot and does
 * not need storage permission. logcat is not enough here: the interesting part
 * happens in the first seconds after boot, when the ring buffer may still be
 * filling, and some ROMs drop early kernel/app lines. A file on disk is the only
 * record that is still there when the user goes looking afterwards.
 *
 * <p>Every write is flushed immediately. The process can be killed without
 * warning (the whole point of this log is to diagnose exactly that), so buffering
 * would lose the very lines that matter.
 *
 * <p>Deliberately no synchronisation: writes come from the receiver and from the
 * service's single worker thread, append-only, and a torn line is harmless.
 */
public final class BootLog {

    private static final String TAG = "DFRoot/Boot";

    /** Keep the file small enough to paste into a chat in one go. */
    private static final long MAX_BYTES = 128 * 1024;
    private static final String NAME = "boot.log";

    private BootLog() {
    }

    /** The log file, for the UI to read and for the user to pull. */
    public static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), NAME);
    }

    /** Record one event. Never throws: logging must not break the thing it logs. */
    public static void i(Context context, String msg) {
        write(context, "I", msg);
        Log.i(TAG, msg);
    }

    public static void w(Context context, String msg) {
        write(context, "W", msg);
        Log.w(TAG, msg);
    }

    public static void e(Context context, String msg, Throwable t) {
        String line = msg + (t == null ? "" : ": " + t.getClass().getName()
                + (t.getMessage() == null ? "" : " (" + t.getMessage() + ")"));
        write(context, "E", line);
        Log.e(TAG, msg, t);
    }

    /**
     * Start a new session, keeping the previous one.
     *
     * <p>Called once per boot so that a fresh run cannot be confused with the
     * leftovers of the last one, while still leaving the old content in place
     * for comparison when the new run fails early.
     */
    public static void sessionStart(Context context, String reason) {
        write(context, "=", "--- session start: " + reason + " ---");
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

    private static void write(Context context, String level, String msg) {
        if (context == null) {
            return;
        }
        File f = file(context);
        try {
            if (f.length() > MAX_BYTES) {
                // Cheap rotation: truncate in place rather than renaming, so the
                // path the user was told to look at never changes.
                try (FileOutputStream trunc = new FileOutputStream(f, false)) {
                    trunc.write(("--- truncated, older entries dropped ---\n").getBytes("UTF-8"));
                }
            }
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String line = stamp + " " + level + " " + android.os.Process.myPid() + " " + msg + "\n";
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                Writer w = new OutputStreamWriter(out, "UTF-8");
                w.write(line);
                w.flush();
            }
        } catch (IOException | RuntimeException ignored) {
            // Storage full, or the directory vanished mid-write. Fall back to
            // logcat only.
        }
    }
}
