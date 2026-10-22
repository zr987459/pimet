package com.xm486.pimet.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 让容器里的 Pi 操控「Pi 浏览器」悬浮窗（经 AppBridge 127.0.0.1:30143，需令牌）。
 * <p>
 * 思路借鉴 Playwright MCP / browser-use：每次返回页面的「快照」，把可交互元素编号成 [e1] [e2]…，
 * Pi 再用编号点击、输入；点击走真实触摸事件（isTrusted），用户在窗口里能看到每一步并随时接手。
 */
public final class BrowserAgent {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object LOCK = new Object();

    private BrowserAgent() {}

    public static JSONObject handle(Context ctx, JSONObject req) {
        synchronized (LOCK) {
            try {
                return doHandle(ctx, req);
            } catch (Throwable t) {
                return err("浏览器操作失败: " + t);
            }
        }
    }

    private static JSONObject doHandle(Context ctx, JSONObject req) throws Exception {
        String action = req.optString("browser_action", req.optString("op", "snapshot")).trim().toLowerCase();
        int timeout = clamp(req.optInt("timeout", 20000), 1000, 120000);

        if (action.equals("close")) {
            onMain(() -> {
                FloatingApps f = FloatingApps.current();
                if (f != null) f.agentClose();
                return null;
            }, 5000);
            return ok().put("result", "已关闭浏览器窗口");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(ctx)) {
            return err("PiMet 没有悬浮窗权限，无法打开浏览器窗口。请让用户在系统设置里给 PiMet 开启「显示在其他应用上层」。");
        }
        final FloatingApps win = onMain(() -> FloatingApps.ensureForAgent(ctx), 8000);
        if (win == null) return err("浏览器窗口创建失败");

        boolean wantSnapshot = req.optBoolean("snapshot", true);
        String result;
        switch (action) {
            case "open":
            case "navigate":
            case "goto": {
                String url = normalize(req.optString("url", ""));
                if (url.isEmpty()) return err("缺少 url");
                onMain(() -> { win.agentRestore(); win.agentLoad(url); return null; }, 5000);
                SystemClock.sleep(300);
                waitLoaded(win, timeout);
                result = "已打开 " + url;
                break;
            }
            case "home":
                onMain(() -> { win.agentHome(); return null; }, 5000);
                SystemClock.sleep(500);
                waitLoaded(win, timeout);
                result = "已回到首页";
                break;
            case "back":
            case "forward":
            case "reload": {
                onMain(() -> {
                    if (action.equals("back")) { if (win.web.canGoBack()) win.web.goBack(); }
                    else if (action.equals("forward")) { if (win.web.canGoForward()) win.web.goForward(); }
                    else win.web.reload();
                    return null;
                }, 5000);
                SystemClock.sleep(400);
                waitLoaded(win, timeout);
                result = action.equals("back") ? "已后退" : action.equals("forward") ? "已前进" : "已刷新";
                break;
            }
            case "snapshot":
            case "read":
                waitLoaded(win, Math.min(timeout, 8000));
                wantSnapshot = true;
                result = null;
                break;
            case "click":
            case "tap": {
                result = click(win, req);
                settle(win, timeout);
                break;
            }
            case "type":
            case "fill": {
                result = type(win, req);
                settle(win, timeout);
                break;
            }
            case "select": {
                String ref = req.optString("ref", "");
                String js = "(function(ref,v){var el=document.querySelector('[data-pimet-ref=\"'+ref+'\"]');"
                        + "if(!el)return {ok:false,error:'找不到元素 '+ref+'，请先 snapshot'};"
                        + "if(el.tagName!=='SELECT')return {ok:false,error:ref+' 不是下拉框'};"
                        + "var o=Array.from(el.options).find(function(o){return o.value===v||o.text.trim()===v;});"
                        + "if(!o)return {ok:false,error:'没有选项 '+v+'，可选：'+Array.from(el.options).map(function(o){return o.text.trim()}).join(' / ')};"
                        + "el.value=o.value;el.dispatchEvent(new Event('input',{bubbles:true}));"
                        + "el.dispatchEvent(new Event('change',{bubbles:true}));return {ok:true,result:'已选择 '+o.text.trim()};})("
                        + JSONObject.quote(ref) + "," + JSONObject.quote(req.optString("value", "")) + ")";
                JSONObject r = evalJson(win, js, 5000);
                if (!r.optBoolean("ok")) return err(r.optString("error", "选择失败"));
                result = r.optString("result");
                settle(win, timeout);
                break;
            }
            case "press":
            case "key": {
                String key = req.optString("key", "Enter");
                int code = keyCode(key);
                if (code == 0) return err("不支持的按键 " + key + "（支持 Enter Tab Escape Backspace Delete Space ArrowUp/Down/Left/Right PageUp PageDown Home End）");
                onMain(() -> {
                    win.web.requestFocus();
                    long now = SystemClock.uptimeMillis();
                    win.web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                    win.web.dispatchKeyEvent(new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, code, 0));
                    return null;
                }, 5000);
                result = "已按 " + key;
                settle(win, timeout);
                break;
            }
            case "scroll": {
                String ref = req.optString("ref", "");
                String dir = req.optString("direction", "down");
                double amount = req.optDouble("amount", 0.8);
                String js = "(function(ref,dir,amt){if(ref){var el=document.querySelector('[data-pimet-ref=\"'+ref+'\"]');"
                        + "if(!el)return {ok:false,error:'找不到元素 '+ref};el.scrollIntoView({block:'center'});return {ok:true,result:'已滚动到 '+ref};}"
                        + "var dy=dir==='up'?-1:dir==='down'?1:0,dx=dir==='left'?-1:dir==='right'?1:0;"
                        + "var t=document.elementFromPoint(innerWidth/2,innerHeight/2),sc=null;"
                        + "while(t&&t!==document.body&&t!==document.documentElement){var s=getComputedStyle(t);"
                        + "if((dy&&/(auto|scroll)/.test(s.overflowY)&&t.scrollHeight>t.clientHeight+4)||(dx&&/(auto|scroll)/.test(s.overflowX)&&t.scrollWidth>t.clientWidth+4)){sc=t;break;}t=t.parentElement;}"
                        + "var tgt=sc||document.scrollingElement||document.documentElement;"
                        + "var h=sc?sc.clientHeight:innerHeight,w=sc?sc.clientWidth:innerWidth;"
                        + "tgt.scrollBy({left:dx*w*amt,top:dy*h*amt,behavior:'instant'});"
                        + "return {ok:true,result:'已滚动 '+dir};})("
                        + JSONObject.quote(ref) + "," + JSONObject.quote(dir) + "," + amount + ")";
                JSONObject r = evalJson(win, js, 5000);
                if (!r.optBoolean("ok")) return err(r.optString("error", "滚动失败"));
                result = r.optString("result");
                SystemClock.sleep(350);
                break;
            }
            case "wait": {
                String text = req.optString("text", "");
                if (!text.isEmpty()) {
                    long end = SystemClock.uptimeMillis() + timeout;
                    boolean found = false;
                    while (SystemClock.uptimeMillis() < end) {
                        JSONObject r = evalJson(win, "({ok:true,v:document.body?document.body.innerText.indexOf("
                                + JSONObject.quote(text) + ")>=0:false})", 3000);
                        if (r.optBoolean("v")) { found = true; break; }
                        SystemClock.sleep(400);
                    }
                    result = found ? "页面已出现「" + text + "」" : "等待超时，页面没有出现「" + text + "」";
                } else {
                    SystemClock.sleep(clamp(req.optInt("ms", 1000), 0, 60000));
                    waitLoaded(win, timeout);
                    result = "已等待";
                }
                break;
            }
            case "eval":
            case "js": {
                String script = req.optString("script", "");
                if (script.isEmpty()) return err("缺少 script");
                String v = evalAsync(win, script, timeout);
                JSONObject out = ok().put("url", win.url()).put("result", v);
                return out;
            }
            case "screenshot": {
                onMain(() -> { win.agentRestore(); return null; }, 3000);
                SystemClock.sleep(250);
                String b64 = onMain(() -> capture(win.web), 8000);
                if (b64 == null) return err("截图失败（窗口不可见？）");
                return ok().put("url", win.url()).put("image", b64).put("mime", "image/jpeg");
            }
            case "show":
                onMain(() -> { win.agentRestore(); return null; }, 3000);
                result = "浏览器窗口已显示";
                wantSnapshot = false;
                break;
            case "hide":
            case "minimize":
                onMain(() -> { win.agentMinimize(); return null; }, 3000);
                result = "浏览器窗口已最小化（仍可继续操作）";
                wantSnapshot = false;
                break;
            case "reload_ui":
            case "reload_config":
                onMain(() -> { win.reloadConfigNow(); return null; }, 3000);
                result = "已重新加载浏览器配置（config.json）；用户脚本会在下次打开/刷新网页时生效";
                wantSnapshot = false;
                break;
            case "status":
                result = "url: " + win.url();
                wantSnapshot = false;
                break;
            default:
                return err("未知操作 " + action);
        }

