package df.root;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The screen that has to be read before anything runs.
 *
 * <p>Shown once per install: agreeing writes a flag and this activity is never
 * seen again unless the app data is cleared. The agree button stays disabled
 * for a countdown, which is the only way to make "read this first" mean
 * anything — otherwise it is one tap away from being dismissed unread.
 */
public class DisclaimerActivity extends Activity {

    private static final String PREFS = "dfroot";
    private static final String KEY_ACCEPTED = "disclaimer_accepted";

    /** Seconds before "I have read and agree" becomes tappable. */
    private static final int COUNTDOWN_SECONDS = 10;

    private Button agreeButton;
    private CountDownTimer countdown;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_ACCEPTED, false)) {
            goToMain();
            return;
        }

        setContentView(buildUi());
        startCountdown();
    }

    @Override
    protected void onDestroy() {
        if (countdown != null) {
            countdown.cancel();
        }
        super.onDestroy();
    }

    private ViewGroup buildUi() {
        // A ScrollView as the content view does not inset itself for the
        // status/navigation bars, so the title ends up under the clock and the
        // buttons under the gesture handle. Pad the scroll view by the real
        // bar heights; the system window insets are the only source that
        // knows what those are at runtime.
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.parseColor("#121212"));

        int basePad = dp(22);
        scroll.setPadding(basePad, basePad, basePad, basePad);
        scroll.setClipToPadding(false);

        final int initialTop = basePad;
        final int initialBottom = basePad;
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(initialTop, initialTop + insets.getSystemWindowInsetTop(),
                    initialTop, initialBottom + insets.getSystemWindowInsetBottom());
            return insets;
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 0, 0, 0);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(this);
        icon.setText("⚠️");
        icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        icon.setPadding(0, 0, dp(10), 0);
        header.addView(icon);

        TextView title = new TextView(this);
        title.setText("使用前必读");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#F44336"));
        header.addView(title);

        root.addView(header);

        TextView body = new TextView(this);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        body.setTextColor(Color.parseColor("#DDDDDD"));
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(18), 0, dp(18));
        body.setTextIsSelectable(true);
        body.setText(DISCLAIMER);
        root.addView(body);

        agreeButton = new Button(this);
        agreeButton.setEnabled(false);
        agreeButton.setOnClickListener(v -> {
            getSharedPreferences(PREFS, MODE_PRIVATE)
                    .edit().putBoolean(KEY_ACCEPTED, true).apply();
            goToMain();
        });
        root.addView(agreeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button decline = new Button(this);
        decline.setText("不同意（退出）");
        decline.setOnClickListener(v -> finishAffinity());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        root.addView(decline, lp);

        scroll.addView(root);
        return scroll;
    }

    private void startCountdown() {
        agreeButton.setText("我已阅读并同意（" + COUNTDOWN_SECONDS + "s）");
        countdown = new CountDownTimer(COUNTDOWN_SECONDS * 1000L, 1000L) {
            @Override
            public void onTick(long millisUntilFinished) {
                agreeButton.setText("我已阅读并同意（"
                        + (millisUntilFinished / 1000 + 1) + "s）");
            }

            @Override
            public void onFinish() {
                agreeButton.setText("我已阅读并同意");
                agreeButton.setEnabled(true);
            }
        };
        countdown.start();
    }

    private void goToMain() {
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()));
    }

    private static final String DISCLAIMER =
            "本工具仅用于【且仅限于】你自己拥有的设备的\n安全研究与学习。\n"
            + "\n"
            + "【风险提示】\n"
            + "· 本工具通过内核漏洞获取 root 权限，属高危操作；\n"
            + "· 唯一的风险是：可能造成内核卡死（黑屏 / 无响应），\n"
            + "  此时长按电源键强制重启即可恢复；\n"
            + "· 本工具不改动任何分区、不刷写 boot / 固件、不格式化数据，\n"
            + "  因此不会造成系统损坏或数据丢失。\n"
            + "\n"
            + "【注意事项】\n"
            + "1. 请不要中途退出软件，否则可能导致提权失败；\n"
            + "2. 如果 30 秒内没有提权完成，基本可以判定为失败\n"
            + "   （可能是判断逻辑没写好），重启后重试；\n"
            + "3. 保持屏幕常亮、把本 App 留在前台；\n"
            + "4. 若日志出现失败特征，请先重启设备再重试，\n"
            + "   不要连续硬跑。\n"
            + "\n"
            + "【免责声明】\n"
            + "使用本工具即表示你已知悉上述风险并自行承担全部后果。\n"
            + "请勿将本工具用于任何非授权设备。\n"
            + "\n"
            + "本工具完全免费，若你是付费获得的，恭喜你被骗了。\n"
            + "作者 QQ：2084089509\n"
            + "（反馈 / 交流可用，不提供任何收费服务）";
}