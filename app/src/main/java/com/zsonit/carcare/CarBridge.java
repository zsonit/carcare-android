package com.zsonit.carcare;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;


/**
 * 网页 → 原生的桥（window.CarCareAndroid）。
 * 与 iOS 版 bridge.js 的消息协议完全一致：
 *   {type:'notify'|'shareFile'|'sharePDF'|'previewImage'|'notifyPermission', ...}
 */
public class CarBridge {

    static final String CHANNEL_ID = "carcare_alerts";
    private static final String TAG = "CarCare";
    private static final int REQ_SAVE = 2001;

    private final MainActivity act;
    private final WebView web;
    private volatile int insetTopPx = 0;
    private volatile int insetBottomPx = 0;
    private File pendingSave;

    CarBridge(MainActivity act, WebView web) {
        this.act = act;
        this.web = web;
    }

    // ------------------------------------------------------------ 安全区

    void setInsets(int topPx, int bottomPx) {
        insetTopPx = topPx;
        insetBottomPx = bottomPx;
    }

    private String css(float px) {
        float d = act.getResources().getDisplayMetrics().density;
        return String.valueOf(Math.round(px / d * 10f) / 10f);
    }

    /** 返回 "顶部,底部"，单位是 CSS px（= dp） */
    @JavascriptInterface
    public String getInsets() {
        return css(insetTopPx) + "," + css(insetBottomPx);
    }

    @JavascriptInterface
    public String platform() {
        return "android";
    }

    /** 通知权限当前状态：granted / default */
    @JavascriptInterface
    public String notificationState() {
        return act.notificationsGranted() ? "granted" : "default";
    }

    // ------------------------------------------------------------ 消息入口

