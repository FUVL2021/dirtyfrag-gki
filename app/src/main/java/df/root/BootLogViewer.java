package df.root;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;

/**
 * The boot log dialog, in one place.
 *
 * <p>Both the main screen and settings can open it, and neither should grow its
 * own copy of "read a file, show a dialog, offer a copy button".
 *
 * <p>Reading runs on a caller-supplied executor rather than on the UI thread:
 * the file is small, but this is the screen a user reaches when something
 * already went wrong, and a disk read that stalls the UI in that moment reads
 * as another failure.
 */
public final class BootLogViewer {

    private BootLogViewer() {
    }

    /** Build the text to show, including the file path so it can be pulled with adb. */
    public static String read(Context context) {
        File f = BootLog.file(context);
        if (!f.exists()) {
            return "还没有日志。日志从开机自启动开始时记录，"
                    + "执行过一次或重启过一次之后才会有内容。\n\n文件位置：\n" + f;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String text = bos.toString("UTF-8");
            if (text.isEmpty()) {
                text = "(日志文件为空)";
            }
            return text + "\n\n文件位置：\n" + f;
        } catch (Exception e) {
            return "读取日志失败：" + e + "\n\n文件位置：\n" + f;
        }
    }

    /** Show {@code body} in a scrollable dialog with a copy-all action. */
    public static void show(Context context, String body) {
        int pad = Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 12, context.getResources().getDisplayMetrics()));

        TextView tv = new TextView(context);
        tv.setText(body);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setTextIsSelectable(true);
        tv.setPadding(pad, pad, pad, pad);

        ScrollView sv = new ScrollView(context);
        sv.addView(tv);

        new AlertDialog.Builder(context)
                .setTitle("开机日志")
                .setView(sv)
                .setPositiveButton("复制全部", (d, w) -> {
                    ClipboardManager cm =
                            (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(ClipData.newPlainText("Vin的提权工具 boot log", body));
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}