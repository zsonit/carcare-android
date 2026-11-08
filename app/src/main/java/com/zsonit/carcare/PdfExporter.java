package com.zsonit.carcare;

import android.app.Activity;
import android.content.Context;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.util.Log;
import android.view.ViewGroup;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

/**
 * 导出 PDF：把网页拼好的打印用 HTML 交给系统打印框架，
 * 用户选「另存为 PDF」即得到矢量 PDF（也可直接连打印机打印）。
 * 这是 Android 上最稳、最原生的 PDF 导出方式，效果与浏览器「打印」一致。
 */
public final class PdfExporter {

    private static final String TAG = "CarCare";

    private PdfExporter() {
    }

    public static void print(Activity act, String html, String jobName) {
        if (act == null || act.isFinishing()) return;

        final WebView web = new WebView(act);
        final ViewGroup host = act.findViewById(R.id.root);
        final String name = (jobName == null || jobName.isEmpty()) ? "车管家记录" : jobName;

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        web.setBackgroundColor(0xFFFFFFFF);
        web.setAlpha(0.02f);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.postDelayed(() -> {
                    try {
                        PrintManager pm = (PrintManager) act.getSystemService(Context.PRINT_SERVICE);
                        if (pm == null) {
                            GlassDialog.alert(act, "此设备不支持打印，无法导出 PDF", null);
                            return;
                        }
                        PrintAttributes attrs = new PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .build();
                        pm.print(name, view.createPrintDocumentAdapter(name), attrs);
                    } catch (Exception e) {
                        Log.w(TAG, "print failed", e);
                        GlassDialog.alert(act, "PDF 导出失败：" + e.getMessage(), null);
                    }
                }, 500);
            }
        });

        int w = act.getResources().getDisplayMetrics().widthPixels;
        int h = act.getResources().getDisplayMetrics().heightPixels;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = -w * 3;
        if (host != null) host.addView(web, lp);

        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
    }
}
