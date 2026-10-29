package df.root;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/**
 * Everything that is not "run it": which kernel this is, which module that
 * resolves to, what else the build ships, and the autorun switch.
 *
 * <p>The module list is the honest version of "supported kernels". Listing all
 * eight without marking one would imply the app can use any of them at
 * runtime; it can only use the one the native selector picks. The selected row
 * is the only one that will actually be loaded, so it is the only one marked.
 */
public class SettingsActivity extends Activity {

    private Switch autorun;
    private boolean restoring;

    /** The "接管 root" row; the value half is replaced, not re-created. */
    private LinearLayout suManagerValue;
    private TextView suManagerHint;

    /** For the occasional disk read that must not touch the UI thread. */
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildUi());
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Managers can be installed or uninstalled while this screen is in the
        // background — the whole reason the user opened it may have been to
        // install one. Re-read rather than trust the state from onCreate.
        refreshSuManager();
    }

    @Override
    protected void onDestroy() {
        // The activity is short-lived and the queue only ever holds a log read,
        // so shutting it down here cannot cut off anything worth keeping.
        io.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private ViewGroup buildUi() {
        // The content is a scrolling column: the module list alone is a fixed
        // 200dp, and the maintenance buttons added below it overflow a phone
        // screen, leaving the last controls unreachable.
        ScrollView screen = new ScrollView(this);
        screen.setFillViewport(true);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);

        // Insets go on the inner column, not the ScrollView: padding on a
        // scroll container does not follow scrolled content on all versions.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(pad, pad + insets.getSystemWindowInsetTop(),
                    pad, pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        screen.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        root.addView(sectionLabel("设备"));

        KernelInfo info = KernelInfo.detect();

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        card.setBackgroundColor(Color.parseColor("#F2F2F2"));
        card.addView(keyValue("内核", shortenKernel(info.rawRelease)));
        // Two different numbers that used to share one row. "安卓版本" showed
        // the KMI tag parsed out of the kernel release string, which is the
        // Android release the kernel was built for - on a device whose vendor
        // shipped an old kernel with a newer system these disagree, and the
        // settings card was reporting the wrong one under the right label.
        card.addView(keyValue("系统版本", "Android " + Build.VERSION.RELEASE
                + (Build.VERSION.SDK_INT >= 23 ? " (API " + Build.VERSION.SDK_INT + ")" : "")));
        card.addView(keyValue("内核 KMI",
                info.androidRelease == 0 ? "?" : "android" + info.androidRelease));
        card.addView(keyValue("内核版本", info.kernelMajor == 0
                ? "?" : info.kernelMajor + "." + info.kernelMinor));
        card.addView(keyValue("模块", info.kmi));
        card.addView(keyValue("状态", statusText(info)));
        root.addView(card);

        root.addView(sectionLabel("内置模块"));

        TextView hint = new TextView(this);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        hint.setTextColor(Color.parseColor("#777777"));
        hint.setPadding(0, 0, 0, dp(8));
        hint.setText("高亮的那一行是当前内核实际加载的模块。其余模块让同一个安装包"
                + "可以装到多台设备上；运行时不会在它们之间切换。");
        root.addView(hint);

        // Fixed height, not weighted: the list must not push the startup
        // controls down and read as if the switch belonged to it.
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        for (KernelInfo.Module m : info.modulesWithSelection()) {
            list.addView(moduleRow(m));
        }

        // The module list is only a few rows tall, so it no longer scrolls on
        // its own. Nested scrolling inside the page-level ScrollView makes the
        // gesture ambiguous: a drag that starts on the list moves the list, not
        // the page, and the controls below stay out of reach.
        list.setBackgroundColor(Color.parseColor("#FAFAFA"));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(sectionLabel("su 管理器"));

        // Stated as fact, not as a guess: `value` is empty until the user picks,
        // and refreshSuManager() is what fills it in.
        suManagerValue = keyValue("接管 root", SuManager.describe(this));
        root.addView(suManagerValue);

        suManagerHint = new TextView(this);
        suManagerHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        suManagerHint.setTextColor(Color.parseColor("#777777"));
        suManagerHint.setPadding(0, dp(2), 0, dp(8));
        root.addView(suManagerHint);

        Button pick = new Button(this);
        pick.setText("选择 su 管理器");
        pick.setOnClickListener(v -> pickSuManager());
        root.addView(pick);

        refreshSuManager();

        root.addView(sectionLabel("开机自启动"));

        autorun = new Switch(this);
        autorun.setText("重启后自动执行");
        autorun.setOnCheckedChangeListener((b, checked) -> {
            if (!restoring) {
                setAutorun(checked);
            }
        });
        root.addView(autorun);

        TextView warn = new TextView(this);
        warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        warn.setTextColor(Color.parseColor("#999999"));
        warn.setPadding(0, dp(4), 0, dp(12));
        warn.setText("首次执行失败后，自启动会自动关闭，避免反复重启。"
                + "确认设备正常后可以在这里重新打开。");
        root.addView(warn);

        root.addView(sectionLabel("维护"));

        Button reset = new Button(this);
        reset.setText("重置自启动锁定");
        reset.setOnClickListener(v -> confirmReset());
        root.addView(reset);

        // Diagnostics are behind a switch: a normal user never needs them, and
        // this is the screen someone opens afterwards when a boot run did
        // something they did not expect.
        root.addView(sectionLabel("开发者"));

        // The switch itself. Off by default, and the only way to reach the log.
        CheckBox devSwitch = new CheckBox(this);
        devSwitch.setText("开发者模式");
        devSwitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        devSwitch.setChecked(DevOptions.enabled(this));
        root.addView(devSwitch);

        // Everything the switch controls, in one container: hiding five views
        // one by one is how one of them ends up visible on a release build.
        LinearLayout devPanel = new LinearLayout(this);
        devPanel.setOrientation(LinearLayout.VERTICAL);
        devPanel.setVisibility(devSwitch.isChecked() ? ViewGroup.VISIBLE : ViewGroup.GONE);

        TextView devHint = new TextView(this);
        devHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        devHint.setTextColor(Color.parseColor("#999999"));
        devHint.setPadding(0, 0, 0, dp(8));
        devHint.setText("运行日志记录了每一次执行的触发来源、调用栈和 exploit 的每一行输出。"
                + "排查「它自己执行了」「清理不干净」这类问题时需要它，日常使用用不到。");
        devPanel.addView(devHint);

        Button viewLog = new Button(this);
        viewLog.setText("查看运行日志");
        viewLog.setOnClickListener(v -> {
            // Off the UI thread: this is the screen someone reaches when a run
            // already went wrong, and a stalled read there reads as a second
            // failure. One shared executor, not one per click.
            io.execute(() -> {
                String body = BootLogViewer.read(this);
                runOnUiThread(() -> BootLogViewer.show(this, body));
            });
        });
        devPanel.addView(viewLog);

        Button clearLog = new Button(this);
        clearLog.setText("清空日志");
        clearLog.setOnClickListener(v -> confirmClearLog());
        devPanel.addView(clearLog);

        TextView logHint = new TextView(this);
        logHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        logHint.setTextColor(Color.parseColor("#999999"));
        logHint.setPadding(0, dp(4), 0, dp(12));
        logHint.setText("日志保存在应用私有目录，重启后仍然保留。\n路径："
                + BootLog.file(this));
        devPanel.addView(logHint);

        root.addView(devPanel);

        devSwitch.setOnCheckedChangeListener((b, checked) -> {
            DevOptions.setEnabled(this, checked);
            devPanel.setVisibility(checked ? ViewGroup.VISIBLE : ViewGroup.GONE);
        });

        restoreAutorun();
        return screen;
    }

    /**
     * Clearing the log is destructive and irreversible, so it asks first.
     *
     * <p>Not offered as a silent side effect of anything else: the log is the
     * only record of a boot run that happened while the screen was off.
     */
    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle("清空日志？")
                .setMessage("会删除全部开机日志。仅在确认不再需要排查问题时这么做。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    BootLog.clear(this);
                    ((android.widget.Toast) android.widget.Toast
                            .makeText(this, "日志已清空", android.widget.Toast.LENGTH_SHORT))
                            .show();
                })
                .show();
    }

    // ------------------------------------------------------------------
    // su manager
    // ------------------------------------------------------------------

    /**
     * Re-read the manager state and rewrite the row.
     *
     * <p>Also called after a pick: choosing a manager has to be visible
     * immediately, otherwise the button looks like it did nothing.
     */
    private void refreshSuManager() {
        if (suManagerValue == null) {
            return;
        }
        java.util.List<SuManager.Impl> have = SuManager.installed(this);
        SuManager.Impl c = SuManager.chosen(this);

        // The value is the last child of the row; replacing it keeps the label
        // column width stable instead of re-laying out the whole row.
        android.view.View last = suManagerValue.getChildAt(suManagerValue.getChildCount() - 1);
        if (last instanceof TextView) {
            ((TextView) last).setText(SuManager.describe(this));
            ((TextView) last).setTextColor(c != null
                    ? Color.parseColor("#1B5E20")
                    : (have.isEmpty() ? Color.parseColor("#C62828") : Color.parseColor("#B26A00")));
        }

        if (have.isEmpty()) {
            suManagerHint.setText("未安装任何受支持的 su 管理器。执行前需要先装一个："
                    + "KernelSU、SukiSU Ultra、ReSukiSU。"
                    + "\n若确认已安装仍显示这一行，是系统限制了本应用读取应用列表，"
                    + "请在系统设置里允许「获取应用列表」权限。");
        } else if (have.size() == 1) {
            suManagerHint.setText("只检测到一个，执行时会直接使用它，不需要选择。");
        } else if (c != null) {
            suManagerHint.setText("同时装了 " + have.size() + " 个。当前由 "
                    + c.label + " 接管，点下面的按钮可以换成另一个。");
        } else {
            suManagerHint.setText("同时装了 " + have.size() + " 个，还没选过。"
                    + "执行时会询问你要哪个接管。");
        }
    }

    /**
     * Pick a manager by hand, whatever the current state.
     *
     * <p>Offered even when only one is installed: the row above already says
     * which one will be used, and hiding the button in that case would make it
     * appear and disappear depending on what is installed, which is harder to
     * reason about than a button that is always there and sometimes has a
     * single option.
     */
    private void pickSuManager() {
        final java.util.List<SuManager.Impl> have = SuManager.installed(this);
        if (have.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("未安装 su 管理器")
                    .setMessage("请先安装 KernelSU、SukiSU Ultra 或 ReSukiSU 中的任一个，"
                            + "再回来选择。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        final SuManager.Impl[] items = have.toArray(new SuManager.Impl[0]);
        CharSequence[] labels = new CharSequence[items.length];
        for (int i = 0; i < items.length; i++) {
            labels[i] = items[i].label;
        }
        SuManager.Impl cur = SuManager.chosen(this);
        int checked = 0;
        for (int i = 0; i < items.length; i++) {
            if (items[i] == cur) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle("选择 su 管理器")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    SuManager.remember(this, items[which]);
                    refreshSuManager();
                    d.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private String statusText(KernelInfo info) {
        if (info.unsupported) {
            return "不支持";
        }
        return info.fallback ? "支持（回退匹配）" : "支持";
    }

    private TextView sectionLabel(String text) {
        TextView v = new TextView(this);
        v.setText(text.toUpperCase(java.util.Locale.US));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        v.setTypeface(Typeface.DEFAULT_BOLD);
        v.setTextColor(Color.parseColor("#999999"));
        v.setPadding(0, dp(18), 0, dp(8));
        return v;
    }

    /**
     * Two-column label/value. Beats concatenation because the kernel string
     * and the module name have very different lengths and a single TextView
     * wraps mid-token on a narrow screen.
     */
    private LinearLayout keyValue(String key, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(3), 0, dp(3));

        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        k.setTextColor(Color.parseColor("#777777"));
        row.addView(k, new LinearLayout.LayoutParams(dp(118),
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        return row;
    }

    /**
     * Long kernel strings are mostly build metadata. Keep the part that
     * decides the module ({@code major.minor-androidNN}) and drop the rest,
     * which the full string still carries in the run log.
     */
    private String shortenKernel(String release) {
        int dash = release.indexOf('-');
        if (dash <= 0) {
            return release;
        }
        String head = release.substring(0, dash);          // 5.15.178
        int tag = release.indexOf("android");
        if (tag < 0) {
            return head;
        }
        int end = tag + "android".length();
        while (end < release.length() && Character.isDigit(release.charAt(end))) {
            end++;
        }
        return head + "-" + release.substring(tag, end);   // 5.15.178-android13
    }

    /** Selected row is marked by weight and colour, not by a glyph. */
    private TextView moduleRow(KernelInfo.Module m) {
        TextView v = new TextView(this);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        v.setTypeface(Typeface.MONOSPACE);
        v.setPadding(dp(10), dp(6), dp(10), dp(6));

        if (m.selected) {
            v.setText(m.kmi() + "   " + m.name + "   ← 已加载");
            v.setTextColor(Color.parseColor("#1B5E20"));
            v.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            v.setBackgroundColor(Color.parseColor("#E8F5E9"));
        } else {
            v.setText(m.kmi() + "   " + m.name);
            v.setTextColor(Color.parseColor("#888888"));
        }
        return v;
    }

    // ------------------------------------------------------------------
    // Autorun
    // ------------------------------------------------------------------

    private void restoreAutorun() {
        boolean on = BootReceiver.autorunOn(this);
        restoring = true;
        autorun.setChecked(on);
        restoring = false;
    }

    private void setAutorun(boolean on) {
        BootReceiver.setAutorun(this, on);
    }

    /**
     * Clearing the interlock is a deliberate act, not a toggle: the receiver
     * may have cleared the flag precisely because a run left things broken.
     */
    private void confirmReset() {
        new AlertDialog.Builder(this)
                .setTitle("重置自启动锁定？")
                .setMessage("会重新开启开机自启。请先确认设备能正常开机，再执行此操作。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", (d, w) -> {
                    BootReceiver.setAutorun(this, true);
                    restoreAutorun();
                })
                .show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }
}
