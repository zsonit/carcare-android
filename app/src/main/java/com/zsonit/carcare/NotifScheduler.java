package com.zsonit.carcare;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 系统级定时提醒：网页把「到期事项」算好后整批传进来，这里用 AlarmManager 排到具体时刻。
 * 重启后由 BootReceiver 按同一份 JSON 重新排（AlarmManager 的闹钟不跨重启）。
 */
final class NotifScheduler {

    private static final String TAG = "CarCare";
    static final String PREFS = "carcare_notify";
    static final String KEY_ITEMS = "items";
    static final String ACTION = "com.zsonit.carcare.ALARM";

    private NotifScheduler() {
    }

    static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 全量替换：先撤掉旧的 cc_ 闹钟，再按新列表排 */
    static void apply(Context ctx, JSONArray items) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;

        // 撤销上一批
        try {
            JSONArray old = new JSONArray(prefs(ctx).getString(KEY_ITEMS, "[]"));
            for (int i = 0; i < old.length(); i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null) cancelOne(ctx, am, o.optString("id"));
            }
        } catch (Exception ignored) {
        }

        JSONArray kept = new JSONArray();
        long now = System.currentTimeMillis();
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) continue;
                long ts = it.optLong("ts", 0L);
                if (ts <= now) continue;
                kept.put(it);
                schedule(ctx, am, it.optString("id"), it.optString("title"),
                        it.optString("body"), ts);
            }
        }
        prefs(ctx).edit().putString(KEY_ITEMS, kept.toString()).apply();
        Log.i(TAG, "scheduled " + kept.length() + " notifications");
    }

    /** 开机后按上次的列表重排（只排还没到期的） */
    static void restore(Context ctx) {
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(KEY_ITEMS, "[]"));
            apply(ctx, arr);
        } catch (Exception e) {
            Log.w(TAG, "restore failed", e);
        }
    }

    private static void schedule(Context ctx, AlarmManager am, String id, String title,
                                 String body, long ts) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(ACTION);
        i.putExtra("id", id);
        i.putExtra("title", title);
        i.putExtra("body", body);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, safeCode(id), i, flags);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // 没有「精确闹钟」权限时退回非精确，仍然会在那天提醒
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ts, pi);
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ts, pi);
            }
        } catch (SecurityException e) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ts, pi);
        }
    }

    private static void cancelOne(Context ctx, AlarmManager am, String id) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction(ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pi = PendingIntent.getBroadcast(ctx, safeCode(id), i, flags);
        am.cancel(pi);
        pi.cancel();
    }

    private static int safeCode(String id) {
        return id == null ? 0 : (id.hashCode() & 0x7fffffff);
    }
}
