package com.xm486.pimet.ui;

import android.webkit.WebSettings;
import android.webkit.WebView;

/**
 * 工作台网页双指缩放支持。
 *
 * <p>与悬浮球上的「文字缩放」按钮（setTextZoom，调整字号并重排）互不冲突：
 * 双指手势驱动的是 WebView 原生页面缩放（视觉放大/缩小），两者各管一层。</p>
 *
 * <p>防溢出策略：</p>
 * <ul>
 *   <li>视口固定为 device-width，最小缩放 = 1（适配屏宽，不能再缩小出空白），最大 5 倍；</li>
 *   <li>放大后的平移由 WebView 原生限制在页面边界内；</li>
 *   <li>根元素横向 overflow 裁切，避免文字放大后内容撑出屏幕形成横向空白。</li>
 * </ul>
 * 仅对本地服务页面 (127.0.0.1 / localhost) 改写视口，外部网站保持其原生行为。
 */
public final class WebPinchZoom {

    public static final String VIEWPORT =
            "width=device-width,initial-scale=1,minimum-scale=1,maximum-scale=5,user-scalable=yes";

    private static final String JS = "(function(){try{" +
            "var C='" + VIEWPORT + "';" +
            "function fix(){" +
            "  var h=document.head||document.documentElement; if(!h) return;" +
            "  var ms=document.querySelectorAll('meta[name=\"viewport\"]');" +
            "  var m=ms.length?ms[0]:null;" +
            "  for(var i=1;i<ms.length;i++){ if(ms[i].parentNode) ms[i].parentNode.removeChild(ms[i]); }" +
            "  if(!m){ m=document.createElement('meta'); m.setAttribute('name','viewport'); h.appendChild(m); }" +
            "  if(m.getAttribute('content')!==C) m.setAttribute('content',C);" +
            "  if(!document.getElementById('__pimet_pinch_css')){" +
            "    var s=document.createElement('style'); s.id='__pimet_pinch_css';" +
            "    s.textContent='html,body{max-width:100%;overflow-x:clip;}';" +
            "    h.appendChild(s);" +
            "  }" +
            "}" +
            "fix();" +
            "if(!window.__pimetPinchObs && window.MutationObserver && document.head){" +
            "  window.__pimetPinchObs=new MutationObserver(function(){fix();});" +
            "  window.__pimetPinchObs.observe(document.head,{childList:true,subtree:true,attributes:true,attributeFilter:['content']});" +
            "}" +
            "}catch(e){}})();";

    private WebPinchZoom() {}

    /** 开启 WebView 原生双指缩放，隐藏系统 +/- 缩放控件。 */
    public static void configure(WebView webView) {
        if (webView == null) return;
        WebSettings ws = webView.getSettings();
        ws.setSupportZoom(true);
        ws.setBuiltInZoomControls(true);
        ws.setDisplayZoomControls(false);
        ws.setUseWideViewPort(true);
        ws.setLoadWithOverviewMode(true);
    }

    /** 页面可见/加载完成后调用：放开页面对双指缩放的限制，并加入防横向溢出保护。 */
    public static void apply(WebView webView, String url) {
        if (webView == null || !isLocal(url)) return;
        webView.evaluateJavascript(JS, null);
    }

    private static boolean isLocal(String url) {
        if (url == null) return false;
        return url.startsWith("http://127.0.0.1") || url.startsWith("http://localhost")
                || url.startsWith("https://127.0.0.1") || url.startsWith("https://localhost");
    }
}
