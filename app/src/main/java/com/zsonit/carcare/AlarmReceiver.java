package com.zsonit.carcare;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.util.Log;

/** 闹钟到点：发一条系统通知（不依赖 App 是否在运行） */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "CarCare";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (intent == null) return;
        String title = intent.getStringExtra("title");
        String body = intent.getStringExtra("body");
        String id = intent.getStringExtra("id");

        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = null;
        if (pm != null) {
            lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carcare:alarm");
            lock.acquire(10000);
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            CarBridge.createChannel(ctx);
            NotificationManager nm =
                    (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent open = new Intent(ctx, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pi = PendingIntent.getActivity(
                    ctx, id == null ? 0 : id.hashCode(), open, flags);

            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nb = new Notification.Builder(ctx, CarBridge.CHANNEL_ID);
            } else {
                nb = new Notification.Builder(ctx);
                nb.setPriority(Notification.PRIORITY_HIGH);
            }
            nb.setSmallIcon(R.drawable.ic_stat_carcare)
                    .setContentTitle(title == null || title.isEmpty() ? "车管家" : title)
                    .setContentText(body == null ? "" : body)
                    .setStyle(new Notification.BigTextStyle().bigText(body == null ? "" : body))
                    .setAutoCancel(true)
                    .setContentIntent(pi);
            nm.notify(id == null ? (int) System.currentTimeMillis() : id.hashCode(), nb.build());
        } catch (Exception e) {
            Log.w(TAG, "alarm notify failed", e);
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
        }
    }
}
