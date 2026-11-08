package com.zsonit.carcare;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 大模型请求的代发通道。
 * <p>
 * WebView 里直连各家大模型 API 会被 CORS 拦（服务端不返回 Access-Control-Allow-Origin），
 * 所以网页把 {url, headers, body} 交给原生，原生用 HttpURLConnection 发，边收边回吐：
 * ccAiChunk(id, text) / ccAiDone(id) / ccAiError(id, msg)。
 * 与 iOS 的 AIClient 协议完全一致。
 */
public class AIHttp {

    private static final String TAG = "CarCareAI";
    private static final ExecutorService POOL = Executors.newCachedThreadPool();
    private static final Map<String, HttpURLConnection> LIVE = new ConcurrentHashMap<>();

    public static void start(MainActivity act, String id, String url, JSONObject headers, String body) {
        if (id == null || id.isEmpty() || url == null || url.isEmpty()) return;
        POOL.execute(() -> run(act, id, url, headers, body));
    }

    public static void cancel(String id) {
        if (id == null) return;
        HttpURLConnection c = LIVE.remove(id);
        if (c != null) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
            }
        }
    }

    private static void run(MainActivity act, String id, String url, JSONObject headers, String body) {
        HttpURLConnection conn = null;
        try {
            URL u = new URL(url);
            String scheme = u.getProtocol();
            if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                fail(act, id, "接口地址无效，请填完整的 https:// 地址");
                return;
            }
            conn = (HttpURLConnection) u.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            if (headers != null) {
                Iterator<String> it = headers.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    conn.setRequestProperty(k, headers.optString(k, ""));
                }
            }
            LIVE.put(id, conn);

            byte[] payload = (body == null ? "{}" : body).getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code != 200) {
                InputStream es = conn.getErrorStream();
                String text = es == null ? "" : readAll(es);
                fail(act, id, errorText(text, code));
                return;
            }

            BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8), 8192);
            StringBuilder batch = new StringBuilder();
            long last = System.currentTimeMillis();
            String line;
            while ((line = br.readLine()) != null) {
                if (Thread.currentThread().isInterrupted()) return;
                batch.append(line).append('\n');
                long now = System.currentTimeMillis();
                if (now - last >= 60 || batch.length() > 2000) {
                    emit(act, id, batch.toString());
                    batch.setLength(0);
                    last = now;
                }
            }
            if (batch.length() > 0) emit(act, id, batch.toString());
            act.evaluateJs("ccAiDone(" + JSONObject.quote(id) + ")");
        } catch (Exception e) {
            if (Thread.currentThread().isInterrupted()) return;
            fail(act, id, friendly(e));
        } finally {
            LIVE.remove(id);
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static void emit(MainActivity act, String id, String text) {
        if (text == null || text.isEmpty()) return;
        act.evaluateJs("ccAiChunk(" + JSONObject.quote(id) + "," + JSONObject.quote(text) + ")");
    }

    private static void fail(MainActivity act, String id, String msg) {
        act.evaluateJs("ccAiError(" + JSONObject.quote(id) + "," + JSONObject.quote(msg) + ")");
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total > 8000) break;
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String errorText(String raw, int code) {
        String body = raw == null ? "" : raw.trim();
        if (!body.isEmpty()) {
            try {
                JSONObject o = new JSONObject(body);
                JSONObject err = o.optJSONObject("error");
                if (err != null) {
                    String m = err.optString("message", "");
                    if (m.isEmpty()) m = err.optString("code", "");
                    if (!m.isEmpty()) return "HTTP " + code + "：" + m;
                }
                String m = o.optString("message", "");
                if (!m.isEmpty()) return "HTTP " + code + "：" + m;
            } catch (Exception ignored) {
            }
            return "HTTP " + code + "：" + body.substring(0, Math.min(180, body.length()));
        }
        return "HTTP " + code;
    }

    private static String friendly(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String low = m.toLowerCase();
        if (low.contains("unable to resolve host") || low.contains("unknownhost")) {
            return "连不上这个地址，检查接口地址或网络";
        }
        if (low.contains("timeout") || low.contains("timed out")) {
            return "请求超时，网络太慢或服务无响应";
        }
        if (low.contains("cleartext")) {
            return "Android 只允许 HTTPS 接口地址";
        }
        Log.w(TAG, "ai request failed", e);
        return m;
    }
}
