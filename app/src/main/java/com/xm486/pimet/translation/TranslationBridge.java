package com.xm486.pimet.translation;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

/**
 * 宿主 WebView 原生通信桥接 (TranslationBridge)
 * 提供 window.PiMetTranslator 接口，实现与页面 DOM 双向无障碍通信并彻底绕过浏览器跨域限制
 */
public class TranslationBridge {

    public interface StateListener {
        void onTranslationStateChanged(boolean isTranslated, int doneCount, int totalCount);
    }

    private final Activity activity;
    private final WebView webView;
    private final Handler mainHandler;
    private StateListener stateListener;

    public TranslationBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    /**
     * 接收页面提交的一批文本待翻译任务
     */
    @JavascriptInterface
    public void requestBatch(final String batchId, final String textsJson) {
        if (activity == null || webView == null || textsJson == null) return;

        List<String> texts = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(textsJson);
            for (int i = 0; i < arr.length(); i++) {
                texts.add(arr.getString(i));
            }
        } catch (Throwable t) {
            notifyBatchError(batchId, "页面提交的文本格式不对");
            return;
        }

        WebTranslationManager.translateBatch(activity, texts, new WebTranslationManager.TranslationCallback() {
            @Override
            public void onSuccess(List<String> translatedTexts) {
                JSONArray resArr = new JSONArray();
                for (String s : translatedTexts) {
                    resArr.put(s);
                }
                final String resJson = resArr.toString();
                mainHandler.post(() -> {
                    if (webView != null) {
                        String js = "if (window.__pimet_receive_batch) window.__pimet_receive_batch(" +
                                org.json.JSONObject.quote(batchId) + ", " + JSONObjectEscape(resJson) + ", false);";
                        webView.evaluateJavascript(js, null);
                    }
                });
            }

            @Override
            public void onError(String error) {
                notifyBatchError(batchId, error);
            }
        });
    }

    private long lastErrorToastAt = 0L;
    private String lastErrorToast = null;

    private void notifyBatchError(final String batchId, final String reason) {
        final String msg = reason == null || reason.isEmpty() ? "翻译服务无响应" : reason;
        mainHandler.post(() -> {
            if (webView != null) {
                String js = "if (window.__pimet_receive_batch) window.__pimet_receive_batch(" +
                        org.json.JSONObject.quote(batchId) + ", '[]', true, " + org.json.JSONObject.quote(msg) + ");";
                webView.evaluateJavascript(js, null);
            }
            // 同一原因 8 秒内只提示一次（一页会有几十批同时失败）
            long now = System.currentTimeMillis();
            if (!msg.equals(lastErrorToast) || now - lastErrorToastAt > 8000L) {
                lastErrorToast = msg;
                lastErrorToastAt = now;
                WebTranslationManager.toast(activity, "网页翻译失败：" + msg);
            }
        });
    }

    /**
     * 页面指示胶囊或悬浮按钮点击打开专属翻译配置面板
     */
    @JavascriptInterface
    public void openSettingsDialog() {
        mainHandler.post(() -> {
            if (activity != null && !activity.isFinishing()) {
                WebTranslationDialog.show(activity, webView);
            }
        });
    }

    /**
     * 页面翻译状态同步回宿主
     */
    @JavascriptInterface
    public void onStateChanged(final boolean isTranslated, final int doneCount, final int totalCount) {
        mainHandler.post(() -> {
            if (stateListener != null) {
                stateListener.onTranslationStateChanged(isTranslated, doneCount, totalCount);
            }
        });
    }

    /**
     * 页面弹出原生提示
     */
    @JavascriptInterface
    public void showToast(final String message) {
        mainHandler.post(() -> {
            if (activity != null && message != null) {
                WebTranslationManager.toast(activity, message);
            }
        });
    }

    private static String JSONObjectEscape(String json) {
        if (json == null) return "''";
        return org.json.JSONObject.quote(json);
    }
}
