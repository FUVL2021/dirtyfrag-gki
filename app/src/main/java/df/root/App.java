package df.root;

import android.app.Application;

/**
 * Records every process start.
 *
 * <p>Without this the log cannot distinguish two very different stories:
 *
 * <ul>
 *   <li>the app was already running, and something inside it started a run;</li>
 *   <li>the app was not running, and something outside it started the process —
 *       a broadcast, a service, an alarm.</li>
 * </ul>
 *
 * <p>From inside {@code run()} both look identical. A process-start line
 * immediately before a run is the signature of the second case, and that is the
 * difference between "the user tapped a button" and "it woke up on its own".
 *
 * <p>Registered in the manifest as {@code android:name=".App"}. Nothing else
 * belongs here: an Application subclass that does work at startup changes when
 * the process is created, and that would itself be a source of surprise runs.
 */
public final class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Process start, not app start: Android creates this once per process.
        BootLog.processStart(this, "Application.onCreate");
    }
}