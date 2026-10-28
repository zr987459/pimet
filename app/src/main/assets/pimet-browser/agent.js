/* PiMet 浏览器自动化的页面端辅助脚本（由 BrowserAgent 注入；宿主先发探针 window.__pimetAgent.v === V，不一致才发整份源码）。
 * - V 是占位符，宿主注入前替换成源码哈希。
 * - 快照递归进入同源 iframe 和开放的 shadowRoot；ref 跨快照稳定：元素→号记在 WeakMap（window.__pimetIds），号永不重用，
 *   ref → 元素记在 window.__pimetRefs（Map），所以 iframe / shadow DOM 里的元素也能定位回去（data-pimet-ref 属性只是辅助）。
 * - 坐标统一换算到顶层页面的视口（累加 iframe 的位置和边框），供宿主做真实触摸。
 * - A.waitStable 用 MutationObserver + fetch/XHR 在途计数判断页面是否稳定（脚本加载时就安装）。
 * 注意：保持 ES5 风格 + Map/Set/WeakMap，兼容较老的 Android WebView；不用 NodeList.forEach / isConnected / Array.find 等（见 shim）。 */
(function () {
  var V = '__PIMET_AGENT_V__';
  if (window.__pimetAgent && window.__pimetAgent.v === V) return;
  var A = { v: V };

  var SEL = 'a[href],button,input:not([type=hidden]),textarea,select,summary,label[for],[role=button],[role=link],[role=checkbox],[role=radio],' +
    '[role=tab],[role=menuitem],[role=option],[role=switch],[role=combobox],[role=textbox],[role=searchbox],[contenteditable=""],[contenteditable=true],[onclick]';
  var STRONG_ROLES = { button: 1, link: 1, menuitem: 1, tab: 1, option: 1, 'switch': 1, checkbox: 1, radio: 1 };
  var POINTER_TAGS = { DIV: 1, SPAN: 1, LI: 1, IMG: 1, svg: 1, SVG: 1 };
  var POINTER_BUDGET_PER_ROOT = 800, MAX_ROOTS = 60;

  // ------------------------------------------------------------------ shim（老 WebView）
  var MapC = window.Map, WeakMapC = window.WeakMap || window.Map;
  function now() { return Date.now ? Date.now() : new Date().getTime(); }
  function each(list, fn) { if (list) for (var i = 0; i < list.length; i++) fn(list[i], i); }
  function arrFind(list, fn) { if (list) for (var i = 0; i < list.length; i++) if (fn(list[i], i)) return list[i]; return null; }
  function matches(el, sel) {
    var f = el && (el.matches || el.webkitMatchesSelector || el.msMatchesSelector);
    if (!f) return false;
    try { return f.call(el, sel); } catch (e) { return false; }
  }
  function parentOf(el) {
    if (el.parentElement) return el.parentElement;
    var p = el.parentNode;
    return p && p.host ? p.host : null; // 跨出 shadowRoot
  }
  function closest(el, sel) {
    var n = el;
    while (n && n.nodeType === 1) { if (matches(n, sel)) return n; n = n.parentElement; }
    return null;
  }
  function rootOf(el) {
    if (el.getRootNode) { try { return el.getRootNode(); } catch (e) {} }
    var n = el;
    while (n.parentNode) n = n.parentNode;
    return n;
  }
  /** 元素是否还在文档里（含 shadowRoot、同源 iframe） */
  function connected(el) {
    if (!el || el.nodeType !== 1) return false;
    if (typeof el.isConnected === 'boolean') return el.isConnected;
    var n = el, guard = 0;
    while (n && guard++ < 50) {
      var r = rootOf(n);
      if (r && r.nodeType === 9) return true; // Document
      n = r && r.host;
    }
    return false;
  }
  /** a 是否（跨 shadow 边界）包含 b */
  function containsDeep(a, b) {
    var n = b, guard = 0;
    while (n && guard++ < 200) { if (n === a) return true; n = parentOf(n); }
    return false;
  }
  function mkEvent(W, type, init, Ctor) {
    var C = Ctor && W[Ctor];
    if (C) { try { return new C(type, init); } catch (e) {} }
    try { return new W.Event(type, init); } catch (e2) {}
    var ev = W.document.createEvent('Event');
    ev.initEvent(type, !!init.bubbles, !!init.cancelable);
    for (var k in init) if (k !== 'bubbles' && k !== 'cancelable') { try { ev[k] = init[k]; } catch (x) {} }
    return ev;
  }

  // ------------------------------------------------------------------ 基础工具
  function itext(el) { if (!el) return ''; var t = el.innerText; return t == null ? (el.textContent || '') : t; }
  function clean(s) { return (s || '').replace(/\s+/g, ' ').replace(/^\s+|\s+$/g, ''); }
  function cut(s, n) { s = clean(s); return s.length > n ? s.slice(0, n) + '…' : s; }
  function winOf(el) { return (el && el.ownerDocument && el.ownerDocument.defaultView) || window; }
  var styleCache = null; // 快照期间缓存 getComputedStyle / rect
  var rectCache = null;
  function styleOf(el) {
    if (styleCache) { var c = styleCache.get(el); if (c !== undefined) return c; }
    var s = null;
    try { s = winOf(el).getComputedStyle(el); } catch (e) { s = null; }
    if (styleCache) styleCache.set(el, s);
    return s;
  }
  function lrect(el) { // 元素在自己文档里的 rect
    if (rectCache) { var c = rectCache.get(el); if (c) return c; }
    var r;
    try { r = el.getBoundingClientRect(); } catch (e) { r = { left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0 }; }
    if (rectCache) rectCache.set(el, r);
    return r;
  }
  function beginCache() { styleCache = new MapC(); rectCache = new MapC(); }
  function endCache() { styleCache = null; rectCache = null; }
  function isField(el) {
    var t = el.tagName;
    return t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT' || el.isContentEditable;
  }
  function inFrame(el) { return el.ownerDocument !== document; }

  /** 某个文档（可能是同源 iframe）的视口原点在顶层视口中的位置 */
  function frameOffset(doc) {
    var x = 0, y = 0, w = doc.defaultView, guard = 0;
    while (w && w !== window && guard++ < 10) {
      var fe = null;
      try { fe = w.frameElement; } catch (e) { break; }
      if (!fe) break;
      var r = fe.getBoundingClientRect(), cs = styleOf(fe);
      x += r.left + fe.clientLeft + (cs ? parseFloat(cs.paddingLeft) || 0 : 0);
      y += r.top + fe.clientTop + (cs ? parseFloat(cs.paddingTop) || 0 : 0);
      w = fe.ownerDocument.defaultView;
    }
    return { x: x, y: y };
  }
  function toTop(r, doc) {
    var o = doc === document ? { x: 0, y: 0 } : frameOffset(doc);
    return { left: r.left + o.x, top: r.top + o.y, width: r.width, height: r.height, right: r.right + o.x, bottom: r.bottom + o.y, ox: o.x, oy: o.y };
  }
  function rectOf(el) { return toTop(lrect(el), el.ownerDocument); }
  function frameChain(el) {
    var chain = [], w = winOf(el), guard = 0;
    while (w !== window && guard++ < 10) {
      var fe = null;
      try { fe = w.frameElement; } catch (e) { break; }
      if (!fe) break;
      chain.unshift(fe);
      w = fe.ownerDocument.defaultView;
    }
    return chain;
  }
  function reveal(el) {
    var chain = frameChain(el);
    for (var i = 0; i < chain.length; i++) try { chain[i].scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); } catch (e) {}
    try { el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' }); } catch (e) { try { el.scrollIntoView(); } catch (x) {} }
  }
  /** 遮挡物的简短描述：标签.类名「文字」 */
  function describe(el) {
    if (!el || el.nodeType !== 1) return '';
    var s = el.tagName.toLowerCase();
    if (el.id) s += '#' + cut(el.id, 20);
    var cls = typeof el.className === 'string' ? clean(el.className).split(' ')[0] : '';
    if (cls) s += '.' + cut(cls, 24);
    var role = el.getAttribute('role');
    if (role) s += '[' + role + ']';
    var t = el.getAttribute('aria-label') || textOf(el, 40);
    if (t) s += '「' + cut(t, 30) + '」';
    return s;
  }
  /** 先用 textContent 截断，短文本才用 innerText（避免对大容器强制布局 + 生成巨型字符串） */
  function textOf(el, n) {
    var raw = el.textContent || '';
    if (raw.length <= 200) { var it = el.innerText; return cut(it == null ? raw : it, n); } // innerText 不存在时退回 textContent
    return cut(raw.slice(0, 300), n);
  }

  // ------------------------------------------------------------------ 可见性 / 遮挡
  function visible(el) {
    var r = lrect(el);
    if (r.width < 2 || r.height < 2) return false;
    var W = winOf(el), iw = W.innerWidth || 0;
    if (iw && (r.right < -iw || r.left > iw * 2)) return false; // 移到屏幕外（left:-9999 之类）
    if (el.checkVisibility) {
      try { if (!el.checkVisibility({ opacityProperty: true, visibilityProperty: true, checkOpacity: true, checkVisibilityCSS: true })) return false; } catch (e) {}
    }
    var s = styleOf(el);
    if (s && (s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity || '1') <= 0.05)) return false;
    // 祖先：opacity 不继承、aria-hidden / inert、被 overflow 裁剪
    var p = parentOf(el), k = 0;
    while (p && p.nodeType === 1 && k++ < 15) {
      if (p.getAttribute('aria-hidden') === 'true' || p.hasAttribute('inert')) return false;
      var ps = styleOf(p);
      if (ps) {
        if (!el.checkVisibility && parseFloat(ps.opacity || '1') <= 0.05) return false;
        if (/(hidden|clip|auto|scroll|overlay)/.test((ps.overflowX || '') + ' ' + (ps.overflowY || '')) && p.tagName !== 'BODY' && p.tagName !== 'HTML') {
          var pr = lrect(p);
          if (pr.width > 0 && pr.height > 0 && (r.right <= pr.left || r.left >= pr.right || r.bottom <= pr.top || r.top >= pr.bottom)) {
            // 完全在裁剪容器外：可滚动容器里的就算「需要滚动」，仍算可见；hidden/clip 的才算不可见
            var ox = ps.overflowX, oy = ps.overflowY;
            if (!/(auto|scroll)/.test(ox) && !/(auto|scroll)/.test(oy)) return false;
          }
        }
      }
      p = parentOf(p);
    }
    return true;
  }
  function hitOk(el, hit) { return !!hit && (hit === el || containsDeep(el, hit) || containsDeep(hit, el)); }
  /** 局部坐标 (lx,ly)（元素自己文档的视口坐标）处命中的元素；顶层还要确认 iframe 链没被盖住 */
  function hitAt(el, lx, ly) {
    var rn = rootOf(el), hit = null;
    try { hit = (rn && rn.elementFromPoint ? rn : el.ownerDocument).elementFromPoint(lx, ly); } catch (e) {}
    if (hit && inFrame(el)) {
      var chain = frameChain(el), o = frameOffset(el.ownerDocument), top = null;
      try { top = document.elementFromPoint(lx + o.x, ly + o.y); } catch (e2) {}
      if (top && chain.length && top !== chain[0] && !containsDeep(chain[0], top)) return { hit: top, ok: false };
    }
    return { hit: hit, ok: hit ? hitOk(el, hit) : true }; // 点在视口外（hit=null）时不判遮挡
  }
  /** 命中的是 el 本身，或 el 内部、且不属于 el 内另一个可交互元素 */
  function hitIsOwn(el, hit) {
    if (!hit) return false;
    if (hit === el) return true;
    if (!containsDeep(el, hit)) return containsDeep(hit, el); // 命中祖先（pointer-events:none）也算
    var n = hit, k = 0;
    while (n && n !== el && k++ < 30) { if (matches(n, SEL)) return false; n = parentOf(n); }
    return true;
  }
  /** 当前弹窗：dialog[open] / aria-modal / role=dialog，或视口中心命中的大面积 fixed 层 */
  function findModal() {
    var H = innerHeight, W = innerWidth, cand = null;
    try {
      var ds = document.querySelectorAll('dialog[open],[aria-modal=true],[role=dialog],[role=alertdialog]');
      for (var i = 0; i < ds.length; i++) if (visible(ds[i])) { var r = lrect(ds[i]); if (r.width * r.height > 0.05 * W * H) cand = ds[i]; }
    } catch (e) {}
    if (cand) return cand;
    var pts = [[0.5, 0.5], [0.5, 0.3], [0.5, 0.7]];
    for (var j = 0; j < pts.length; j++) {
      var h = null;
      try { h = document.elementFromPoint(W * pts[j][0], H * pts[j][1]); } catch (e2) {}
      var n = h, k = 0, fixed = null;
      while (n && n.nodeType === 1 && n !== document.body && k++ < 25) {
        var s = styleOf(n);
        if (s && s.position === 'fixed') fixed = n;
        n = parentOf(n);
      }
      if (fixed) {
        var fr = lrect(fixed), fs = styleOf(fixed), z = fs ? parseInt(fs.zIndex, 10) : 0;
        if (fr.width * fr.height >= 0.3 * W * H && z >= 1) { fixed.__pimetHeur = true; return fixed; }
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ 遍历（TreeWalker，一次收集候选 / shadowRoot / iframe）
  var observed = null; // 已挂 MutationObserver 的根（WeakMap 当 Set 用）
  function walkRoots(fn, cross) {
    var queue = [{ root: document, depth: 0 }], n = 0;
    while (queue.length && n++ < MAX_ROOTS) {
      var q = queue.shift(), root = q.root;
      observeRoot(root);
      var doc = root.nodeType === 9 ? root : root.ownerDocument, tw;
      try { tw = doc.createTreeWalker(root, 1, null, false); } catch (e) { continue; }
      fn(root, null, true);
      var el = tw.nextNode();
      while (el) {
        if (el.shadowRoot && q.depth < 8) queue.push({ root: el.shadowRoot, depth: q.depth + 1 });
        var tag = el.tagName;
        if ((tag === 'IFRAME' || tag === 'FRAME') && q.depth < 8) {
          var d = null;
          try { d = el.contentDocument; } catch (e2) { d = null; }
          if (d && d.documentElement) queue.push({ root: d, depth: q.depth + 1 });
          else if (cross && visible(el)) cross.push(el.src || el.getAttribute('src') || '(无地址)');
        }
        fn(root, el, false);
        el = tw.nextNode();
      }
    }
  }

  // ------------------------------------------------------------------ 页面稳定（waitStable）
  var ST = window.__pimetState || (window.__pimetState = { lastMut: now(), reqs: {}, rid: 0, hooked: false });
  function onMutations(recs) {
    for (var i = 0; i < recs.length; i++) {
      var r = recs[i];
      if (r.type === 'childList') {
        var own = true;
        each(r.addedNodes, function (n) { if (!n.__pimetOwn) own = false; });
        each(r.removedNodes, function (n) { if (!n.__pimetOwn) own = false; });
        if (own && (r.addedNodes.length || r.removedNodes.length)) continue;
      }
      ST.lastMut = now();
      return;
    }
  }
  function observeRoot(root) {
    var MO = window.MutationObserver || window.WebKitMutationObserver;
    if (!MO || !root) return;
    if (!observed) observed = window.__pimetObserved || (window.__pimetObserved = new WeakMapC());
    if (observed.get(root)) return;
    observed.set(root, 1);
    try { new MO(onMutations).observe(root, { childList: true, subtree: true, characterData: true }); } catch (e) {}
  }
  function hookNet() {
    if (ST.hooked) return;
    ST.hooked = true;
    function start() { var id = ++ST.rid; ST.reqs[id] = now(); return id; }
    function done(id) { delete ST.reqs[id]; ST.lastMut = now(); }
    try {
      var X = window.XMLHttpRequest && window.XMLHttpRequest.prototype, send0 = X && X.send;
      if (send0) X.send = function () {
        var id = start(), xhr = this, fin = false;
        function end() { if (!fin) { fin = true; done(id); } }
        try { xhr.addEventListener('loadend', end); xhr.addEventListener('readystatechange', function () { if (xhr.readyState === 4) end(); }); } catch (e) {}
        try { return send0.apply(this, arguments); } catch (e2) { end(); throw e2; }
      };
    } catch (e) {}
    try {
      var f0 = window.fetch;
      if (f0) window.fetch = function () {
        var id = start(), p;
        try { p = f0.apply(this, arguments); } catch (e) { done(id); throw e; }
        if (p && p.then) p.then(function () { done(id); }, function () { done(id); });
        else done(id);
        return p;
      };
    } catch (e) {}
  }
  observeRoot(document);
  hookNet();

  /** 同步返回 {stable, pending, idleMs}；宿主轮询调用 */
  A.waitStable = function (quietMs, maxMs) {
    quietMs = quietMs > 0 ? quietMs : 300;
    maxMs = maxMs > 0 ? maxMs : 3000;
    var t = now(), pending = 0;
    for (var id in ST.reqs) {
      if (t - ST.reqs[id] < Math.max(maxMs, 1000)) pending++; // 更久的视为长轮询，不计
      else if (t - ST.reqs[id] > 60000) delete ST.reqs[id];
    }
    var idle = Math.max(0, t - ST.lastMut);
    var loading = document.readyState === 'loading';
    return { stable: !loading && pending === 0 && idle >= quietMs, pending: pending, idleMs: idle };
  };

  // ------------------------------------------------------------------ ref（跨快照稳定）
  var IDS = window.__pimetIds || (window.__pimetIds = new WeakMapC());
  if (typeof window.__pimetSeq !== 'number' || !(window.__pimetRefs instanceof MapC)) { window.__pimetSeq = 0; window.__pimetRefs = new MapC(); } // 旧版（按序号重排）的 ref 一律作废
  var REFS = window.__pimetRefs;
  function refFor(el) {
    var n = IDS.get(el);
    if (!n) { n = ++window.__pimetSeq; IDS.set(el, n); }
    var ref = 'e' + n;
    REFS.set(ref, el);
    return ref;
  }
  function staleErr(ref) { return { ok: false, error: '页面已变化，ref ' + ref + ' 已失效，请重新 snapshot' }; }
  /** 内部：{el} 或 {ok:false,error} */
  function resolve(ref) {
    ref = String(ref || '');
    var m = /^e(\d+)$/.exec(ref);
    if (!m) return { ok: false, error: '无效的 ref ' + ref + '（应形如 e12），请先 snapshot' };
    var n = +m[1];
    var el = REFS.get(ref);
    if (el) {
      if (connected(el) && IDS.get(el) === n) return { el: el };
      return staleErr(ref);
    }
    var found = null;
    try { found = document.querySelector('[data-pimet-ref="' + ref + '"]'); } catch (e) {}
    if (found) {
      if (IDS.get(found) === n) { REFS.set(ref, found); return { el: found }; }
      return staleErr(ref); // 框架克隆了带属性的节点：不是同一个元素
    }
    if (n <= window.__pimetSeq) return staleErr(ref);
    return { ok: false, error: '找不到元素 ' + ref + '，请先 snapshot' };
  }
  /** 成功返回元素；失效返回 {ok:false,error} */
  A.get = function (ref) { var r = resolve(ref); return r.el || r; };
  function pruneRefs() {
    var dead = [];
    REFS.forEach(function (el, ref) { if (!connected(el)) dead.push(ref); });
    for (var i = 0; i < dead.length; i++) REFS['delete'](dead[i]);
  }

  // ------------------------------------------------------------------ 快照
  function nameOf(el, tag, type) {
    var name = el.getAttribute('aria-label') || el.getAttribute('title') || '';
    if (!name) {
      var lb = el.getAttribute('aria-labelledby');
      if (lb) { try { var le = el.ownerDocument.getElementById(lb.split(' ')[0]); if (le) name = textOf(le, 70); } catch (e) {} }
    }
    if (!name && tag !== 'input' && tag !== 'textarea' && tag !== 'select') name = textOf(el, 70);
    if (!name) { var img = el.querySelector && el.querySelector('img[alt]'); if (img) name = img.getAttribute('alt'); }
    if (!name && tag === 'img') name = el.getAttribute('alt') || '';
    if (!name && (tag === 'input' || tag === 'textarea')) name = el.getAttribute('placeholder') || el.getAttribute('name') || '';
    if (!name && (type === 'submit' || type === 'button')) name = el.value || '';
    return cut(name, 70);
  }
  function shortHref(el) {
    var h = el.getAttribute('href') || '';
    if (!h || h.charAt(0) === '#' || /^javascript:/i.test(h)) return '';
    var u = el.href || h, loc = location;
    try {
      if (el.protocol && el.host) {
        if (el.host === loc.host) u = (el.pathname || '/') + (el.search || '');
        else u = el.host + (el.pathname && el.pathname !== '/' ? el.pathname : '');
      }
    } catch (e) {}
    return cut(u, 40);
  }

  A.snapshot = function (maxChars, mode) {
    maxChars = maxChars > 0 ? maxChars : 6000;
    beginCache();
    try { return doSnapshot(maxChars, mode); } finally { endCache(); }
  };

  function doSnapshot(maxChars, mode) {
    pruneRefs();
    var H = innerHeight, W = innerWidth, vpArea = H * W;
    var cand = [], seen = new MapC(), cross = [], budget = 0;
    walkRoots(function (root, el, isStart) {
      if (isStart) { budget = POINTER_BUDGET_PER_ROOT; return; }
      if (matches(el, SEL)) { if (!seen.get(el)) { seen.set(el, 1); cand.push(el); } return; }
      // 很多站点用 div 当按钮：收集 cursor:pointer 的元素（只取最外层），每个根独立预算，先用 rect 过滤到视口 ±1 屏
      if (budget > 0 && POINTER_TAGS[el.tagName]) {
        var r = lrect(el);
        if (r.width < 2 || r.height < 2 || r.bottom < -H || r.top > 2 * H || r.width * r.height > vpArea * 0.6) return;
        budget--;
        var s = styleOf(el);
        if (!s || s.cursor !== 'pointer') return;
        var p = parentOf(el), ps = p ? styleOf(p) : null;
        if (ps && ps.cursor === 'pointer') return;
        if (closest(el, SEL)) return;
        seen.set(el, 1); cand.push(el);
      }
    }, cross);

    // 只保留最外层的可点击元素：祖先是「强」可点击元素或面积不大的可点击容器时，丢弃内层；输入框总是保留
    function dominated(el) {
      if (isField(el)) return false;
      var p = parentOf(el), k = 0;
      while (p && k++ < 12) {
        if (seen.get(p)) {
          var tag = p.tagName, role = p.getAttribute('role');
          if (tag === 'A' || tag === 'BUTTON' || tag === 'SUMMARY' || (role && STRONG_ROLES[role])) return true;
          var r = lrect(p);
          if (r.width * r.height < vpArea * 0.25) return true;
        }
        p = parentOf(p);
      }
      return false;
    }

    var modal = findModal();
    var items = [], extra = { below: 0, above: 0, inView: 0 };
    function whereOf(r) { return r.bottom < 0 ? 2 : r.top > H ? 1 : 0; }
    function countExtra(w) { if (w === 1) extra.below++; else if (w === 2) extra.above++; else extra.inView++; }
    for (var i = 0; i < cand.length; i++) {
      var el = cand[i];
      if (!visible(el)) continue;
      if (dominated(el)) continue;
      var r = rectOf(el);
      if (items.length >= 400) { countExtra(whereOf(r)); continue; }
      var tag = el.tagName.toLowerCase(), type = (el.getAttribute('type') || '').toLowerCase();
      var role = el.getAttribute('role') || (tag === 'a' ? 'link' : tag === 'button' ? 'button' : tag === 'select' ? 'select' : tag === 'textarea' ? 'textbox'
        : tag === 'input' ? (type === 'checkbox' || type === 'radio' ? type : (type === 'submit' || type === 'button' ? 'button' : 'textbox')) : el.isContentEditable ? 'textbox' : 'clickable');
      var name = nameOf(el, tag, type);
      if (!name && role === 'clickable') continue;
      if (role === 'clickable' && !el.getAttribute('role') && r.width * r.height > vpArea * 0.6) continue; // 整页大小的点击容器（事件委托），不是按钮
      var where = whereOf(r), inModal = modal ? containsDeep(modal, el) : false;
      items.push({ el: el, tag: tag, type: type, role: role, name: name, r: r, where: where, modal: inModal, idx: items.length });
    }
    // 启发式找到的 fixed 层几乎包含所有元素时，多半是 fixed 布局的应用外壳，不是弹窗
    if (modal && modal.__pimetHeur && items.length > 5) {
      var inM = 0;
      for (var mi = 0; mi < items.length; mi++) if (items[mi].modal) inM++;
      if (inM >= items.length * 0.9) { modal = null; for (mi = 0; mi < items.length; mi++) items[mi].modal = false; }
    }
    // 弹窗内的最前；然后视口内（从上到下、从左到右），其次下方，最后上方
    items.sort(function (a, b) {
      if (a.modal !== b.modal) return a.modal ? -1 : 1;
      if (a.where !== b.where) return a.where - b.where;
      if (a.where === 0) {
        var dy = Math.round(a.r.top / 8) - Math.round(b.r.top / 8);
        return dy || (a.r.left - b.r.left) || (a.idx - b.idx);
      }
      return (a.r.top - b.r.top) || (a.idx - b.idx);
    });
    if (items.length > 220) { for (var q = 220; q < items.length; q++) countExtra(items[q].where); items.length = 220; }

    var se = document.scrollingElement || document.documentElement;
    var head = '网址: ' + location.href + '\n标题: ' + document.title + '\n滚动: ' + Math.round(se.scrollTop) + '/' + Math.max(0, Math.round(se.scrollHeight - H)) + ' (视口 ' + W + 'x' + H + ')';
    if (modal) head += '\n⚠ 当前有弹窗/遮罩：' + describe(modal) + '（弹窗内元素排在最前；背景元素可能点不到，先处理弹窗）';
    for (var c = 0; c < cross.length && c < 6; c++) head += '\n跨源 iframe：' + cut(cross[c], 120) + '（内容不在快照里，可 open 该网址单独查看）';
    var body = '\n\n可交互元素（用 ref 操作，视口内的在前）:\n';

    var interactive = mode === 'interactive';
    var lineBudget = interactive ? maxChars - head.length - body.length - 80 : Math.max(1200, Math.floor(maxChars * 0.65) - head.length - body.length);
    var lines = [], used = 0, listed = 0;
    for (var k = 0; k < items.length; k++) {
      var it = items[k], el2 = it.el, ref = refFor(el2);
      try { el2.setAttribute('data-pimet-ref', ref); } catch (e) {}
      var ex = '', tg = it.tag, ty = it.type;
      if (it.role === 'textbox' || tg === 'input') {
        var v = el2.isContentEditable ? textOf(el2, 60) : el2.value;
        if (v && ty !== 'password') ex += ' 值="' + cut(v, 60) + '"';
        else if (v && ty === 'password') ex += ' 已填' + v.length + '位';
        var ph = el2.getAttribute('placeholder');
        if (ph && it.name !== cut(ph, 70)) ex += ' 提示="' + cut(ph, 40) + '"';
        if (ty && ty !== 'text' && ty !== 'checkbox' && ty !== 'radio') ex += ' type=' + ty;
      }
      if (ty === 'checkbox' || ty === 'radio' || el2.getAttribute('aria-checked')) ex += (el2.checked || el2.getAttribute('aria-checked') === 'true') ? ' [已选]' : ' [未选]';
      if (el2.getAttribute('aria-expanded')) ex += el2.getAttribute('aria-expanded') === 'true' ? ' [已展开]' : ' [已收起]';
      if (tg === 'select') { var o = el2.options[el2.selectedIndex]; ex += ' 当前="' + cut(o ? o.text : '', 30) + '" 选项数=' + el2.options.length; }
      if (tg === 'a') { var hr = shortHref(el2); if (hr) ex += ' →' + hr; }
      if (el2.disabled || el2.getAttribute('aria-disabled') === 'true') ex += ' [禁用]';
      if (inFrame(el2)) ex += ' [iframe]';
      if (it.where === 0) {
        var lr = lrect(el2), h = hitAt(el2, lr.left + lr.width / 2, lr.top + lr.height / 2);
        if (!h.ok) ex += ' [被遮挡]';
      } else if (it.where === 2) ex += ' (上方)'; else ex += ' (下方)';
      var line = '[' + ref + '] ' + it.role + (it.name ? ' "' + it.name + '"' : '') + ex;
      if (used + line.length + 1 > lineBudget && listed > 0) { for (var z = k; z < items.length; z++) countExtra(items[z].where); break; }
      lines.push(line); used += line.length + 1; listed++;
    }
    body += lines.join('\n') || '(无)';
    var unl = extra.below + extra.above + extra.inView;
    var tail = unl ? '\n\n（另有 ' + unl + ' 个元素未列出' + (extra.below ? '：下方 ' + extra.below : '') + (extra.above ? (extra.below ? '，' : '：') + '上方 ' + extra.above : '') +
      (extra.inView ? ((extra.below || extra.above) ? '，' : '：') + '视口内 ' + extra.inView : '') + '；可滚动后再 snapshot，或用 max_chars 调大）' : '';
    if (interactive) {
      var t0 = head + body;
      if (t0.length + tail.length > maxChars) t0 = t0.slice(0, Math.max(0, maxChars - tail.length)) + '…';
      return { url: location.href, title: document.title, count: listed, text: t0 + tail };
    }

    // 大纲（h1-h3）
    var outline = [];
    try {
      var hs = document.querySelectorAll('h1,h2,h3');
      for (var hi = 0; hi < hs.length && outline.length < 15; hi++) {
        if (!visible(hs[hi])) continue;
        var ht = textOf(hs[hi], 60);
        if (ht) outline.push((hs[hi].tagName === 'H1' ? '# ' : hs[hi].tagName === 'H2' ? '## ' : '### ') + ht);
      }
    } catch (e) {}
    var outlineText = outline.length ? '\n\n大纲:\n' + outline.join('\n') : '';

    // 页面文字：优先正文区域；超长时从视口附近开始取
    var main = null, label = '', container = document.body;
    try { main = document.querySelector('main,[role=main],article'); } catch (e) {}
    var text = '';
    if (main && clean(itext(main)).length >= 200) { text = clean(itext(main)); label = '（正文区域）'; container = main; }
    else text = clean(itext(document.body));
    var pre = '\n\n页面文字' + label + ':\n';
    var room = Math.max(800, maxChars - head.length - body.length - outlineText.length - pre.length - tail.length - 20);
    if (text.length > room) {
      var start = 0;
      if (se.scrollTop > H * 0.5) {
        start = viewportTextIndex(text, container);
        if (start < 0) start = Math.floor(text.length * se.scrollTop / Math.max(1, se.scrollHeight));
        start = Math.max(0, Math.min(start - Math.floor(room * 0.15), text.length - room));
      }
      var piece = text.slice(start, start + room);
      text = (start > 0 ? '…（前文省略 ' + start + ' 字）' : '') + piece + (start + room < text.length ? '…（后面还有 ' + (text.length - start - room) + ' 字，可滚动或用 eval 读取）' : '');
      pre = '\n\n页面文字' + label + (start > 0 ? '，从当前视口附近开始' : '') + ':\n';
    }
    return { url: location.href, title: document.title, count: listed, text: head + body + outlineText + pre + text + tail };
  }
  /** 视口顶部附近的文字在整段文字里的位置（找不到返回 -1） */
  function viewportTextIndex(text, container) {
    var W = innerWidth, H = innerHeight, ys = [0.12, 0.25, 0.4], xs = [0.5, 0.3];
    for (var i = 0; i < ys.length; i++) for (var j = 0; j < xs.length; j++) {
      var h = null;
      try { h = document.elementFromPoint(W * xs[j], H * ys[i]); } catch (e) {}
      var n = h, k = 0;
      while (n && n.nodeType === 1 && k++ < 6) {
        if (container && !containsDeep(container, n)) break;
        var raw = n.textContent || '';
        if (raw.length >= 12 && raw.length <= 3000) {
          var snip = clean(n.innerText || raw).slice(0, 30);
          if (snip.length >= 8) { var idx = text.indexOf(snip); if (idx >= 0) return idx; }
        }
        n = n.parentElement;
      }
    }
    return -1;
  }

  // ------------------------------------------------------------------ 定位 / 点击
  /** 按文字找元素（含同源 iframe 和 shadowRoot）：先用 textContent 粗筛；优先视口内、可交互、文字最短 */
  function findByText(text) {
    var best = null, H = innerHeight;
    var TAGS = { A: 1, BUTTON: 1, INPUT: 1, LABEL: 1, SUMMARY: 1, LI: 1, SPAN: 1, DIV: 1, TD: 1, P: 1 };
    walkRoots(function (root, e, isStart) {
      if (isStart || !e) return;
      var inter = matches(e, SEL);
      if (!inter && !TAGS[e.tagName]) return;
      var tc = e.textContent || '', al = e.getAttribute('aria-label') || '', val = e.tagName === 'INPUT' ? (e.value || '') : '';
      if (tc.indexOf(text) < 0 && al.indexOf(text) < 0 && val.indexOf(text) < 0) return;
      if (tc.length > 2000 && !inter) return;
      var r = lrect(e);
      if (r.width < 2 || r.height < 2) return;
      var t = clean(val || (tc.length <= 300 ? (e.innerText || tc) : tc.slice(0, 300)) || al);
      if (t.indexOf(text) < 0 && al.indexOf(text) < 0) return;
      if (!visible(e)) return;
      var tr = rectOf(e), inView = tr.bottom > 0 && tr.top < H;
      var score = t.length + (inView ? 0 : 5000) - (inter ? 20 : 0);
      if (!best || score < best.score) best = { e: e, score: score };
    }, null);
    if (!best) return null;
    var c = closest(best.e, SEL);
    return c && c !== document.body ? (rectOf(c).width * rectOf(c).height < innerWidth * innerHeight * 0.5 ? c : best.e) : best.e;
  }

  var SAMPLES = [[0.5, 0.5], [0.3, 0.5], [0.7, 0.5], [0.5, 0.3], [0.5, 0.7], [0.25, 0.25], [0.75, 0.25], [0.25, 0.75], [0.75, 0.75]];
  /** 量元素、找一个能点到它自己的点。fx/fy 有值时优先用它（relocate） */
  function measure(el, fx, fy) {
    var lr = el.getBoundingClientRect();
    if (lr.width < 1 || lr.height < 1) return { ok: false, error: '元素当前不可见（可能菜单已收起或已被移除），请重新 snapshot' };
    var o = inFrame(el) ? frameOffset(el.ownerDocument) : { x: 0, y: 0 };
    var pts = fx != null ? [[fx, fy]].concat(SAMPLES) : SAMPLES, pick = null, firstHit = null;
    for (var i = 0; i < pts.length; i++) {
      var lx = lr.left + lr.width * pts[i][0], ly = lr.top + lr.height * pts[i][1];
      var h = hitAt(el, lx, ly);
      if (i === 0) firstHit = h;
      if (h.ok && (h.hit === null || hitIsOwn(el, h.hit))) { pick = { fx: pts[i][0], fy: pts[i][1], lx: lx, ly: ly }; break; }
    }
    var covered = !pick, coveredBy = covered && firstHit && firstHit.hit ? describe(firstHit.hit) : '';
    if (!pick) pick = { fx: pts[0][0], fy: pts[0][1], lx: lr.left + lr.width * pts[0][0], ly: lr.top + lr.height * pts[0][1] };
    var x = pick.lx + o.x, y = pick.ly + o.y;
    var vv = window.visualViewport, vx = vv ? x - vv.offsetLeft : x, vy = vv ? y - vv.offsetTop : y, vw = vv ? vv.width : innerWidth;
    window.__pimetTarget = el;
    window.__pimetPoint = { fx: pick.fx, fy: pick.fy };
    return {
      ok: true, x: vx, y: vy, vw: vw, covered: covered, coveredBy: coveredBy, frame: inFrame(el), tag: el.tagName.toLowerCase(),
      label: cut(itext(el) || el.value || el.getAttribute('aria-label') || '', 40),
      _r: { left: lr.left + o.x, top: lr.top + o.y, width: lr.width, height: lr.height }
    };
  }
  function flash(r) {
    try {
      var o = document.createElement('div');
      o.__pimetOwn = true;
      o.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #ff4d4f;border-radius:4px;box-shadow:0 0 0 3px rgba(255,77,79,.3);' +
        'left:' + (r.left - 2) + 'px;top:' + (r.top - 2) + 'px;width:' + r.width + 'px;height:' + r.height + 'px';
      document.documentElement.appendChild(o);
      setTimeout(function () { if (o.parentNode) o.parentNode.removeChild(o); }, 700);
    } catch (x) {}
  }
  function strip(m) { if (m && m._r) delete m._r; return m; }

  A.locate = function (ref, text) {
    var el = null;
    if (ref) {
      var rs = resolve(ref);
      if (!rs.el) { if (!text) return rs; } else el = rs.el;
    }
    if (!el && text) el = findByText(text);
    if (!el) return { ok: false, error: ref ? '找不到元素 ' + ref + '，页面可能已变化，请重新 snapshot' : '找不到包含「' + text + '」的元素' };
    reveal(el);
    var m = measure(el);
    if (!m.ok) return { ok: false, error: (ref ? ref + ' ' : '') + m.error };
    flash(m._r);
    return strip(m);
  };

  /** 触摸前重新量上一次 locate 的元素（页面可能因懒加载 / 动画移动了） */
  A.relocate = function () {
    var el = window.__pimetTarget;
    if (!el || !connected(el)) return { ok: false, error: '目标元素已不存在，页面已变化，请重新 snapshot' };
    var p = window.__pimetPoint || { fx: 0.5, fy: 0.5 };
    var lr = el.getBoundingClientRect(), o = inFrame(el) ? frameOffset(el.ownerDocument) : { x: 0, y: 0 };
    var cy = lr.top + lr.height * p.fy + o.y, cx = lr.left + lr.width * p.fx + o.x;
    if (cy < 0 || cy > innerHeight || cx < 0 || cx > innerWidth) reveal(el);
    return strip(measure(el, p.fx, p.fy));
  };

  /** 真实触摸失败（后台标签、窗口最小化等）时的 JS 点击 */
  A.jsClick = function () {
    var el = window.__pimetTarget;
    if (!el || !connected(el)) return { ok: false, error: '目标元素已不存在' };
    var W = winOf(el), r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
    var types = ['pointerdown', 'mousedown', 'pointerup', 'mouseup'];
    for (var i = 0; i < types.length; i++) {
      var t = types[i];
      try {
        var C = t.indexOf('pointer') === 0 && W.PointerEvent ? W.PointerEvent : W.MouseEvent;
        el.dispatchEvent(new C(t, { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, view: W }));
      } catch (e) {}
    }
    try { el.focus(); } catch (e) {}
    try { el.click(); } catch (e) { return { ok: false, error: String(e) }; }
    return { ok: true };
  };

  // ------------------------------------------------------------------ 输入
  function keyInit(key, code) { return { key: key, code: code, keyCode: 0, which: 0, bubbles: true, cancelable: true, composed: true }; }
  function charKey(text) { return text && text.length === 1 ? text : 'Unidentified'; }
  function readBack(el) {
    var v = el.isContentEditable ? (el.innerText || el.textContent || '') : (el.value || '');
    var pw = el.tagName === 'INPUT' && (el.getAttribute('type') || '').toLowerCase() === 'password';
    return pw ? { len: v.length } : { value: v.length > 200 ? v.slice(0, 200) + '…' : v };
  }

  A.type = function (ref, text, clear) {
    text = text == null ? '' : String(text);
    var el;
    if (ref) { var rs = resolve(ref); if (!rs.el) return rs; el = rs.el; } else el = deepActive();
    if (!el || el === document.body) return { ok: false, error: ref ? '找不到元素 ' + ref + '，请重新 snapshot' : '没有获得焦点的输入框，请指定 ref' };
    if (!isField(el)) { var inner = el.querySelector && el.querySelector('input:not([type=hidden]),textarea,[contenteditable=""],[contenteditable=true]'); if (inner) el = inner; }
    var tp = (el.getAttribute('type') || '').toLowerCase();
    if (el.tagName === 'INPUT' && tp === 'file') return { ok: false, error: (ref || '当前元素') + ' 是文件上传框，不能直接输入文字' };
    if (el.tagName === 'SELECT') return { ok: false, error: (ref || '当前元素') + ' 是下拉框，请用 select' };
    reveal(el);
    try { el.focus(); } catch (e) {}
    var W = winOf(el), D = el.ownerDocument, kd = keyInit(charKey(text), '');
    try { el.dispatchEvent(mkEvent(W, 'keydown', kd, 'KeyboardEvent')); } catch (e) {}
    if (el.isContentEditable) {
      // execCommand 本身会触发 beforeinput / input
      try {
        var sel = W.getSelection(), range = D.createRange();
        range.selectNodeContents(el);
        if (!clear) range.collapse(false);
        sel.removeAllRanges(); sel.addRange(range);
        if (clear) D.execCommand('delete', false);
      } catch (e) {}
      var okIns = false;
      try { okIns = D.execCommand('insertText', false, text); } catch (e) {}
      if (!okIns) {
        if (clear) el.textContent = '';
        el.appendChild(D.createTextNode(text));
        el.dispatchEvent(mkEvent(W, 'input', { bubbles: true, inputType: 'insertText', data: text }, 'InputEvent'));
      }
    } else if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
      var bi = mkEvent(W, 'beforeinput', { bubbles: true, cancelable: true, composed: true, inputType: 'insertText', data: text }, 'InputEvent');
      try { el.dispatchEvent(bi); } catch (e) {}
      {
        var nv = (clear ? '' : el.value) + text;
        var ml = el.maxLength;
        if (ml > 0 && nv.length > ml) nv = nv.slice(0, ml);
        var proto = el.tagName === 'TEXTAREA' ? W.HTMLTextAreaElement.prototype : W.HTMLInputElement.prototype;
        var desc = Object.getOwnPropertyDescriptor(proto, 'value');
        try { if (desc && desc.set) desc.set.call(el, nv); else el.value = nv; } catch (e) { return { ok: false, error: '输入失败：' + String(e && e.message || e) }; }
        el.dispatchEvent(mkEvent(W, 'input', { bubbles: true, composed: true, inputType: 'insertText', data: text }, 'InputEvent'));
        el.dispatchEvent(mkEvent(W, 'change', { bubbles: true }));
      }
    } else return { ok: false, error: (ref || '当前元素') + ' 不是输入框' };
    try { el.dispatchEvent(mkEvent(W, 'keyup', kd, 'KeyboardEvent')); } catch (e) {}
    window.__pimetTarget = el;
    var out = { ok: true, form: !!el.form }, rb = readBack(el);
    for (var k in rb) out[k] = rb[k];
    return out;
  };

  A.submit = function () {
    var el = window.__pimetTarget;
    try { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); } catch (e) { return { ok: false, error: String(e) }; }
    return { ok: true };
  };

  A.select = function (ref, v) {
    var rs = resolve(ref);
    if (!rs.el) return rs;
    var el = rs.el;
    if (el.tagName !== 'SELECT') return { ok: false, error: ref + ' 不是下拉框' };
    var o = arrFind(el.options, function (o) { return o.value === v || clean(o.text) === v; }) ||
      arrFind(el.options, function (o) { return clean(o.text).indexOf(v) >= 0; });
    if (!o) {
      var names = [];
      each(el.options, function (op) { names.push(clean(op.text)); });
      return { ok: false, error: '没有选项 ' + v + '，可选：' + names.join(' / ') };
    }
    var W = winOf(el);
    el.value = o.value;
    el.dispatchEvent(mkEvent(W, 'input', { bubbles: true }));
    el.dispatchEvent(mkEvent(W, 'change', { bubbles: true }));
    return { ok: true, result: '已选择 ' + clean(o.text) };
  };

  // ------------------------------------------------------------------ 滚动
  function canScroll(t, dy, dx) {
    var s = styleOf(t);
    if (!s) return false;
    return (dy && /(auto|scroll|overlay)/.test(s.overflowY) && t.scrollHeight > t.clientHeight + 4) ||
      (dx && /(auto|scroll|overlay)/.test(s.overflowX) && t.scrollWidth > t.clientWidth + 4);
  }
  function findScroller(dy, dx) {
    var t = null;
    try { t = document.elementFromPoint(innerWidth / 2, innerHeight / 2); } catch (e) {}
    while (t && t !== document.body && t !== document.documentElement) {
      if (canScroll(t, dy, dx)) return t;
      t = parentOf(t);
    }
    // 中心点在固定层上等情况：找视口内最大的可滚动容器
    var best = null, bestA = 0, list;
    try { list = document.querySelectorAll('div,main,section,article,ul,ol,nav,aside'); } catch (e2) { list = []; }
    var vpA = innerWidth * innerHeight;
    for (var i = 0; i < list.length && i < 1500; i++) {
      var e = list[i];
      if (dy ? e.scrollHeight <= e.clientHeight + 4 : e.scrollWidth <= e.clientWidth + 4) continue;
      var r = e.getBoundingClientRect();
      var a = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0)) * Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
      if (a > bestA && a > vpA * 0.2 && canScroll(e, dy, dx)) { best = e; bestA = a; }
    }
    return best;
  }
  function pos(t, isDoc) { return isDoc ? { x: window.pageXOffset || t.scrollLeft || 0, y: window.pageYOffset || t.scrollTop || 0 } : { x: t.scrollLeft, y: t.scrollTop }; }
  function doScroll(t, isDoc, x, y) {
    if (isDoc) { try { window.scrollBy(x, y); } catch (e) { t.scrollTop += y; t.scrollLeft += x; } return; }
    if (t.scrollBy) { try { t.scrollBy({ left: x, top: y, behavior: 'instant' }); return; } catch (e) {} }
    t.scrollTop += y; t.scrollLeft += x;
  }
  function atEndOf(t, isDoc, dy, dx) {
    var p = pos(t, isDoc), ch = isDoc ? innerHeight : t.clientHeight, cw = isDoc ? innerWidth : t.clientWidth;
    if (dy > 0) return p.y + ch >= t.scrollHeight - 2;
    if (dy < 0) return p.y <= 0;
    if (dx > 0) return p.x + cw >= t.scrollWidth - 2;
    if (dx < 0) return p.x <= 0;
    return false;
  }

  A.scroll = function (ref, dir, amt) {
    if (ref) {
      var rs = resolve(ref);
      if (!rs.el) return rs;
      var b0 = rs.el.getBoundingClientRect();
      reveal(rs.el);
      var b1 = rs.el.getBoundingClientRect();
      return { ok: true, result: '已滚动到 ' + ref, atEnd: false, moved: Math.abs(b1.top - b0.top) >= 1 || Math.abs(b1.left - b0.left) >= 1 };
    }
    amt = +amt; if (!(amt > 0)) amt = 0.8;
    var dy = dir === 'up' ? -1 : dir === 'down' ? 1 : 0, dx = dir === 'left' ? -1 : dir === 'right' ? 1 : 0;
    if (!dy && !dx) return { ok: false, error: '方向只能是 up / down / left / right' };
    var docEl = document.scrollingElement || document.documentElement;
    var sc = findScroller(dy, dx), tgt = sc || docEl, isDoc = !sc;
    var before = pos(tgt, isDoc);
    var h = isDoc ? innerHeight : tgt.clientHeight, w = isDoc ? innerWidth : tgt.clientWidth;
    doScroll(tgt, isDoc, dx * w * amt, dy * h * amt);
    var after = pos(tgt, isDoc), moved = after.x !== before.x || after.y !== before.y;
    if (!moved && !isDoc) { // 内部容器到头了，退回滚整页
      tgt = docEl; isDoc = true; before = pos(tgt, true);
      doScroll(tgt, true, dx * innerWidth * amt, dy * innerHeight * amt);
      after = pos(tgt, true); moved = after.x !== before.x || after.y !== before.y;
    }
    var end = atEndOf(tgt, isDoc, dy, dx);
    var res = moved ? '已滚动 ' + dir + (isDoc ? '' : '（页面内的滚动区域）') + (end ? '，已到' + (dy < 0 || dx < 0 ? '顶' : '底') : '')
      : '没有滚动：已经到' + (dy < 0 || dx < 0 ? '顶' : '底') + '了，或页面不可滚动';
    return { ok: true, result: res, atEnd: end || !moved, moved: moved };
  };

  // ------------------------------------------------------------------ 按键
  function deepActive() {
    var a = document.activeElement, guard = 0;
    while (a && guard++ < 10) {
      if (a.shadowRoot && a.shadowRoot.activeElement) { a = a.shadowRoot.activeElement; continue; }
      if (a.tagName === 'IFRAME' || a.tagName === 'FRAME') {
        var d = null;
        try { d = a.contentDocument; } catch (e) {}
        if (d && d.activeElement) { a = d.activeElement; continue; }
      }
      break;
    }
    return a;
  }

  /** 后台标签不能抢焦点发系统按键：用 JS 键盘事件模拟（Enter 在表单里会提交） */
  A.key = function (key) {
    var el = deepActive() || document.body, W = winOf(el);
    var map = { enter: ['Enter', 13], tab: ['Tab', 9], escape: ['Escape', 27], esc: ['Escape', 27], backspace: ['Backspace', 8], 'delete': ['Delete', 46],
      space: [' ', 32], arrowup: ['ArrowUp', 38], arrowdown: ['ArrowDown', 40], arrowleft: ['ArrowLeft', 37], arrowright: ['ArrowRight', 39],
      pageup: ['PageUp', 33], pagedown: ['PageDown', 34], home: ['Home', 36], end: ['End', 35] };
    var k = map[String(key).toLowerCase()] || [key, 0];
    var init = { key: k[0], code: k[0] === ' ' ? 'Space' : k[0], keyCode: k[1], which: k[1], bubbles: true, cancelable: true, composed: true };
    var ok = el.dispatchEvent(mkEvent(W, 'keydown', init, 'KeyboardEvent'));
    if (k[0] === 'Enter') el.dispatchEvent(mkEvent(W, 'keypress', init, 'KeyboardEvent'));
    el.dispatchEvent(mkEvent(W, 'keyup', init, 'KeyboardEvent'));
    if (ok && k[0] === 'Enter' && el.form && (el.tagName === 'INPUT')) {
      try { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); } catch (e) {}
    }
    if (ok && k[0] === 'Tab') {
      var f = Array.prototype.filter.call(el.ownerDocument.querySelectorAll('input,textarea,select,button,a[href],[tabindex]'), function (e) { return e.tabIndex >= 0 && visible(e); });
      var i = f.indexOf(el);
      if (f.length) try { f[(i + 1) % f.length].focus(); } catch (e) {}
    }
    return { ok: true };
  };

  /** 页面（含同源 iframe 和 shadowRoot）里是否出现某段文字 */
  A.hasText = function (text) {
    var t0 = itext(document.body);
    if (t0 && t0.indexOf(text) >= 0) return { ok: true, v: true };
    var found = false;
    walkRoots(function (root, el, isStart) {
      if (root === document) return;
      if (found || !isStart) return;
      var t = root.body ? itext(root.body) : root.textContent;
      if (t && t.indexOf(text) >= 0) found = true;
    }, null);
    return { ok: true, v: found };
  };

  /** 供宿主判断 eval 的 script 是表达式还是语句体 */
  A.evalExpr = function (src) {
    try { new Function('return (' + src + '\n)'); return { ok: true, expr: true }; } catch (e) { return { ok: true, expr: false }; }
  };

  window.__pimetAgent = A;
})();
