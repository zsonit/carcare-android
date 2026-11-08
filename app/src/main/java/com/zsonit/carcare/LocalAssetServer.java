package com.zsonit.carcare;

import android.content.Context;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简本机 HTTP 服务：把 APK 内 assets/web 以 http://127.0.0.1:PORT 提供给 WebView。
 *
 * 为什么不用 file:// 或 WebViewAssetLoader：
 *  - file:// 是 opaque origin，localStorage / fetch 都不正常；
 *  - http://127.0.0.1 与 iOS 版（同样跑本机 HTTP 服务）完全一致，
 *    页面里 fetch 局域网 NAS 的 http 地址也不会被判成混合内容。
 *
 * 顺带在 index.html 的 <head> 前插入 Android 专用桥接脚本 cc-android.js。
 */
public class LocalAssetServer {

    private static final String ASSET_ROOT = "web";
    private static final int[] DEFAULT_PORTS = {48700, 48701, 48702, 48703, 48704, 48705};

    private final Context ctx;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket server;
    private int port;
    private byte[] indexHtml;

    public LocalAssetServer(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    public int port() {
        return port;
    }

    /** 依次尝试候选端口，返回绑定的端口；全部失败返回 -1 */
    public int start() {
        int[] candidates = DEFAULT_PORTS;
        int saved = ctx.getSharedPreferences("carcare", Context.MODE_PRIVATE).getInt("port", 0);
        if (saved > 1024) {
            candidates = new int[DEFAULT_PORTS.length + 1];
            candidates[0] = saved;
            System.arraycopy(DEFAULT_PORTS, 0, candidates, 1, DEFAULT_PORTS.length);
        }
        for (int candidate : candidates) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate), 16);
                server = s;
                port = candidate;
                running = true;
                ctx.getSharedPreferences("carcare", Context.MODE_PRIVATE)
                        .edit().putInt("port", candidate).apply();
                Thread t = new Thread(this::acceptLoop, "carcare-http");
                t.setDaemon(true);
                t.start();
                return candidate;
            } catch (IOException ignored) {
                // 端口被占用，试下一个
            }
        }
        return -1;
    }

    public void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket socket = server.accept();
                pool.execute(() -> {
                    try {
                        handle(socket);
                    } catch (Exception ignored) {
                    } finally {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                        }
                    }
                });
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void handle(Socket socket) throws IOException {
        socket.setSoTimeout(8000);
        InputStream in = socket.getInputStream();
        ByteArrayOutputStream head = new ByteArrayOutputStream(1024);
        int b;
        while ((b = in.read()) != -1) {
            head.write(b);
            int n = head.size();
            if (n >= 4) {
                byte[] h = head.toByteArray();
                if (h[n - 4] == '\r' && h[n - 3] == '\n' && h[n - 2] == '\r' && h[n - 1] == '\n') break;
            }
            if (n > 16384) break;
        }
        String requestLine = new String(head.toByteArray(), "UTF-8");
        int eol = requestLine.indexOf("\r\n");
        if (eol > 0) requestLine = requestLine.substring(0, eol);

        String[] parts = requestLine.split(" ");
        if (parts.length < 2) {
            write(socket, 400, "Bad Request", "text/plain; charset=utf-8", "bad request".getBytes("UTF-8"));
            return;
        }

        String path = parts[1];
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        q = path.indexOf('#');
        if (q >= 0) path = path.substring(0, q);
        path = decode(path);
        if (path.isEmpty() || path.equals("/")) path = "/index.html";
        while (path.startsWith("/")) path = path.substring(1);

        if (path.contains("..")) {
            write(socket, 403, "Forbidden", "text/plain; charset=utf-8", new byte[0]);
            return;
        }

        byte[] body = readAsset(path);
        String mime = mimeOf(path);
        if (body == null) {
            // 未知路径回落到首页（与 iOS 版一致）
            body = indexHtml();
            mime = "text/html; charset=utf-8";
        }
        if (body == null) {
            write(socket, 404, "Not Found", "text/plain; charset=utf-8", "not found".getBytes("UTF-8"));
            return;
        }
        write(socket, 200, "OK", mime, body);
    }

    private byte[] readAsset(String path) {
        if ("index.html".equals(path)) {
            return indexHtml();
        }
        try (InputStream is = ctx.getAssets().open(ASSET_ROOT + "/" + path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, is.available()));
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    /** 首页：注入 Android 桥接脚本后再返回（缓存在内存里） */
    private byte[] indexHtml() {
        if (indexHtml != null) return indexHtml;
        try (InputStream is = ctx.getAssets().open(ASSET_ROOT + "/index.html")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(96 * 1024);
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            String html = new String(bos.toByteArray(), "UTF-8");
            String tag = "<script src=\"cc-android.js\"></script>";
            int at = html.indexOf("<head>");
            if (at >= 0) {
                html = html.substring(0, at + 6) + tag + html.substring(at + 6);
            } else {
                html = tag + html;
            }
            indexHtml = html.getBytes("UTF-8");
            return indexHtml;
        } catch (IOException e) {
            return null;
        }
    }

    private static String decode(String s) {
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    private static String mimeOf(String path) {
        String p = path.toLowerCase(Locale.US);
        if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
        if (p.endsWith(".js") || p.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (p.endsWith(".css")) return "text/css; charset=utf-8";
        if (p.endsWith(".json") || p.endsWith(".webmanifest")) return "application/json; charset=utf-8";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".ico")) return "image/x-icon";
        if (p.endsWith(".webp")) return "image/webp";
        if (p.endsWith(".woff2")) return "font/woff2";
        if (p.endsWith(".txt")) return "text/plain; charset=utf-8";
        return "application/octet-stream";
    }

    private static void write(Socket socket, int status, String reason, String mime, byte[] body)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n");
        sb.append("Content-Type: ").append(mime).append("\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Cache-Control: no-store\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Connection: close\r\n\r\n");
        OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16384);
        out.write(sb.toString().getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }
}
