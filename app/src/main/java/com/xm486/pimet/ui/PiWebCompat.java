package com.xm486.pimet.ui;

import android.webkit.WebView;

/**
 * Pi-Web 在 PiMet WebView 里的兼容修正（只作用于本地服务页 127.0.0.1 / localhost）。
 *
 * <p><b>聊天框下方模型/设置栏被「吞掉」</b>：Pi-Web 的 useViewportHeight 用
 * {@code innerHeight / visualViewport.scale - visualViewport.height > 60} 且有输入框聚焦来判断「软键盘已弹出」，
 * 判定成立就给 html 打上 {@code data-keyboard-open}，CSS 随即把 {@code .chat-input-controls}
 * （附件 + 模型选择 + 思考档位那一行）、扩展状态栏、新会话项目栏全部 {@code display:none}，
 * 同时把 {@code --app-viewport-height} 改成 visualViewport 高度。</p>
 *
 * <p>这套逻辑是给 iOS PWA 用的。PiMet 的 Activity 是 adjustResize：键盘弹出时系统直接把 WebView 缩到键盘上方，
 * Pi-Web 本来就不需要再自己缩。但 PiMet 的布局缩放（{@link WebPinchZoom}，非 100% 时 initial-scale≠1）会让
 * scale 与 innerHeight 不再对应，输入框只要聚焦（哪怕按返回键收起了键盘）就被误判为「键盘弹出」，
 * 模型栏随之消失、整页高度也被压矮。</p>
 *
 * <p>修正：观察 html 根元素，Pi-Web 一旦写入 {@code data-keyboard-open} 或内联的 {@code --app-viewport-height}
 * 就立即撤销（MutationObserver 回调在下一帧绘制前执行，不会闪）。键盘避让完全交给 adjustResize，
 * 与 100% 缩放下的表现一致。</p>
 */
public final class PiWebCompat {

    private PiWebCompat() {}

    private static final String KEYBOARD_GUARD_JS = "(function(){try{" +
            "if(window.__pimetKbGuard)return;window.__pimetKbGuard=1;" +
            "var r=document.documentElement;if(!r)return;" +
            "var fix=function(){" +
            " if(r.hasAttribute('data-keyboard-open'))r.removeAttribute('data-keyboard-open');" +
            " if(r.style&&r.style.getPropertyValue('--app-viewport-height'))r.style.removeProperty('--app-viewport-height');" +
            "};" +
            "fix();" +
            "if(window.MutationObserver)new MutationObserver(fix).observe(r,{attributes:true,attributeFilter:['data-keyboard-open','style']});" +
            "}catch(e){}})();";

    public static boolean isLocalPage(String url) {
        return url != null && (url.startsWith("http://127.0.0.1:") || url.startsWith("http://localhost:")
                || url.startsWith("https://127.0.0.1:") || url.startsWith("https://localhost:"));
    }

    /** 在 onPageCommitVisible / onPageFinished 调用；重复调用无副作用。 */
    public static void apply(WebView webView, String url) {
        if (webView == null || !isLocalPage(url)) return;
        try {
            webView.evaluateJavascript(KEYBOARD_GUARD_JS, null);
        } catch (Throwable ignored) {
        }
    }
}