        JSONObject out = ok();
        if (result != null) out.put("result", result);
        if (wantSnapshot) {
            JSONObject snap = snapshot(win, req.optInt("max_chars", 6000));
            out.put("url", snap.optString("url"));
            out.put("title", snap.optString("title"));
            out.put("snapshot", snap.optString("text"));
        } else {
            out.put("url", win.url());
        }
        return out;
    }

    // ------------------------------------------------------------------ 快照

    private static final String SNAPSHOT_JS = "(function(maxChars){"
            + "document.querySelectorAll('[data-pimet-ref]').forEach(function(e){e.removeAttribute('data-pimet-ref')});"
            + "function clean(s){return (s||'').replace(/\\s+/g,' ').trim();}"
            + "function cut(s,n){s=clean(s);return s.length>n?s.slice(0,n)+'…':s;}"
            + "function visible(el){var r=el.getBoundingClientRect();if(r.width<2||r.height<2)return false;"
            + "var s=getComputedStyle(el);return s.visibility!=='hidden'&&s.display!=='none'&&parseFloat(s.opacity||'1')>0.05;}"
            + "var sel='a[href],button,input:not([type=hidden]),textarea,select,summary,label[for],[role=button],[role=link],[role=checkbox],[role=radio],"
            + "[role=tab],[role=menuitem],[role=option],[role=switch],[role=combobox],[role=textbox],[role=searchbox],[contenteditable=\"\"],[contenteditable=true],[onclick]';"
            + "var all=Array.from(document.querySelectorAll(sel));"
            // 额外收集 cursor:pointer 的小元素（很多站点用 div 当按钮）
            + "try{Array.from(document.querySelectorAll('div,span,li,img,svg')).slice(0,3000).forEach(function(e){"
            + "if(all.indexOf(e)<0&&getComputedStyle(e).cursor==='pointer'&&!(e.parentElement&&getComputedStyle(e.parentElement).cursor==='pointer')"
            + "&&!e.closest(sel))all.push(e);});}catch(x){}"
            + "var lines=[],n=0,H=innerHeight,W=innerWidth;"
            + "for(var i=0;i<all.length&&n<220;i++){var el=all[i];if(!visible(el))continue;"
            + "var tag=el.tagName.toLowerCase(),type=(el.getAttribute('type')||'').toLowerCase();"
            + "var role=el.getAttribute('role')||(tag==='a'?'link':tag==='button'?'button':tag==='select'?'select':tag==='textarea'?'textbox'"
            + ":tag==='input'?(type==='checkbox'||type==='radio'?type:(type==='submit'||type==='button'?'button':'textbox')):el.isContentEditable?'textbox':'clickable');"
            + "var name=el.getAttribute('aria-label')||el.getAttribute('title')||'';"
            + "if(!name&&tag!=='input'&&tag!=='textarea'&&tag!=='select')name=el.innerText||'';"
            + "if(!name){var img=el.querySelector&&el.querySelector('img[alt]');if(img)name=img.getAttribute('alt');}"
            + "if(!name&&tag==='img')name=el.getAttribute('alt')||'';"
            + "if(!name&&(tag==='input'||tag==='textarea'))name=el.getAttribute('placeholder')||el.getAttribute('name')||'';"
            + "if(!name&&(type==='submit'||type==='button'))name=el.value||'';"
            + "name=cut(name,70);if(!name&&role==='clickable')continue;"
            + "n++;var ref='e'+n;el.setAttribute('data-pimet-ref',ref);"
            + "var extra='';"
            + "if(role==='textbox'||tag==='input'){var v=el.isContentEditable?el.innerText:el.value;if(v&&type!=='password')extra+=' 值=\"'+cut(v,60)+'\"';"
            + "if(el.getAttribute('placeholder')&&name!==cut(el.getAttribute('placeholder'),70))extra+=' 提示=\"'+cut(el.getAttribute('placeholder'),40)+'\"';"
            + "if(type&&type!=='text'&&type!=='checkbox'&&type!=='radio')extra+=' type='+type;}"
            + "if(type==='checkbox'||type==='radio'||el.getAttribute('aria-checked'))extra+=(el.checked||el.getAttribute('aria-checked')==='true')?' [已选]':' [未选]';"
            + "if(tag==='select'){var o=el.options[el.selectedIndex];extra+=' 当前=\"'+cut(o?o.text:'',30)+'\" 选项数='+el.options.length;}"
            + "if(el.disabled||el.getAttribute('aria-disabled')==='true')extra+=' [禁用]';"
            + "var r=el.getBoundingClientRect();if(r.bottom<0)extra+=' (上方)';else if(r.top>H)extra+=' (下方)';"
            + "lines.push('['+ref+'] '+role+(name?' \"'+name+'\"':'')+extra);}"
            + "var text=document.body?clean(document.body.innerText):'';"
            + "var se=document.scrollingElement||document.documentElement;"
            + "var head='网址: '+location.href+'\\n标题: '+document.title+'\\n滚动: '+Math.round(se.scrollTop)+'/'+Math.max(0,Math.round(se.scrollHeight-H))"
            + "+' (视口 '+W+'x'+H+')';"
            + "var frames=document.querySelectorAll('iframe').length;if(frames)head+='\\n注意: 页面含 '+frames+' 个 iframe，其中内容不在快照里';"
            + "var body='\\n\\n可交互元素（用 ref 操作）:\\n'+(lines.join('\\n')||'(无)')+'\\n\\n页面文字:\\n';"
            + "var room=Math.max(800,maxChars-head.length-body.length);"
            + "return {url:location.href,title:document.title,text:head+body+(text.length>room?text.slice(0,room)+'…（已截断，可滚动或用 eval 读取更多）':text)};"
            + "})";

    private static JSONObject snapshot(FloatingApps win, int maxChars) throws Exception {
        int mc = clamp(maxChars, 1500, 40000);
        JSONObject r = evalJson(win, SNAPSHOT_JS + "(" + mc + ")", 8000);
        if (!r.has("text")) {
            r.put("url", win.url()).put("title", "").put("text", "网址: " + win.url() + "\n（页面还没准备好，无法读取快照）");
        }
        return r;
    }

    // ------------------------------------------------------------------ 点击 / 输入

    private static final String LOCATE_JS = "(function(ref,text){var el=null;"
            + "if(ref)el=document.querySelector('[data-pimet-ref=\"'+ref+'\"]');"
            + "if(!el&&text){var c=Array.from(document.querySelectorAll('a,button,[role=button],input[type=submit],input[type=button],[onclick],label,summary,li,span,div'));"
            + "var best=null;for(var i=0;i<c.length;i++){var e=c[i];var t=(e.innerText||e.value||e.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim();"
            + "if(t&&t.indexOf(text)>=0){var r0=e.getBoundingClientRect();if(r0.width<2||r0.height<2)continue;if(!best||t.length<best.len){best={e:e,len:t.length};}}}"
            + "if(best)el=best.e;}"
            + "if(!el)return {ok:false,error:ref?('找不到元素 '+ref+'，页面可能已变化，请重新 snapshot'):'找不到包含「'+text+'」的元素'};"
            + "el.scrollIntoView({block:'center',inline:'center',behavior:'instant'});"
            + "var r=el.getBoundingClientRect();"
            + "try{var o=document.createElement('div');o.style.cssText='position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #ff4d4f;"
            + "border-radius:4px;box-shadow:0 0 0 3px rgba(255,77,79,.3);left:'+(r.left-2)+'px;top:'+(r.top-2)+'px;width:'+(r.width)+'px;height:'+(r.height)+'px';"
            + "document.documentElement.appendChild(o);setTimeout(function(){o.remove()},700);}catch(x){}"
            + "var x=r.left+r.width/2,y=r.top+r.height/2;"
            + "var hit=document.elementFromPoint(x,y);var covered=hit&&hit!==el&&!el.contains(hit)&&!hit.contains(el);"
            + "window.__pimetTarget=el;"
            + "var vv=window.visualViewport,vx=vv?x-vv.offsetLeft:x,vy=vv?y-vv.offsetTop:y,vw=vv?vv.width:innerWidth;"
            + "return {ok:true,x:vx,y:vy,vw:vw,covered:!!covered,tag:el.tagName.toLowerCase(),"
            + "label:(el.innerText||el.value||el.getAttribute('aria-label')||'').replace(/\\s+/g,' ').trim().slice(0,40)};})";

    private static String click(FloatingApps win, JSONObject req) throws Exception {
        String ref = req.optString("ref", "");
        String text = req.optString("text", "");
        if (ref.isEmpty() && text.isEmpty()) throw new IllegalArgumentException("click 需要 ref 或 text");
        JSONObject loc = evalJson(win, LOCATE_JS + "(" + JSONObject.quote(ref) + "," + JSONObject.quote(text) + ")", 5000);
        if (!loc.optBoolean("ok")) throw new IllegalStateException(loc.optString("error", "定位失败"));
        SystemClock.sleep(120);
        boolean useJs = req.optBoolean("js", false);
        boolean nativeOk = false;
        if (!useJs) {
            nativeOk = Boolean.TRUE.equals(onMain(() -> nativeTap(win, loc.optDouble("x"), loc.optDouble("y"), loc.optDouble("vw")), 4000));
        }
        if (!nativeOk) {
            evalJson(win, "(function(){var el=window.__pimetTarget;if(!el)return {ok:false};var r=el.getBoundingClientRect(),x=r.left+r.width/2,y=r.top+r.height/2;"
                    + "['pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){try{el.dispatchEvent(new (t.indexOf('pointer')===0&&window.PointerEvent?PointerEvent:MouseEvent)"
                    + "(t,{bubbles:true,cancelable:true,clientX:x,clientY:y,view:window}))}catch(e){}});"
                    + "try{el.focus()}catch(e){}el.click();return {ok:true};})()", 5000);
        }
        String label = loc.optString("label");
        return "已点击 " + (ref.isEmpty() ? "「" + text + "」" : ref) + (label.isEmpty() ? "" : "（" + label + "）")
                + (loc.optBoolean("covered") && !useJs ? "；注意：该位置被其它元素遮挡，可能点到了遮挡物，必要时加 js=true 重试" : "");
    }

    /** 真实触摸：CSS 坐标换算到 WebView 像素 */
    private static boolean nativeTap(FloatingApps win, double cssX, double cssY, double vw) {
        WebView web = win.web;
        if (win.minimized || web.getWidth() <= 0 || web.getHeight() <= 0 || vw <= 0 || !web.isShown()) return false;
        float ratio = (float) (web.getWidth() / vw);
        float x = (float) (cssX * ratio), y = (float) (cssY * ratio);
        if (x < 0 || y < 0 || x > web.getWidth() || y > web.getHeight()) return false;
        long down = SystemClock.uptimeMillis();
        MotionEvent d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent u = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0);
        d.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        u.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(d);
        web.dispatchTouchEvent(u);
        d.recycle();
        u.recycle();
        return true;
    }

    private static String type(FloatingApps win, JSONObject req) throws Exception {
        String ref = req.optString("ref", "");
        String text = req.optString("text", "");
        boolean clear = req.optBoolean("clear", true);
        boolean submit = req.optBoolean("submit", false);
        String js = "(function(ref,text,clear){var el=ref?document.querySelector('[data-pimet-ref=\"'+ref+'\"]'):document.activeElement;"
                + "if(!el||el===document.body)return {ok:false,error:ref?'找不到元素 '+ref+'，请重新 snapshot':'没有获得焦点的输入框，请指定 ref'};"
                + "if(!(el.isContentEditable||el.tagName==='INPUT'||el.tagName==='TEXTAREA')){var inner=el.querySelector&&el.querySelector('input,textarea,[contenteditable]');if(inner)el=inner;}"
                + "el.scrollIntoView({block:'center',behavior:'instant'});try{el.focus()}catch(e){}"
                + "if(el.isContentEditable){if(clear){document.execCommand('selectAll',false);document.execCommand('delete',false);}document.execCommand('insertText',false,text);}"
                + "else if(el.tagName==='INPUT'||el.tagName==='TEXTAREA'){var proto=el.tagName==='TEXTAREA'?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;"
                + "var set=Object.getOwnPropertyDescriptor(proto,'value').set;set.call(el,(clear?'':el.value)+text);"
                + "el.dispatchEvent(new InputEvent('input',{bubbles:true,inputType:'insertText',data:text}));el.dispatchEvent(new Event('change',{bubbles:true}));}"
                + "else return {ok:false,error:ref+' 不是输入框'};"
                + "window.__pimetTarget=el;return {ok:true,form:!!el.form};})("
                + JSONObject.quote(ref) + "," + JSONObject.quote(text) + "," + clear + ")";
        JSONObject r = evalJson(win, js, 5000);
        if (!r.optBoolean("ok")) throw new IllegalStateException(r.optString("error", "输入失败"));
        String res = "已输入" + (ref.isEmpty() ? "" : "到 " + ref);
        if (submit) {
            if (r.optBoolean("form")) {
                evalJson(win, "(function(){var el=window.__pimetTarget;try{if(el.form.requestSubmit)el.form.requestSubmit();else el.form.submit();}catch(e){return {ok:false}}return {ok:true};})()", 5000);
            } else {
                onMain(() -> {
                    long now = SystemClock.uptimeMillis();
                    win.web.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0));
                    win.web.dispatchKeyEvent(new KeyEvent(now, now + 30, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER, 0));
                    return null;
                }, 3000);
            }
            res += "并提交";
        }
        return res;
    }

    // ------------------------------------------------------------------ JS 执行

    /** 执行一段返回对象的表达式，结果按 JSON 解析 */
    private static JSONObject evalJson(FloatingApps win, String expr, long timeoutMs) throws Exception {
        String raw = evalRaw(win, "(function(){try{return JSON.stringify(" + expr + ")}catch(e){return JSON.stringify({ok:false,error:String(e)})}})()", timeoutMs);
        if (raw == null || raw.equals("null")) return new JSONObject().put("ok", false).put("error", "页面没有返回结果（可能正在加载）");
        // evaluateJavascript 返回的是 JSON 编码后的字符串
        String s = new org.json.JSONTokener(raw).nextValue().toString();
        return new JSONObject(s);
    }

    private static String evalRaw(FloatingApps win, String js, long timeoutMs) throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> ref = new AtomicReference<>();
        MAIN.post(() -> {
            try {
                win.web.evaluateJavascript(js, v -> { ref.set(v); latch.countDown(); });
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("页面脚本执行超时");
        return ref.get();
    }

    /** 支持 await 的任意脚本：结果放进 window.__pimetEval[id]，轮询取回 */
    private static String evalAsync(FloatingApps win, String script, long timeoutMs) throws Exception {
        String id = UUID.randomUUID().toString().replace("-", "");
        String body = script.contains("return") ? script : "return (" + script + ");";
        String js = "(function(){window.__pimetEval=window.__pimetEval||{};var id=" + JSONObject.quote(id) + ";"
                + "(async function(){" + body + "\n})().then(function(v){var s;try{s=typeof v==='string'?v:JSON.stringify(v);}catch(e){s=String(v);}"
                + "window.__pimetEval[id]={ok:true,v:s===undefined?'undefined':s};},function(e){window.__pimetEval[id]={ok:false,v:String(e&&e.stack||e)};});return 1;})()";
        evalRaw(win, js, 5000);
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            JSONObject r = evalJson(win, "(function(){var m=window.__pimetEval||{},r=m[" + JSONObject.quote(id) + "];if(!r)return {done:false};delete m["
                    + JSONObject.quote(id) + "];return {done:true,ok:r.ok,v:r.v};})()", 3000);
            if (r.optBoolean("done")) {
                String v = r.optString("v");
                if (v.length() > 40000) v = v.substring(0, 40000) + "…（已截断）";
                return r.optBoolean("ok") ? v : "脚本出错: " + v;
            }
            SystemClock.sleep(150);
        }
        return "脚本超时（" + timeoutMs + "ms）未返回";
    }

    // ------------------------------------------------------------------ 等待 / 截图 / 工具

    private static void waitLoaded(FloatingApps win, long timeoutMs) {
        long end = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < end) {
            if (!win.pageLoading && win.pageProgress >= 100) break;
            SystemClock.sleep(150);
        }
        SystemClock.sleep(250);
    }

    /** 操作后给页面一点反应时间；若触发了跳转则等加载完 */
    private static void settle(FloatingApps win, long timeoutMs) {
        SystemClock.sleep(500);
        if (win.pageLoading || win.pageProgress < 100) waitLoaded(win, timeoutMs);
        else SystemClock.sleep(200);
    }

    private static String capture(WebView web) {
        int w = web.getWidth(), h = web.getHeight();
        if (w <= 0 || h <= 0) return null;
        float scale = Math.min(1f, 900f / Math.max(w, h));
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), Bitmap.Config.RGB_565);
        Canvas c = new Canvas(bmp);
        c.scale(scale, scale);
        c.translate(-web.getScrollX(), -web.getScrollY());
        web.draw(c);
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 70, bo);
        bmp.recycle();
        return Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP);
    }

    private static int keyCode(String key) {
        switch (key.trim().toLowerCase()) {
            case "enter": case "return": return KeyEvent.KEYCODE_ENTER;
            case "tab": return KeyEvent.KEYCODE_TAB;
            case "escape": case "esc": return KeyEvent.KEYCODE_ESCAPE;
            case "backspace": return KeyEvent.KEYCODE_DEL;
            case "delete": return KeyEvent.KEYCODE_FORWARD_DEL;
            case "space": case " ": return KeyEvent.KEYCODE_SPACE;
            case "arrowup": case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "arrowdown": case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "arrowleft": case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "arrowright": case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "pageup": return KeyEvent.KEYCODE_PAGE_UP;
            case "pagedown": return KeyEvent.KEYCODE_PAGE_DOWN;
            case "home": return KeyEvent.KEYCODE_MOVE_HOME;
            case "end": return KeyEvent.KEYCODE_MOVE_END;
            default: return 0;
        }
    }

    private static String normalize(String s) {
        s = s == null ? "" : s.trim();
        if (s.isEmpty()) return "";
        if (s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*:.*")) return s;
        if (s.startsWith("localhost") || s.startsWith("127.0.0.1") || s.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+(:\\d+)?(/.*)?$")) return "http://" + s;
        if (s.startsWith("/")) return "file://" + s;
        if (!s.contains(" ") && s.contains(".")) return "https://" + s;
        return "https://www.bing.com/search?q=" + android.net.Uri.encode(s);
    }

    private interface MainCall<T> { T call() throws Exception; }

    private static <T> T onMain(MainCall<T> c, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> ref = new AtomicReference<>();
        AtomicReference<Throwable> err = new AtomicReference<>();
        MAIN.post(() -> {
            try { ref.set(c.call()); } catch (Throwable t) { err.set(t); }
            latch.countDown();
        });
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) throw new IllegalStateException("界面线程超时");
        if (err.get() != null) {
            Throwable t = err.get();
            if (t instanceof Exception) throw (Exception) t;
            throw new RuntimeException(t);
        }
        return ref.get();
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static JSONObject ok() throws Exception {
        return new JSONObject().put("success", true);
    }

    private static JSONObject err(String msg) {
        try {
            return new JSONObject().put("success", false).put("error", msg);
        } catch (Throwable t) {
            return new JSONObject();
        }
    }

    @SuppressWarnings("unused")
    private static boolean isShown(View v) {
        return v != null && v.isShown();
    }
}
