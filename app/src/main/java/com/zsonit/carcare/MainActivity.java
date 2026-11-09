package com.zsonit.carcare;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 车管家 Android 版：原生外壳 + WebView（内置与 iOS 版同一套页面）+ 原生能力桥接。
 *
 * 页面由内置的本机 HTTP 服务提供（http://127.0.0.1:PORT），与 iOS 版同源策略一致，
 * localStorage / fetch 行为、NAS 局域网访问都和浏览器版完全一样。
 */
public class MainActivity extends Activity {

    private static final String TAG = "CarCare";
    private static final int REQ_FILE = 1001;
    private static final int REQ_NOTIF = 1003;
    private static final int REQ_VOICE = 1004;
    private static final int REQ_HOLD_VOICE = 1005;

    private WebView web;
    private LocalAssetServer server;
    private CarBridge bridge;
    private FrameLayout root;

    /** 挂起的网页文件选择回调（WebView 要求必须回调，否则网页控件卡死） */
    private ValueCallback<Uri[]> pendingFileCallback;
    private Uri pendingCameraUri;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        web = findViewById(R.id.web);

        // 崩溃日志落盘（App 专属目录，USB 可见），便于远程排障
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File dir = getExternalFilesDir("crash");
                if (dir != null) {
                    java.io.PrintWriter w = new java.io.PrintWriter(new File(dir, "crash.txt"), "UTF-8");
                    e.printStackTrace(w);
                    w.close();
                }
            } catch (Exception ignored) {
            }
            if (prev != null) prev.uncaughtException(t, e);
        });

        CarBridge.createChannel(this);
        setupEdgeToEdge();
        setupWeb();
        bridge = new CarBridge(this, web);
        web.addJavascriptInterface(bridge, "CarCareAndroid");

        startServerAndLoad();
    }

    // ------------------------------------------------------------ 窗口

    private void setupEdgeToEdge() {
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            android.view.WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }

        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            int ime = 0;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                top = bars.top;
                bottom = bars.bottom;
                ime = insets.getInsets(WindowInsets.Type.ime()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }
            if (bridge != null) {
                bridge.setInsets(top, bottom);
                evaluateJs("window.__ccApplyInsets&&window.__ccApplyInsets()");
            }
            // 键盘：边到边后系统不再自动压缩窗口，用内边距让出键盘高度
            web.setPadding(0, 0, 0, ime > bottom ? ime - bottom : 0);
            return insets;
        });
    }

    // ------------------------------------------------------------ WebView

    private void setupWeb() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setTextZoom(100);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setLoadWithOverviewMode(false);
        s.setUseWideViewPort(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        // NAS 同步要访问局域网 http 地址
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            //noinspection deprecation
            s.setForceDark(WebSettings.FORCE_DARK_OFF);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            s.setAlgorithmicDarkeningAllowed(false);
        }
        web.setBackgroundColor(Color.TRANSPARENT);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri url = request.getUrl();
                String scheme = url.getScheme() == null ? "" : url.getScheme();
                String host = url.getHost() == null ? "" : url.getHost();
                if (("http".equals(scheme) || "https".equals(scheme))
                        && ("127.0.0.1".equals(host) || "localhost".equals(host))) {
                    return false;
                }
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, url));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                // 极速救援：tel: 调起系统拨号盘（不自动拨出）
                if ("tel".equals(scheme)) {
                    try {
                        startActivity(new Intent(Intent.ACTION_DIAL, url));
                    } catch (Exception ignored) {
                    }
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                // 页面就绪后补一次安全区与通知权限状态
                evaluateJs("window.__ccApplyInsets&&window.__ccApplyInsets()");
                if (notificationsGranted()) {
                    evaluateJs("window.__ccNotifyPermission&&window.__ccNotifyPermission('granted')");
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.w(TAG, "main frame error: " + error.getDescription());
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                Log.i(TAG, "js: " + m.message() + " @" + m.lineNumber());
                return true;
            }

            @Override
            public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
                GlassDialog.alert(MainActivity.this, message, result::confirm);
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView view, String url, String message, JsResult result) {
                boolean destructive = message != null
                        && (message.contains("删除") || message.contains("清空") || message.contains("不可恢复"));
                GlassDialog.confirm(MainActivity.this, message, destructive, ok -> {
                    if (ok) {
                        result.confirm();
                    } else {
                        result.cancel();
                    }
                });
                return true;
            }

            @Override
            public boolean onJsPrompt(WebView view, String url, String message,
                                      String defaultValue, JsPromptResult result) {
                GlassDialog.prompt(MainActivity.this, message, defaultValue, text -> {
                    if (text == null) {
                        result.cancel();
                    } else {
                        result.confirm(text);
                    }
                });
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (pendingFileCallback != null) {
                    pendingFileCallback.onReceiveValue(null);
                }
                pendingFileCallback = callback;
                try {
                    startActivityForResult(buildFileChooserIntent(params), REQ_FILE);
                } catch (Exception e) {
                    Log.w(TAG, "file chooser failed", e);
                    pendingFileCallback = null;
                    callback.onReceiveValue(null);
                }
                return true;
            }
        });
    }

    private void startServerAndLoad() {
        server = new LocalAssetServer(this);
        int port = server.start();
        if (port <= 0) {
            GlassDialog.alert(this, "本地服务启动失败，请重启应用", null);
            return;
        }
        web.loadUrl("http://127.0.0.1:" + port + "/index.html");
    }

    void evaluateJs(String js) {
        if (web == null) return;
        runOnUiThread(() -> {
            try {
                web.evaluateJavascript(js, null);
            } catch (Exception ignored) {
            }
        });
    }

    // ------------------------------------------------------------ 通知权限

    boolean notificationsGranted() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true;
        return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (bridge != null) bridge.onNotificationPermission(true);
            return;
        }
        if (notificationsGranted()) {
            if (bridge != null) bridge.onNotificationPermission(true);
            return;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIF) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (bridge != null) bridge.onNotificationPermission(granted);
            return;
        }
        if (requestCode == REQ_HOLD_VOICE) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            android.widget.Toast.makeText(this,
                    granted ? "已允许麦克风，再按一次即可说话" : "未允许麦克风，按住说话不可用",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------ 文件选择 / 拍照

    private Intent buildFileChooserIntent(WebChromeClient.FileChooserParams params) {
        String[] accept = params != null ? params.getAcceptTypes() : null;
        String type = accept != null && accept.length > 0 ? accept[0] : "";
        boolean isImage = type != null && type.contains("image");

        Intent content = new Intent(Intent.ACTION_GET_CONTENT);
        content.addCategory(Intent.CATEGORY_OPENABLE);
        content.setType(isImage ? "image/*" : "*/*");

        if (!isImage) {
            return Intent.createChooser(content, "选择文件");
        }
        Intent chooser = Intent.createChooser(content, "选择图片或拍照");
        Intent camera = buildCameraIntent();
        if (camera != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[]{camera});
        }
        return chooser;
    }

    private Intent buildCameraIntent() {
        try {
            String name = "carcare_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                    .format(new Date()) + ".jpg";
            File photo = ShareProvider.fileFor(this, name);
            pendingCameraUri = ShareProvider.uriFor(this, photo);

            Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            camera.putExtra(MediaStore.EXTRA_OUTPUT, pendingCameraUri);
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            if (camera.resolveActivity(getPackageManager()) == null) {
                return null;
            }
            return camera;
        } catch (Exception e) {
            Log.w(TAG, "camera intent failed", e);
            return null;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == 2001) { // 保存到「文件」
            if (bridge != null) bridge.onSaveResult(resultCode, data);
            return;
        }
        if (requestCode == REQ_VOICE) {
            if (resultCode == RESULT_OK && data != null) {
                java.util.ArrayList<String> list =
                        data.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS);
                if (list != null && !list.isEmpty()) {
                    String text = list.get(0);
                    String js = "ccVoiceResult(" + org.json.JSONObject.quote(text) + ")";
                    evaluateJs(js);
                }
            }
            return;
        }
        if (requestCode != REQ_FILE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        ValueCallback<Uri[]> callback = pendingFileCallback;
        pendingFileCallback = null;
        if (callback == null) return;

        if (resultCode == RESULT_OK && data == null && pendingCameraUri != null) {
            callback.onReceiveValue(new Uri[]{pendingCameraUri});
            pendingCameraUri = null;
            return;
        }
        pendingCameraUri = null;
        if (resultCode != RESULT_OK || data == null) {
            callback.onReceiveValue(null);
            return;
        }
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        if (result == null && data.getData() != null) {
            result = new Uri[]{data.getData()};
        }
        callback.onReceiveValue(result);
    }

    // ------------------------------------------------------------ 语音记账

    /**
     * 调系统语音识别（RecognizerIntent）：不申请录音权限，收音与识别都由识别器 App 完成，
     * 识别结果通过 ccVoiceResult() 回填到网页表单。
     */
    void startVoiceInput() {
        Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        i.putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "说出内容，如「加油 300 块」");
        try {
            startActivityForResult(i, REQ_VOICE);
        } catch (Exception e) {
            Log.w(TAG, "voice input unavailable", e);
            GlassDialog.alert(this, "没找到语音识别服务。可在系统设置里安装/开启「语音服务」后重试。", null);
        }
    }

    /** 角标：Android 的桌面数字角标依赖通知，这里只存档（首页用小组件展示），不改动通知栏 */
    void applyBadge(int count) {
        getSharedPreferences("carcare_widget", MODE_PRIVATE)
                .edit().putInt("badge", count).apply();
    }

    // ------------------------------------------------------------ AI 对话按住说话

    private android.speech.SpeechRecognizer holdRecognizer;
    private volatile boolean holdActive;
    private volatile boolean holdAwaitingFinal;
    private String holdLastText = "";
    private final android.os.Handler holdHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable holdTimeout;
    private Runnable holdFinalFallback;

    /**
     * 应用内 SpeechRecognizer（区别于语音记账的系统识别器弹窗）：
     * 按住开始识别、边说边回传部分结果，松开把最终文字交给网页直接发问。
     */
    void startVoiceHold() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
            // 权限弹窗会打断「按住」手势：先把网页浮层收起，授权后再按一次即可
            evaluateJs("ccAiVoiceCancel()");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_HOLD_VOICE);
            return;
        }
        beginVoiceHold();
    }

    private void beginVoiceHold() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            failVoiceHold("没找到语音识别服务，可在系统设置里检查「语音服务」");
            return;
        }
        destroyHoldRecognizer();
        holdActive = true;
        holdAwaitingFinal = false;
        holdLastText = "";
        try {
            holdRecognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this);
            holdRecognizer.setRecognitionListener(new android.speech.RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) { }
                @Override public void onBeginningOfSpeech() { }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() { }
                @Override public void onEvent(int eventType, Bundle params) { }

                @Override
                public void onPartialResults(Bundle partialResults) {
                    String t = firstRecognition(partialResults);
                    if (t != null && !t.isEmpty()) {
                        holdLastText = t;
                        if (holdActive) {
                            evaluateJs("ccAiVoicePartial(" + org.json.JSONObject.quote(t) + ")");
                        }
                    }
                }

                @Override
                public void onResults(Bundle results) {
                    finishVoiceHold(firstRecognition(results));
                }

                @Override
                public void onError(int error) {
                    if (holdAwaitingFinal) {
                        // stopListening 后有的识别器回 NO_MATCH/SPEECH_TIMEOUT，用最后的部分结果兜底
                        finishVoiceHold(holdLastText);
                        return;
                    }
                    if (!holdActive) return;
                    String msg;
                    switch (error) {
                        case android.speech.SpeechRecognizer.ERROR_NO_MATCH:
                            msg = "没听清，请再试一次";
                            break;
                        case android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                            msg = "没有检测到说话";
                            break;
                        case android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                            msg = "请在系统设置里允许「麦克风」";
                            break;
                        case android.speech.SpeechRecognizer.ERROR_NETWORK:
                        case android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                            msg = "识别服务网络异常，请重试";
                            break;
                        default:
                            msg = "识别出错，请再试一次";
                    }
                    failVoiceHold(msg);
                }
            });

            Intent i = new Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            i.putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            i.putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 1);
            holdRecognizer.startListening(i);

            // 最长 30 秒自动按「松开」处理
            holdTimeout = () -> {
                if (holdActive) releaseVoiceHold(false);
            };
            holdHandler.postDelayed(holdTimeout, 30000);
        } catch (Exception e) {
            Log.w(TAG, "hold voice start failed", e);
            failVoiceHold("语音启动失败，请重试");
        }
    }

    void releaseVoiceHold(boolean cancel) {
        holdHandler.removeCallbacks(holdTimeout);
        holdTimeout = null;
        if (holdRecognizer == null) {
            if (cancel) evaluateJs("ccAiVoiceCancel()");
            return;
        }
        if (cancel) {
            holdActive = false;
            holdAwaitingFinal = false;
            destroyHoldRecognizer();
            evaluateJs("ccAiVoiceCancel()");
            return;
        }
        holdActive = false;
        holdAwaitingFinal = true;
        try {
            holdRecognizer.stopListening();
        } catch (Exception ignored) {
        }
        // 3 秒兜底：最终结果迟迟不来就用最后的部分结果
        holdFinalFallback = () -> {
            if (holdAwaitingFinal) finishVoiceHold(holdLastText);
        };
        holdHandler.postDelayed(holdFinalFallback, 3000);
    }

    private void finishVoiceHold(String text) {
        holdHandler.removeCallbacks(holdFinalFallback);
        holdFinalFallback = null;
        if (!holdActive && !holdAwaitingFinal) return;
        holdActive = false;
        holdAwaitingFinal = false;
        destroyHoldRecognizer();
        String t = text == null ? "" : text.trim();
        evaluateJs("ccAiVoiceResult(" + org.json.JSONObject.quote(t) + ")");
    }

    private void failVoiceHold(String reason) {
        holdHandler.removeCallbacks(holdTimeout);
        holdTimeout = null;
        holdHandler.removeCallbacks(holdFinalFallback);
        holdFinalFallback = null;
        holdActive = false;
        holdAwaitingFinal = false;
        destroyHoldRecognizer();
        android.widget.Toast.makeText(this, reason, android.widget.Toast.LENGTH_SHORT).show();
        evaluateJs("ccAiVoiceCancel()");
    }

    private void destroyHoldRecognizer() {
        if (holdRecognizer != null) {
            try {
                holdRecognizer.destroy();
            } catch (Exception ignored) {
            }
            holdRecognizer = null;
        }
    }

    private static String firstRecognition(Bundle bundle) {
        if (bundle == null) return null;
        java.util.ArrayList<String> list = bundle.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        String s = list.get(0);
        return s == null ? null : s.trim();
    }

    // ------------------------------------------------------------ 生命周期

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK && web != null && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    @Override
    protected void onDestroy() {
        holdHandler.removeCallbacksAndMessages(null);
        destroyHoldRecognizer();
        if (server != null) server.stop();
        if (web != null) {
            web.removeJavascriptInterface("CarCareAndroid");
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setupEdgeToEdgeFlagsOnly();
    }

    private void setupEdgeToEdgeFlagsOnly() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            int flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
    }
}
