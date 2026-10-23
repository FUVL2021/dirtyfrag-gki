package df.root;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single screen: what this build will do on this kernel, one button to do it,
 * and a live log.
 *
 * <p>The layout is built in code rather than XML on purpose — the whole UI is
 * a handful of widgets, and keeping it here makes the project buildable with
 * nothing but the SDK (no resource merger, no theme).
 *
 * <p>Nothing on this screen is advisory: the kernel line and the module line
 * are the same values the native selector will use, and the run button is
 * disabled when they would lead nowhere.
 */
public class MainActivity extends Activity {

    private TextView kernelView;
    private Button runButton;
    private Button cleanupButton;
    private TextView suHint;
    private TextView logView;
    private ScrollView logScroll;

    /**
     * Whether the automatic residue sweep already ran in this process.
     *
     * <p>Reset after every exploit run: that is the only thing that can create
     * the residue the sweep looks for, so a new run invalidates the old answer.
     */
    private boolean sweptThisProcess;

    /** Shown only when autorun is off; hidden again once the user turns it on. */
    private LinearLayout autorunCard;

    /** One line describing the last boot run, when it did not simply succeed. */
    private TextView autorunResultView;

    private static final String PREFS = "dfroot";

    private volatile boolean running;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
        requestNotificationPermission();
        refreshKernelInfo();
        refreshAutorunCard();
        refreshSuState();
    }

    /**
     * Ask for POST_NOTIFICATIONS once.
     *
     * <p>Not cosmetic: on Android 13+ a foreground service whose notification
     * cannot be posted is not allowed to run, and the autorun service depends
     * on that. Denying it does not break the manual run, only the boot path,
     * which is why the request is made without blocking anything.
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return;
        }
        requestPermissions(
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The settings screen can flip the switch while this one is paused,
        // the boot receiver can disable autorun after a failed run, and root
        // can be granted or revoked while this app is in the background.
        refreshAutorunCard();
        refreshSuState();
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private ViewGroup buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        // Same reason as the disclaimer screen: a LinearLayout content view is
        // laid out edge to edge, so the title would sit under the status bar.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad, pad + insets.getSystemWindowInsetTop(),
                    pad, pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("Vin的提权工具");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        header.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView settings = new TextView(this);
        settings.setText("设置");
        settings.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        settings.setTextColor(Color.parseColor("#1565C0"));
        settings.setPadding(dp(12), dp(8), 0, dp(8));
        settings.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        header.addView(settings);

        root.addView(header);

        kernelView = new TextView(this);
        kernelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        kernelView.setPadding(0, dp(12), 0, dp(16));
        kernelView.setTextIsSelectable(true);
        root.addView(kernelView);

        runButton = new Button(this);
        runButton.setText("开始执行");
        runButton.setOnClickListener(v -> startRun());
        root.addView(runButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Cleanup is its own action, never a tail of the run: it needs a
        // separate su grant, and tying it to the run made a denied grant read
        // as a failed exploit.
        cleanupButton = new Button(this);
        cleanupButton.setText("清理（卸载模块 / 恢复 SELinux）");
        cleanupButton.setOnClickListener(v -> startCleanup());
        LinearLayout.LayoutParams cleanLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cleanLp.topMargin = dp(8);
        root.addView(cleanupButton, cleanLp);

        suHint = new TextView(this);
        suHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        suHint.setTextColor(Color.parseColor("#C62828"));
        suHint.setPadding(0, dp(6), 0, 0);
        suHint.setVisibility(ViewGroup.GONE);
        root.addView(suHint);

        root.addView(buildAutorunCard());

        autorunResultView = new TextView(this);
        autorunResultView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        autorunResultView.setTextColor(Color.parseColor("#B26A00"));
        autorunResultView.setBackgroundColor(Color.parseColor("#FFF8E1"));
        autorunResultView.setPadding(dp(14), dp(10), dp(14), dp(10));
        autorunResultView.setVisibility(ViewGroup.GONE);
        root.addView(autorunResultView);

        TextView warning = new TextView(this);
        warning.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        warning.setTextColor(Color.parseColor("#999999"));
        warning.setPadding(0, dp(8), 0, dp(8));
        warning.setText("失败通常表现为内核卡死（黑屏 / 无响应），长按电源键重启即可恢复。"
                + "自启动在首次失败后会自动关闭。");
        root.addView(warning);

        logView = new TextView(this);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setGravity(Gravity.TOP | Gravity.START);
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setTextIsSelectable(true);

        logScroll = new ScrollView(this);
        logScroll.addView(logView);
        root.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    // ------------------------------------------------------------------
    // Autorun nudge
    // ------------------------------------------------------------------

    /**
     * A card that only appears while autorun is off.
     *
     * <p>Autorun is off by default and nothing about the main screen would
     * otherwise say so, which makes it easy to leave disabled without
     * noticing. This states the current state in one line and offers the two
     * actions that matter: turn it on, or say not now.
     *
     * <p>Declining is respected: the card disappears until the next launch
     * rather than reappearing on every resume. Nothing is enabled behind the
     * user's back.
     */
    private LinearLayout buildAutorunCard() {
        autorunCard = new LinearLayout(this);
        autorunCard.setOrientation(LinearLayout.VERTICAL);
        autorunCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        autorunCard.setBackgroundColor(Color.parseColor("#FFF8E1"));

        TextView title = new TextView(this);
        title.setText("开机自启动：未开启");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#8D6E00"));
        autorunCard.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        body.setTextColor(Color.parseColor("#8D6E00"));
        body.setPadding(0, dp(4), 0, dp(10));
        body.setText("未开启时，每次重启都需要手动打开本应用并点「开始执行」。"
                + "开启后会在开机时自动执行一次；若执行失败会自动关闭，"
                + "因此不会导致反复重启。");
        autorunCard.addView(body);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);

        Button enable = new Button(this);
        enable.setText("开启自启动");
        enable.setOnClickListener(v -> setAutorun(true));
        actions.addView(enable, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button dismiss = new Button(this);
        dismiss.setText("暂不开启");
        dismiss.setOnClickListener(v -> setAutorun(false));
        actions.addView(dismiss, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        autorunCard.addView(actions);
        return autorunCard;
    }

    /** Read the preference the boot receiver gates on. */
    private boolean isAutorunOn() {
        return BootReceiver.autorunOn(this);
    }

    private void setAutorun(boolean on) {
        BootReceiver.setAutorun(this, on);
        refreshAutorunCard();
    }

    private void refreshAutorunCard() {
        if (autorunCard != null) {
            autorunCard.setVisibility(isAutorunOn() ? ViewGroup.GONE : ViewGroup.VISIBLE);
        }
        // A boot run that was skipped or failed leaves a note here. It is the
        // only way the user learns about something that happened while the app
        // was not on screen, so it outlives the run that produced it.
        if (autorunResultView != null) {
            String r = BootReceiver.lastResult(this);
            if (r == null || r.equals("ok")) {
                autorunResultView.setVisibility(ViewGroup.GONE);
            } else {
                autorunResultView.setVisibility(ViewGroup.VISIBLE);
                autorunResultView.setText(describeResult(r));
            }
        }
    }

    /**
     * Turns the stored boot result into something a person can act on.
     *
     * <p>"skipped: no root" is by far the most important one: a background app
     * cannot show the su prompt, so the user has to open this screen and grant
     * it, after which the next boot will work.
     */
    private static String describeResult(String r) {
        if (r.equals("skipped: unsupported kernel")) {
            return "上次开机自启动被跳过：当前内核不受支持。";
        }
        if (r.equals("skipped: autorun off")) {
            return "上次开机自启动被跳过：自启动已关闭。";
        }
        if (r.equals("skipped: need choice")) {
            return "上次开机自启动被跳过：同时装了多个 su 管理器，还没选过要哪个接管。"
                    + "打开本应用执行一次并选好，之后开机就会自动用这个选择。";
        }
        if (r.equals("skipped: no su manager")) {
            return "上次开机自启动被跳过：没有检测到 su 管理器"
                    + "（KernelSU / SukiSU / ReSukiSU）。安装后即可。";
        }
        if (r.startsWith("failed: service start")) {
            return "上次开机自启动失败：系统拒绝启动后台服务。"
                    + "请在系统设置里允许本应用显示通知后重试。";
        }
        if (r.startsWith("failed")) {
            return "上次开机自启动执行失败（" + r + "），已自动关闭自启动，避免反复重启。";
        }
        return r;
    }

    private void refreshKernelInfo() {
        KernelInfo info = KernelInfo.detect();
        // The full release string is build metadata; only the head and the
        // android tag affect the decision. The run log carries the whole thing.
        String text = "模块     " + info.describe() + "\n"
                + "接管     " + SuManager.describe(this) + "\n"
                + "版本     " + BuildInfo.summary(this);
        kernelView.setText(text);
        if (info.unsupported) {
            kernelView.setText("内核     " + info.rawRelease + "\n" + text);
            kernelView.setTextColor(Color.parseColor("#C62828"));
            runButton.setEnabled(false);
            runButton.setText("内核不支持");
        }
        if (!ExploitRunner.binary(this).exists()) {
            runButton.setEnabled(false);
            kernelView.append("\n\n此构建缺少 native 二进制文件");
        }
    }

    // ------------------------------------------------------------------
    // Cleanup
    // ------------------------------------------------------------------

    /**
     * Check su off the UI thread and grey the button out when it is missing.
     *
     * <p>Called on resume as well as at startup: root can be revoked, the
     * manager can be uninstalled, and a stale enabled button would just throw
     * a permission dialog the user cannot satisfy.
     */
    private void refreshSuState() {
        if (cleanupButton == null) {
            return;
        }
        cleanupButton.setEnabled(false);
        suHint.setVisibility(ViewGroup.GONE);

        worker.execute(() -> {
            boolean ok = ExploitRunner.suAvailable();
            if (ok) {
                // Root just became available, which is the only moment this
                // check can run at all. Ask whether a previous run left the
                // module loaded, /dev markers around, or SELinux permissive, and
                // clean up without waiting to be told: those are leftovers the
                // user has no way to notice on their own.
                autoSweepIfNeeded();
            }
            runOnUiThread(() -> {
                cleanupButton.setEnabled(ok);
                if (ok) {
                    suHint.setVisibility(ViewGroup.GONE);
                } else {
                    // With no root there is nothing to clean up yet — the module
                    // and the SELinux switch only exist after a run. Say that
                    // plainly instead of asking for a permission that the
                    // exploit itself is supposed to grant.
                    suHint.setText("当前没有 root：不需要清理。清理只在执行过后才有意义，"
                            + "用来卸载模块并恢复 SELinux。");
                    suHint.setVisibility(ViewGroup.VISIBLE);
                }
            });
        });
    }

    /**
     * Detect residues and clean them up, reporting to the run log.
     *
     * <p>Runs on the worker thread. Only one sweep per process lifetime: the
     * result cannot change unless something runs, and {@link #startRun} resets
     * the flag when it does. Without that, every resume would pay a su round
     * trip for the same answer.
     *
     * <p>Acting without asking is deliberate here. A loaded module, stray
     * {@code /dev} nodes or a permissive SELinux are not states the user chose
     * and cannot easily see; the safe direction is always to restore them.
     */
    private void autoSweepIfNeeded() {
        if (sweptThisProcess) {
            return;
        }
        sweptThisProcess = true;
        // Logged because "it ran when I opened the app" has to be attributable:
        // this sweep is the only thing the main screen does without a tap, and
        // it must be possible to tell it apart from a real exploit run.
        BootLog.i(this, "sweep: checking residue on open");

        ExploitRunner.Residue r = ExploitRunner.detectResidue();
        BootLog.i(this, "sweep: residue=" + r + " (" + ExploitRunner.describe(r) + ")");
        if (r == ExploitRunner.Residue.CLEAN) {
            appendLog("残留检测：" + ExploitRunner.describe(r));
            return;
        }
        if (r == ExploitRunner.Residue.UNKNOWN) {
            // su answered a moment ago, so this is a transient failure rather
            // than "no root". Do not claim the device is clean.
            appendLog("残留检测：" + ExploitRunner.describe(r) + "，本次跳过自动清理");
            return;
        }

        // Report only. The actual cleanup runs in AutorunService at boot, which is
        // where it has to be: waiting for this screen to open meant residue from
        // a dead run could sit there for days. Running it here as well would
        // duplicate that work and log the same cleanup twice.
        appendLog("残留检测：" + ExploitRunner.describe(r)
                + "（开机时会自动清理，也可点下方按钮立即处理）");
    }

    private void startCleanup() {
        if (running) {
            return;
        }
        running = true;
        cleanupButton.setEnabled(false);
        runButton.setEnabled(false);
        logView.setText("");
        appendLog("清理中…");

        worker.execute(() -> {
            ExploitRunner.cleanupWith(this, line -> {
                // Same file as the run, so a cleanup that appeared to do nothing
                // can be checked against what actually happened.
                BootLog.i(this, "clean| " + line);
                runOnUiThread(() -> appendLog(line));
            });
            runOnUiThread(() -> {
                running = false;
                runButton.setEnabled(true);
                appendLog("");
                appendLog("清理结束。");
                // Re-check: the su used by cleanup may itself have been denied.
                refreshSuState();
            });
        });
    }

    // ------------------------------------------------------------------
    // Run
    // ------------------------------------------------------------------

    private void startRun() {
        if (running) {
            return;
        }
        // Resolve the manager before anything else: the picker needs the UI
        // thread and a decision from the user, and the run must not start until
        // it has one. Asking here rather than relying on run()'s refusal keeps
        // the picker in front of the user instead of behind a log line.
        SuManager.Impl manager = SuManager.chosen(this);
        if (manager == null) {
            java.util.List<SuManager.Impl> have = SuManager.installed(this);
            if (have.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("未检测到 su 管理器")
                        .setMessage("本工具需要把 root 交给一个 su 管理器来接管。"
                                + "请先安装以下任一个后再执行：\n\n"
                                + "· KernelSU\n· SukiSU Ultra\n· ReSukiSU\n\n"
                                + "如果确认已经安装却仍然显示这里，说明系统限制了本应用"
                                + "读取应用列表。请在系统设置里找到本应用，"
                                + "允许「获取应用列表 / 读取已安装应用」权限后重试。")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            if (have.size() > 1) {
                promptForManager(have);
                return;
            }
            // Exactly one: no question worth asking.
            manager = have.get(0);
        }
        beginRun(manager);
    }

    /**
     * Ask which manager should take over, when more than one is installed.
     *
     * <p>Two or more installed is a real case, not a defensive branch: the
     * three managers coexist happily and a user may keep both a stable and a
     * fork build. They are indistinguishable at the binary level (each ships
     * {@code libksud.so}) but not in behaviour — whichever one runs starts its
     * own daemon and shows its own grant prompt — so the choice has to be the
     * user's.
     *
     * <p>Only installed ones are offered. Offering an absent manager would
     * produce a run that fails at staging for a reason the user already knows.
     */
    private void promptForManager(java.util.List<SuManager.Impl> available) {
        final SuManager.Impl[] items = available.toArray(new SuManager.Impl[0]);
        CharSequence[] labels = new CharSequence[items.length];
        for (int i = 0; i < items.length; i++) {
            labels[i] = items[i].label;
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 su 管理器")
                .setMessage("检测到 " + items.length + " 个 su 管理器。"
                        + "它们都会提供 su，但接管 root 的是你选的那一个"
                        + "（授权弹窗也由它显示）。选择会被记住，之后不再询问。")
                .setItems(labels, (d, which) -> {
                    SuManager.remember(this, items[which]);
                    beginRun(items[which]);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** The run itself, with the manager already decided. */
    private void beginRun(SuManager.Impl manager) {
        BootLog.origin(this, "MainActivity.beginRun(" + manager + ")");
        // A manual tap does not bypass the one-run-per-boot rule when the boot
        // autorun already ran or is about to: two runs against the same kernel
        // is what produced the half-patched state (libc++ hooked, module gone)
        // the old build kept getting stuck in. Running twice is only allowed
        // after an explicit confirmation, and it is not the default.
        if (BootReceiver.alreadyRanThisBoot(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("本次开机已经执行过")
                    .setMessage("同一次开机内重复执行会让内核处于半打补丁状态"
                            + "（libc++ 已挂钩但模块已卸载），这是之前残留清理不掉的直接原因。\n\n"
                            + "通常不需要再执行：重启一次即可重新开始。\n"
                            + "确实要冒险再跑一次的，可以点「强制再执行」。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("强制再执行", (d, w) -> reallyBeginRun(manager))
                    .show();
            return;
        }
        BootReceiver.claimThisBoot(this);
        reallyBeginRun(manager);
    }

    /** Actually start the run. Only reached after the boot guard passed. */
    private void reallyBeginRun(SuManager.Impl manager) {
        running = true;
        runButton.setEnabled(false);
        logView.setText("");
        appendLog("正在启动…");
        // A run is the only thing that can create residue, so the previous
        // "nothing to clean" answer no longer holds.
        sweptThisProcess = false;
        // Manual runs land in the same file as boot runs, tagged, so one log
        // covers both and the two can never be confused afterwards.
        BootLog.sessionStart(this, "manual run from UI");
        BootLog.i(this, "manual: su manager " + manager.pkg);
        BootLog.i(this, "manual: invoking exploit binary");

        worker.execute(() -> {
            int rc = ExploitRunner.run(this, line -> {
                BootLog.i(this, "exp| " + line);
                runOnUiThread(() -> appendLog(line));
            }, manager);
            BootLog.i(this, "manual: exploit returned rc=" + rc);
            int finalRc = rc;
            runOnUiThread(() -> {
                running = false;
                runButton.setEnabled(true);
                appendLog("");
                appendLog(finalRc == 0 ? "完成。" : "失败（退出码 " + finalRc + "）。");
            });
        });
    }

    /**
     * Append one line to the on-screen run log. Safe from any thread.
     *
     * <p>The main-thread hop lives here rather than at each call site: the
     * exploit reports from a worker thread, cleanup reports from the same
     * worker, and the automatic residue sweep reports from it too. Putting the
     * check in one place means a new background caller cannot reintroduce
     * CalledFromWrongThreadException by forgetting a runOnUiThread wrapper.
     */
    private void appendLog(String line) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(() -> appendLog(line));
            return;
        }
        logView.append(line + "\n");
        logScroll.post(() -> logScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}