    @JavascriptInterface
    public void post(String json) {
        try {
            JSONObject o = new JSONObject(json == null ? "{}" : json);
            String type = o.optString("type");
            switch (type) {
                case "notify":
                    notify(o.optString("title"), o.optString("body"));
                    break;
                case "shareFile":
                    shareFile(o.optString("name", "export.csv"), o.optString("text", ""));
                    break;
                case "sharePDF":
                    sharePDF(o.optString("html", ""), o.optString("name", "维保记录.pdf"));
                    break;
                case "previewImage":
                    previewImage(o.optString("dataURL", ""));
                    break;
                case "notifyPermission":
                    act.runOnUiThread(() -> act.requestNotificationPermission());
                    break;
                case "schedule":
                    // 网页算好的到期事项 → AlarmManager 定时通知
                    NotifScheduler.apply(act, o.optJSONArray("items"));
                    break;
                case "badge": {
                    int n = o.optInt("count", 0);
                    act.runOnUiThread(() -> act.applyBadge(n));
                    CarWidgetProvider.save(act, n, o.optJSONObject("widget"));
                    break;
                }
                case "voice":
                    act.runOnUiThread(() -> act.startVoiceInput());
                    break;
                case "aiVoiceStart":
                    // AI 对话按住说话：按住开始识别
                    act.runOnUiThread(() -> act.startVoiceHold());
                    break;
                case "aiVoiceEnd":
                    // 松开结束；cancel=true 表示上滑取消
                    act.runOnUiThread(() -> act.releaseVoiceHold(o.optBoolean("cancel", false)));
                    break;
                case "ai":
                    // 大模型请求由原生代发（WebView 直连会被 CORS 拦），流式回吐给网页
                    AIHttp.start(act, o.optString("id"), o.optString("url"),
                            o.optJSONObject("headers"), o.optString("body", "{}"));
                    break;
                case "aiAbort":
                    AIHttp.cancel(o.optString("id"));
                    break;
                case "httpGet":
                    // 公开接口（油价等）由原生代发，绕过 WebView 跨域限制
                    nativeGet(o.optString("url"), o.optString("cb"));
                    break;
                case "httpReq":
                    // 通用请求（车牌查车等第三方接口）：自定义 header / POST / multipart
                    nativeReq(o);
                    break;
                case "getLocation":
                    act.runOnUiThread(() -> act.fetchLocation());
                    break;
                case "openUrl":
                    // 打开外部链接（如高德地图查看附近加油站挂牌价）
                    try {
                        Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(o.optString("url")));
                        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        act.startActivity(it);
                    } catch (Exception ignored) {
                    }
                    break;
                default:
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "bridge post failed", e);
        }
    }

    // ------------------------------------------------------------ 通知

    static void createChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "保养提醒",
                NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("保养到期、保险年检到期的提醒");
        ch.enableVibration(true);
        ch.setShowBadge(true);
        nm.createNotificationChannel(ch);
    }

    private void notify(String title, String body) {
        Context ctx = act;
        String t = (title == null || title.isEmpty()) ? "车管家" : title;
        String b = body == null ? "" : body;
        act.runOnUiThread(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && act.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
            NotificationManager nm = (NotificationManager) act.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;

            Intent open = new Intent(act, MainActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags |= PendingIntent.FLAG_IMMUTABLE;
            PendingIntent pi = PendingIntent.getActivity(act, 0, open, flags);

            Notification.Builder nb;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nb = new Notification.Builder(act, CHANNEL_ID);
            } else {
                nb = new Notification.Builder(act);
                nb.setPriority(Notification.PRIORITY_HIGH);
            }
            nb.setSmallIcon(R.drawable.ic_stat_carcare)
                    .setContentTitle(t)
                    .setContentText(b)
                    .setStyle(new Notification.BigTextStyle().bigText(b))
                    .setAutoCancel(true)
                    .setContentIntent(pi)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setWhen(System.currentTimeMillis());
            try {
                nm.notify((int) (System.currentTimeMillis() % 1000000), nb.build());
            } catch (Exception e) {
                Log.w(TAG, "notify failed", e);
            }
        });
    }

    // ------------------------------------------------------------ 导出 / 分享

    /** 导出 CSV 等纯文本文件 */
    void shareFile(String name, String text) {
        try {
            File f = ShareProvider.fileFor(act, name);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(text.getBytes(StandardCharsets.UTF_8));
            fos.close();
            act.runOnUiThread(() -> shareSheet(f));
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    /** 导出 PDF：交给系统打印框架，选「另存为 PDF」即得到矢量 PDF */
    private void sharePDF(String html, String name) {
        String job = name == null ? "车管家记录" : name.replaceAll("(?i)\\.pdf$", "");
        act.runOnUiThread(() -> {
            toast("在打印页选择「另存为 PDF」即可保存");
            PdfExporter.print(act, html, job);
        });
    }

    private void shareSheet(File f) {
        GlassDialog.actions(act, "已生成 " + f.getName(), null,
                new String[]{"保存到「文件」", "发送到其它 App", "取消"}, -1, idx -> {
                    if (idx == 0) {
                        saveTo(f);
                    } else if (idx == 1) {
                        sendTo(f);
                    }
                });
    }

    private void saveTo(File f) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(ShareProvider.mimeOf(f.getName()));
        i.putExtra(Intent.EXTRA_TITLE, f.getName());
        pendingSave = f;
        try {
            act.startActivityForResult(i, REQ_SAVE);
        } catch (Exception e) {
            toast("没有可用的文件管理器");
        }
    }

    private void sendTo(File f) {
        Uri uri = ShareProvider.uriFor(act, f);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(ShareProvider.mimeOf(f.getName()));
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, f.getName());
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            act.startActivity(Intent.createChooser(send, "发送到"));
        } catch (Exception e) {
            toast("没有可用的分享目标");
        }
    }

    void onSaveResult(int resultCode, Intent data) {
        File f = pendingSave;
        pendingSave = null;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null || f == null) {
            return;
        }
        Uri target = data.getData();
        try (java.io.InputStream in = act.getContentResolver().openInputStream(ShareProvider.uriFor(act, f));
             java.io.OutputStream out = act.getContentResolver().openOutputStream(target)) {
            if (in == null || out == null) throw new Exception("无法打开文件");
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            toast("已保存到 " + f.getName());
        } catch (Exception e) {
            toast("保存失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------ 看图

    private void previewImage(String dataUrl) {
        if (dataUrl == null || dataUrl.isEmpty()) return;
        act.runOnUiThread(() -> {
            Intent i = new Intent(act, ImageViewerActivity.class);
            i.putExtra(ImageViewerActivity.EXTRA_DATA_URL, dataUrl);
            act.startActivity(i);
        });
    }

    // ------------------------------------------------------------ 小工具

    private void toast(String msg) {
        act.runOnUiThread(() -> android.widget.Toast.makeText(act, msg, android.widget.Toast.LENGTH_SHORT).show());
    }

    void onNotificationPermission(boolean granted) {
        act.evaluateJs("window.__ccNotifyPermission&&window.__ccNotifyPermission('"
                + (granted ? "granted" : "denied") + "')");
    }

    /** 通用 HTTP 请求：GET/POST、自定义 header、json / urlencoded / multipart，用于车牌查车等第三方接口 */
    private void nativeReq(final JSONObject o) {
        final String cb = o.optString("cb");
        final String url = o.optString("url");
        final String method = o.optString("method", "GET").toUpperCase();
        final JSONObject hs = o.optJSONObject("headers");
        final JSONObject fields = o.optJSONObject("fields");
        final String ct = o.optString("ct", "application/json");
        final String bodyStr = o.optString("body", "");
        if (cb.isEmpty() || url.isEmpty()) return;
        new Thread(() -> {
            String body = "";
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(15000);
                conn.setRequestMethod(method);
                conn.setRequestProperty("User-Agent", "CheGuanJia/2.8 (Android)");
                if (hs != null) {
                    for (java.util.Iterator<String> it = hs.keys(); it.hasNext(); ) {
                        String k = it.next();
                        conn.setRequestProperty(k, hs.optString(k));
                    }
                }
                if (!"GET".equals(method)) {
                    conn.setDoOutput(true);
                    byte[] payload;
                    if ("multipart".equals(ct) && fields != null) {
                        String bd = "----CCForm" + System.currentTimeMillis();
                        StringBuilder sb = new StringBuilder();
                        for (java.util.Iterator<String> it = fields.keys(); it.hasNext(); ) {
                            String k = it.next();
                            sb.append("--").append(bd).append("\r\n")
                              .append("Content-Disposition: form-data; name=\"").append(k).append("\"\r\n\r\n")
                              .append(fields.optString(k)).append("\r\n");
                        }
                        sb.append("--").append(bd).append("--\r\n");
                        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + bd);
                        payload = sb.toString().getBytes(StandardCharsets.UTF_8);
                    } else {
                        conn.setRequestProperty("Content-Type", ct);
                        payload = bodyStr.getBytes(StandardCharsets.UTF_8);
                    }
                    java.io.OutputStream os = conn.getOutputStream();
                    os.write(payload);
                    os.flush();
                    os.close();
                }
                java.io.BufferedReader rd = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb2 = new StringBuilder();
                String line;
                while ((line = rd.readLine()) != null) sb2.append(line).append('\n');
                rd.close();
                body = sb2.toString();
            } catch (Exception e) {
                Log.w(TAG, "httpReq failed: " + url, e);
                try {
                    if (conn != null && conn.getErrorStream() != null) {
                        java.io.BufferedReader er = new java.io.BufferedReader(
                                new java.io.InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8));
                        StringBuilder eb = new StringBuilder();
                        String el;
                        while ((el = er.readLine()) != null) eb.append(el).append('\n');
                        er.close();
                        body = eb.toString();
                    } else {
                        body = "";
                    }
                } catch (Exception ignore) { body = ""; }
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String out = JSONObject.quote(body);
            act.evaluateJs(cb + " && " + cb + "(" + out + ")");
        }).start();
    }

    // ------------------------------------------------------------ 通用 GET
    /** 后台线程拉一次 HTTP GET，把响应文本回填给网页的回调函数（cb 为网页里的函数名） */
    private void nativeGet(final String url, final String cb) {
        if (cb == null || cb.isEmpty() || url == null || url.isEmpty()) return;
        new Thread(() -> {
            String body = "";
            java.net.HttpURLConnection conn = null;
            try {
                conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);
                conn.setRequestProperty("User-Agent", "CheGuanJia/2.7 (Android)");
                java.io.BufferedReader rd = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = rd.readLine()) != null) sb.append(line).append('\n');
                rd.close();
                body = sb.toString();
            } catch (Exception e) {
                Log.w(TAG, "httpGet failed: " + url, e);
            } finally {
                if (conn != null) conn.disconnect();
            }
            final String out = JSONObject.quote(body);
            act.evaluateJs(cb + " && " + cb + "(" + out + ")");
        }).start();
    }
}
