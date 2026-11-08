package com.zsonit.carcare;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 与网页 .modal .sheet 同款的底部玻璃卡片弹窗，替代系统 AlertDialog，
 * 让 confirm / alert / prompt / 分享 的观感跟 App 内其它弹窗一致。
 */
public final class GlassDialog {

    private static final int INK = 0xFF0B1A2B;
    private static final int SUB = 0xFF5C6B7E;
    private static final int BLUE = 0xFF0A84FF;
    private static final int RED = 0xFFE8382C;
    private static final int HAIRLINE = 0xFFE3EAF4;

    /** index = -1 表示点空白处/Cancel 界面自行取消；text 仅输入框场景有值 */
    public interface Result {
        void onResult(int index, String text);
    }

    private GlassDialog() {
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // ---------------------------------------------------------------- 语义封装

    public static void alert(Activity act, String message, Runnable done) {
        show(act, "提示", message, null, new String[]{"好"}, -1, (i, t) -> {
            if (done != null) done.run();
        });
    }

    public static void confirm(Activity act, String message, boolean destructive,
                               java.util.function.Consumer<Boolean> done) {
        show(act, "请确认", message, null,
                new String[]{"取消", destructive ? "删除" : "确定"}, destructive ? 1 : -1,
                (i, t) -> {
                    if (done != null) done.accept(i == 1);
                });
    }

    public static void prompt(Activity act, String message, String defaultValue,
                              java.util.function.Consumer<String> done) {
        show(act, "请输入", message, defaultValue == null ? "" : defaultValue,
                new String[]{"取消", "确定"}, -1,
                (i, t) -> {
                    if (done != null) done.accept(i == 1 ? t : null);
                });
    }

    /** 动作列表（用于「保存到文件 / 发送给其它 App」这类选择） */
    public static void actions(Activity act, String title, String message,
                               String[] labels, int destructiveIndex,
                               java.util.function.IntConsumer onPick) {
        show(act, title, message, null, labels, destructiveIndex,
                (i, t) -> {
                    if (onPick != null) onPick.accept(i);
                });
    }

    // ---------------------------------------------------------------- 实现

    private static void show(Activity act, String title, String message, String inputDefault,
                             String[] labels, int destructiveIndex, Result result) {
        if (act == null || act.isFinishing()) return;

        final Dialog dialog = new Dialog(act, R.style.SheetDialog);
        FrameLayout root = new FrameLayout(act);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout card = new LinearLayout(act);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFFF9FBFF);
        bg.setCornerRadii(new float[]{
                dp(act, 30), dp(act, 30), dp(act, 30), dp(act, 30), 0, 0, 0, 0});
        card.setBackground(bg);
        card.setElevation(dp(act, 18));
        card.setClickable(true);

        // 抓取条
        View grabber = new View(act);
        GradientDrawable gb = new GradientDrawable();
        gb.setColor(0xFFC9D4E3);
        gb.setCornerRadius(dp(act, 3));
        grabber.setBackground(gb);
        LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(dp(act, 38), dp(act, 5));
        gp.gravity = Gravity.CENTER_HORIZONTAL;
        gp.topMargin = dp(act, 9);
        gp.bottomMargin = dp(act, 2);
        card.addView(grabber, gp);

        if (title != null && !title.isEmpty()) {
            TextView t = new TextView(act);
            t.setText(title);
            t.setTextSize(13);
            t.setTextColor(SUB);
            t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(act, 22), dp(act, 10), dp(act, 22), 0);
            card.addView(t);
        }

        if (message != null && !message.isEmpty()) {
            TextView m = new TextView(act);
            m.setText(message);
            m.setTextSize(15);
            m.setTextColor(INK);
            m.setLineSpacing(dp(act, 3), 1f);
            m.setGravity(Gravity.CENTER);
            m.setPadding(dp(act, 22), dp(act, 6), dp(act, 22), dp(act, 14));
            card.addView(m);
        }

        final EditText input;
        if (inputDefault != null) {
            input = new EditText(act);
            input.setText(inputDefault);
            input.setSingleLine(true);
            input.setTextSize(15);
            input.setTextColor(INK);
            input.setInputType(InputType.TYPE_CLASS_TEXT);
            GradientDrawable ib = new GradientDrawable();
            ib.setColor(0xFFFFFFFF);
            ib.setCornerRadius(dp(act, 12));
            ib.setStroke(dp(act, 1), 0xFFE1E9F5);
            input.setBackground(ib);
            input.setPadding(dp(act, 14), dp(act, 10), dp(act, 14), dp(act, 10));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            ip.setMargins(dp(act, 20), 0, dp(act, 20), dp(act, 14));
            card.addView(input, ip);
            input.setSelection(inputDefault.length());
        } else {
            input = null;
        }

        final boolean[] handled = {false};

        for (int i = 0; i < labels.length; i++) {
            card.addView(hairline(act));
            TextView b = actionRow(act, labels[i], i == destructiveIndex ? RED : BLUE);
            final int index = i;
            b.setOnClickListener(v -> {
                if (handled[0]) return;
                handled[0] = true;
                String text = input == null ? null : input.getText().toString();
                dialog.dismiss();
                result.onResult(index, text);
            });
            card.addView(b);
        }

        // 底部留白（并避让导航栏）
        final int extra = dp(act, 8);
        card.setOnApplyWindowInsetsListener((v, insets) -> {
            int bottom;
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                bottom = insets.getSystemWindowInsetBottom();
            }
            v.setPadding(0, 0, 0, bottom > 0 ? bottom : extra);
            return insets;
        });

        root.setOnClickListener(v -> {
            if (handled[0]) return;
            handled[0] = true;
            dialog.dismiss();
            result.onResult(-1, null);
        });
        root.addView(card, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM));

        dialog.setContentView(root);
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnDismissListener(d -> {
            if (!handled[0]) {
                handled[0] = true;
                result.onResult(-1, null);
            }
        });

        Window w = dialog.getWindow();
        if (w != null) {
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            w.setGravity(Gravity.BOTTOM);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.30f;
            w.setAttributes(lp);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        dialog.show();
        if (input != null) {
            input.requestFocus();
            if (w != null) {
                w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                        | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        }
    }

    private static View hairline(Context c) {
        View v = new View(c);
        v.setBackgroundColor(HAIRLINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 0.6f))));
        return v;
    }

    private static TextView actionRow(Context c, String label, int color) {
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextSize(17);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        tv.setFocusable(true);
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(c, 54)));

        TypedValue out = new TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, out, true);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        tv.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000),
                c.getDrawable(out.resourceId), mask));
        return tv;
    }
}
