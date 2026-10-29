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

        /* 不能传 ScrollView.LayoutParams。
         *
         * 它继承 FrameLayout.LayoutParams, 而 root 是 LinearLayout,
         * 纵向测量走的是 LinearLayout.LayoutParams。类型对不上时
         * WRAP_CONTENT 高度不参与累加, 整列按一屏算完, 超出部分既不显示
         * 也不可滚动 —— su 管理器和开发者两个区段因此整个消失。
         * 不传 LayoutParams, 由 ScrollView.addView 生成正确的类型。 */
        screen.addView(root);

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

        if (SuManager.customChosen(this)) {
            String p = SuManager.customPath(this);
            suManagerHint.setText("正在使用自定义路径：\n" + (p == null ? "（未填）" : p)
                    + "\n点下面的按钮可以改，或换回列表里的面具。");
            return;
        }

        if (have.isEmpty()) {
            // Two different failures look identical here, so name both. Telling
            // a user who has KernelSU installed to "install one" sends them to
            // fix the wrong thing.
            suManagerHint.setText("未检测到 KernelSU / SukiSU Ultra / ReSukiSU。"
                    + "\n可能是没装，也可能是系统限制了本应用读取应用列表"
                    + "（部分厂商 ROM 默认如此）。"
                    + "\n点下面的按钮可以直接从这三个里选一个，"
                    + "用别的面具（KernelSU-Next、APatch 等）则选「自定义」填路径。");
        } else if (have.size() == 1) {
            SuManager.Impl only = have.get(0);
            suManagerHint.setText(only.viaOverrideOnly(this)
                    ? "系统检测不到，当前使用手动指定的路径：\n" + only.override(this)
                    : "只检测到一个，执行时会直接使用它，不需要选择。"
                    + "\n用别的面具可以点下面的按钮选「自定义」。");
        } else if (c != null) {
            suManagerHint.setText("同时装了 " + have.size() + " 个。当前由 "
                    + c.label + " 接管，点下面的按钮可以换成另一个。");
        } else {
            suManagerHint.setText("同时装了 " + have.size() + " 个，还没选过。"
                    + "执行时会询问你要哪个接管。");
        }
    }

    /**
     * Pick a manager by hand.
     *
     * <p>Always offers all three, detected or not. Refusing to open the picker
     * when nothing was detected — the old behaviour — left no way forward on a
     * ROM that hides the manager package: the app would insist nothing was
     * installed while the user could see it in their own launcher.
     *
     * <p>Detected managers are listed first and marked, so the common case stays
     * a single tap; undetected ones are still selectable and are marked as
     * needing a path.
     */
    private void pickSuManager() {
        final java.util.List<SuManager.Impl> detected = SuManager.installed(this);
        final java.util.List<SuManager.Impl> items = SuManager.all();

        // Detected first, so the usual case is the first row.
        items.sort((a, b) -> {
            boolean da = detected.contains(a), db = detected.contains(b);
            if (da != db) {
                return da ? -1 : 1;
            }
            return a.ordinal() - b.ordinal();
        });

        final SuManager.Impl cur = SuManager.chosen(this);
        final boolean customCur = SuManager.customChosen(this);

        // The three known managers, then one extra row for anything else.
        CharSequence[] labels = new CharSequence[items.size() + 1];
        int checked = customCur ? items.size() : -1;
        for (int i = 0; i < items.size(); i++) {
            SuManager.Impl m = items.get(i);
            // Badge says where the path will come from. "未检测到" is not a
            // blocker any more: picking it still means "use this manager", and
            // staging reads its built-in path.
            StringBuilder sb = new StringBuilder(m.label);
            if (detected.contains(m)) {
                sb.append("（已检测到）");
            } else if (m.override(this) != null && !m.override(this).isEmpty()) {
                sb.append("（手动路径）");
            } else {
                sb.append("（未检测到）");
            }
            labels[i] = sb.toString();
            if (m == cur) {
                checked = i;
            }
        }
        String customPath = SuManager.customPath(this);
        labels[items.size()] = customPath == null || customPath.isEmpty()
                ? "自定义（其他面具，填路径）"
                : "自定义（" + customPath + "）";

        new AlertDialog.Builder(this)
                .setTitle("选择 su 管理器")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    d.dismiss();
                    if (which == items.size()) {
                        askCustomPath();
                        return;
                    }
                    SuManager.Impl m = items.get(which);
                    // A pick is a pick: if the manager is installed its path is
                    // known, so honour it immediately. Only an undetected one
                    // still needs a typed path.
                    if (m.ksudSourceWithOverride(this) != null) {
                        SuManager.remember(this, m);
                        refreshSuManager();
                    } else {
                        askKsudPath(m);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * Ask for the path of a manager not in {@link SuManager.Impl}.
     *
     * <p>Saves and selects in one step: a path with no manager behind it is
     * only meaningful as the active choice, so there is no separate "save but
     * do not use" state to get stuck in.
     */
    private void askCustomPath() {
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("/data/app/~~…/…/lib/arm64/libksud.so");
        String existing = SuManager.customPath(this);
        if (existing != null) {
            input.setText(existing);
        }
        input.setSingleLine(false);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(input);

        new AlertDialog.Builder(this)
                .setTitle("自定义 su 管理器")
                .setMessage("用于 KernelSU-Next、APatch 等不在列表里的面具。"
                        + "填它的 ksud 可执行文件（或 libksud.so）完整路径。"
                        + "\n\n留空则取消自定义，回到自动检测。")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) {
                        SuManager.setCustomPath(this, null);
                        SuManager.forget(this);
                    } else {
                        SuManager.setCustomPath(this, path);
                        SuManager.rememberCustom(this);
                    }
                    refreshSuManager();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** The already-chosen manager, else the only detected one, else null. */
    private SuManager.Impl chosenOrFirst(java.util.List<SuManager.Impl> detected) {
        SuManager.Impl c = SuManager.chosen(this);
        if (c != null) {
            return c;
        }
        return detected.size() == 1 ? detected.get(0) : null;
    }

    private void askKsudPathFor(SuManager.Impl m) {
        if (m == null) {
            // Nothing chosen and nothing detected: ask which manager first.
            pickSuManager();
            return;
        }
        askKsudPath(m);
    }

    /**
     * Ask for the {@code libksud.so} path of one manager.
     *
     * <p>Pre-filled with the resolved path when there is one, so the dialog
     * doubles as "show me where this lives". Validation is deliberately weak:
     * existence only, not a binary check. The likely reason the field is being
     * used at all is that the package manager cannot see the file, and a
     * stricter check would reject the one path that actually works.
     */
    private void askKsudPath(SuManager.Impl m) {
        java.io.File resolved = m.ksudSource(this);
        String existing = m.override(this);
        String initial = existing != null ? existing
                : (resolved != null ? resolved.getAbsolutePath() : "");

        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("/data/app/~~…/…/lib/arm64/libksud.so");
        input.setText(initial);
        input.setSingleLine(false);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);

        new AlertDialog.Builder(this)
                .setTitle("指定 " + m.label + " 的 libksud.so")
                .setMessage("系统可能限制了本应用读取应用列表，导致检测不到。"
                        + "如果你确认已安装 " + m.label + "，在这里填它的 libksud.so 完整路径。"
                        + "\n\n留空则清除，恢复为自动检测。")
                .setView(input)
                .setPositiveButton("保存", (d, w) -> {
                    String path = input.getText().toString().trim();
                    if (!path.isEmpty() && !new java.io.File(path).isFile()) {
                        // Warn but keep the value: an unreadable path is exactly
                        // the situation this field exists for, and the file may
                        // become readable once root is obtained.
                        android.widget.Toast.makeText(this,
                                "该路径当前不可读，已保存；执行时若失败请检查",
                                android.widget.Toast.LENGTH_LONG).show();
                    }
                    m.setOverride(this, path);
                    SuManager.remember(this, m);
                    refreshSuManager();
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
