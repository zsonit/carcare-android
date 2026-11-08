package com.zsonit.carcare;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;
import android.widget.RemoteViews;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 桌面小组件：下次保养倒计时 / 当前里程 / 本月花销。
 * 数据由网页每次保存后通过桥（type=badge 的 widget 字段）写进 SharedPreferences。
 */
public class CarWidgetProvider extends AppWidgetProvider {

    static final String PREFS = "carcare_widget";
    private static final String TAG = "CarCare";

    static void save(Context ctx, int badge, JSONObject w) {
        SharedPreferences.Editor e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putInt("badge", badge);
        if (w != null) {
            e.putString("title", w.optString("title", ""));
            e.putInt("days", w.optInt("days", 99999));
            e.putBoolean("hasDays", w.has("days") && !w.isNull("days"));
            e.putLong("mileage", w.optLong("mileage", 0));
            e.putInt("month", w.optInt("month", 0));
            e.putLong("updated", w.optLong("updated", System.currentTimeMillis()));
        }
        e.apply();
        refresh(ctx);
    }

    static void refresh(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            ComponentName cn = new ComponentName(ctx, CarWidgetProvider.class);
            int[] ids = mgr.getAppWidgetIds(cn);
            if (ids == null || ids.length == 0) return;
            for (int id : ids) {
                mgr.updateAppWidget(id, build(ctx));
            }
        } catch (Exception e) {
            Log.w(TAG, "widget refresh failed", e);
        }
    }

    private static RemoteViews build(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        RemoteViews v = new RemoteViews(ctx.getPackageName(), R.layout.widget_carcare);

        String title = sp.getString("title", "");
        boolean hasDays = sp.getBoolean("hasDays", false);
        int days = sp.getInt("days", 99999);
        long mileage = sp.getLong("mileage", 0);
        int month = sp.getInt("month", 0);

        if (title == null || title.isEmpty()) {
            v.setTextViewText(R.id.w_title, "车管家 · 暂无待办");
            v.setTextViewText(R.id.w_main, "点一下开始记录");
            v.setTextColor(R.id.w_main, Color.parseColor("#0B1A2B"));
        } else {
            v.setTextViewText(R.id.w_title, "车管家 · 下次保养");
            v.setTextViewText(R.id.w_main, title);
            if (!hasDays) {
                v.setTextViewText(R.id.w_days, "无日期");
                v.setTextColor(R.id.w_days, Color.parseColor("#5C6B7E"));
            } else if (days < 0) {
                v.setTextViewText(R.id.w_days, "已逾期 " + (-days) + " 天");
                v.setTextColor(R.id.w_days, Color.parseColor("#E8382C"));
            } else if (days == 0) {
                v.setTextViewText(R.id.w_days, "今天到期");
                v.setTextColor(R.id.w_days, Color.parseColor("#E8382C"));
            } else {
                v.setTextViewText(R.id.w_days, "还有 " + days + " 天");
                v.setTextColor(R.id.w_days, days <= 14 ? Color.parseColor("#E08600") : Color.parseColor("#0A84FF"));
            }
        }
        String sub = String.format(Locale.CHINA, "%,d km", mileage);
        if (month > 0) sub += "　本月 ¥" + month;
        v.setTextViewText(R.id.w_sub, sub);

        Intent open = new Intent(ctx, MainActivity.class);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        v.setOnClickPendingIntent(R.id.w_root,
                PendingIntent.getActivity(ctx, 0, open, flags));
        return v;
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        RemoteViews v = build(ctx);
        for (int id : ids) mgr.updateAppWidget(id, v);
    }

    @Override
    public void onEnabled(Context ctx) {
        refresh(ctx);
    }
}
