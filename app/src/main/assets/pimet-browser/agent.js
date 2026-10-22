/* PiMet 浏览器自动化的页面端辅助脚本（由 BrowserAgent 每次调用前注入，带版本号，已注入则跳过）。
 * - 快照会递归进入同源 iframe 和开放的 shadowRoot；ref → 元素记在 window.__pimetRefs（Map）里，
 *   所以 iframe / shadow DOM 里的元素也能定位回去（data-pimet-ref 属性只是辅助）。
 * - 坐标统一换算到顶层页面的视口（累加 iframe 的位置和边框），供宿主做真实触摸。
 * 注意：只用 ES5 + Map/Set，兼容较老的 Android WebView。 */
(function () {
  var V = 4;
  if (window.__pimetAgent && window.__pimetAgent.v === V) return;
  var A = { v: V };

  var SEL = 'a[href],button,input:not([type=hidden]),textarea,select,summary,label[for],[role=button],[role=link],[role=checkbox],[role=radio],' +
    '[role=tab],[role=menuitem],[role=option],[role=switch],[role=combobox],[role=textbox],[role=searchbox],[contenteditable=""],[contenteditable=true],[onclick]';
  var STRONG_ROLES = { button: 1, link: 1, menuitem: 1, tab: 1, option: 1, 'switch': 1, checkbox: 1, radio: 1 };

  function clean(s) { return (s || '').replace(/\s+/g, ' ').trim(); }
  function cut(s, n) { s = clean(s); return s.length > n ? s.slice(0, n) + '…' : s; }
  function winOf(el) { return (el && el.ownerDocument && el.ownerDocument.defaultView) || window; }
  function styleOf(el) { try { return winOf(el).getComputedStyle(el); } catch (e) { return null; } }
  function parentOf(el) {
    if (el.parentElement) return el.parentElement;
    var p = el.parentNode;
    return p && p.host ? p.host : null; // 跨出 shadowRoot
  }
  function isField(el) {
    var t = el.tagName;
    return t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT' || el.isContentEditable;
  }

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
  function rectOf(el) {
    var r = el.getBoundingClientRect(), o = frameOffset(el.ownerDocument);
    return { left: r.left + o.x, top: r.top + o.y, width: r.width, height: r.height, right: r.right + o.x, bottom: r.bottom + o.y };
  }
  function visible(el) {
    var r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) return false;
    var s = styleOf(el);
    return !s || (s.visibility !== 'hidden' && s.display !== 'none' && parseFloat(s.opacity || '1') > 0.05);
  }
  function inFrame(el) { return el.ownerDocument !== document; }
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

  /** 遍历文档、同源 iframe、开放 shadowRoot；fn(root) 对每个根调用 */
  function eachRoot(root, fn, cross, depth) {
    depth = depth || 0;
    if (depth > 8) return;
    fn(root);
    var all;
    try { all = root.querySelectorAll('*'); } catch (e) { return; }
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.shadowRoot) eachRoot(el.shadowRoot, fn, cross, depth + 1);
      var tag = el.tagName;
      if (tag === 'IFRAME' || tag === 'FRAME') {
        var d = null;
        try { d = el.contentDocument; } catch (e) { d = null; }
        if (d && d.documentElement) eachRoot(d, fn, cross, depth + 1);
        else if (cross && visible(el)) cross.push(el.src || el.getAttribute('src') || '(无地址)');
      }
    }
  }

  A.get = function (ref) {
    var m = window.__pimetRefs, el = m && m.get(ref);
    if (el && el.isConnected) return el;
    try { return document.querySelector('[data-pimet-ref="' + ref + '"]'); } catch (e) { return null; }
  };

  function nameOf(el, tag, type, role) {
    var name = el.getAttribute('aria-label') || el.getAttribute('title') || '';
    if (!name && tag !== 'input' && tag !== 'textarea' && tag !== 'select') name = el.innerText || el.textContent || '';
    if (!name) { var img = el.querySelector && el.querySelector('img[alt]'); if (img) name = img.getAttribute('alt'); }
    if (!name && tag === 'img') name = el.getAttribute('alt') || '';
    if (!name && (tag === 'input' || tag === 'textarea')) name = el.getAttribute('placeholder') || el.getAttribute('name') || '';
    if (!name && (type === 'submit' || type === 'button')) name = el.value || '';
    return cut(name, 70);
  }

  A.snapshot = function (maxChars, mode) {
    var old = window.__pimetRefs;
    if (old) old.forEach(function (el) { try { el.removeAttribute('data-pimet-ref'); } catch (e) {} });
    try { document.querySelectorAll('[data-pimet-ref]').forEach(function (e) { e.removeAttribute('data-pimet-ref'); }); } catch (e) {}
    var refs = new Map();
    window.__pimetRefs = refs;

    var cand = [], seen = new Set(), cross = [], pointerBudget = 3000;
    eachRoot(document, function (root) {
      var list;
      try { list = root.querySelectorAll(SEL); } catch (e) { list = []; }
      for (var i = 0; i < list.length; i++) if (!seen.has(list[i])) { seen.add(list[i]); cand.push(list[i]); }
      // 很多站点用 div 当按钮：收集 cursor:pointer 的元素（只取最外层）
      if (pointerBudget > 0) {
        var pc;
        try { pc = root.querySelectorAll('div,span,li,img,svg'); } catch (e) { pc = []; }
        for (var j = 0; j < pc.length && pointerBudget > 0; j++, pointerBudget--) {
          var e = pc[j];
          if (seen.has(e)) continue;
          var s = styleOf(e);
          if (!s || s.cursor !== 'pointer') continue;
          var p = parentOf(e), ps = p ? styleOf(p) : null;
          if (ps && ps.cursor === 'pointer') continue;
          try { if (e.closest(SEL)) continue; } catch (x) {}
          seen.add(e); cand.push(e);
        }
      }
    }, cross);

    var H = innerHeight, W = innerWidth, vpArea = H * W;
    // 只保留最外层的可点击元素：祖先是「强」可点击元素（链接/按钮等）或面积不大的可点击容器时，丢弃内层；输入框总是保留
    function dominated(el) {
      if (isField(el)) return false;
      var p = parentOf(el), k = 0;
      while (p && k++ < 12) {
        if (seen.has(p)) {
          var tag = p.tagName, role = p.getAttribute('role');
          if (tag === 'A' || tag === 'BUTTON' || tag === 'SUMMARY' || (role && STRONG_ROLES[role])) return true;
          var r = p.getBoundingClientRect();
          if (r.width * r.height < vpArea * 0.25) return true;
        }
        p = parentOf(p);
      }
      return false;
    }

    var items = [];
    for (var i = 0; i < cand.length && items.length < 400; i++) {
      var el = cand[i];
      if (!visible(el)) continue;
      if (dominated(el)) continue;
      var tag = el.tagName.toLowerCase(), type = (el.getAttribute('type') || '').toLowerCase();
      var role = el.getAttribute('role') || (tag === 'a' ? 'link' : tag === 'button' ? 'button' : tag === 'select' ? 'select' : tag === 'textarea' ? 'textbox'
        : tag === 'input' ? (type === 'checkbox' || type === 'radio' ? type : (type === 'submit' || type === 'button' ? 'button' : 'textbox')) : el.isContentEditable ? 'textbox' : 'clickable');
      var name = nameOf(el, tag, type, role);
      if (!name && role === 'clickable') continue;
      var r = rectOf(el);
      if (role === 'clickable' && !el.getAttribute('role') && r.width * r.height > vpArea * 0.6) continue; // 整页大小的点击容器（事件委托），不是按钮
      var where = r.bottom < 0 ? 2 : r.top > H ? 1 : 0; // 0 视口内 1 下方 2 上方
      items.push({ el: el, tag: tag, type: type, role: role, name: name, r: r, where: where, idx: items.length });
    }
    // 视口内的在前（按从上到下、从左到右），其次是下方，最后是上方
    items.sort(function (a, b) {
      if (a.where !== b.where) return a.where - b.where;
      if (a.where === 0) {
        var dy = Math.round(a.r.top / 8) - Math.round(b.r.top / 8);
        return dy || (a.r.left - b.r.left) || (a.idx - b.idx);
      }
      return (a.r.top - b.r.top) || (a.idx - b.idx);
    });
    if (items.length > 220) items.length = 220;

    var lines = [];
    for (var k = 0; k < items.length; k++) {
      var it = items[k], el2 = it.el, ref = 'e' + (k + 1);
      refs.set(ref, el2);
      try { el2.setAttribute('data-pimet-ref', ref); } catch (e) {}
      var extra = '', tg = it.tag, ty = it.type;
      if (it.role === 'textbox' || tg === 'input') {
        var v = el2.isContentEditable ? el2.innerText : el2.value;
        if (v && ty !== 'password') extra += ' 值="' + cut(v, 60) + '"';
        var ph = el2.getAttribute('placeholder');
        if (ph && it.name !== cut(ph, 70)) extra += ' 提示="' + cut(ph, 40) + '"';
        if (ty && ty !== 'text' && ty !== 'checkbox' && ty !== 'radio') extra += ' type=' + ty;
      }
      if (ty === 'checkbox' || ty === 'radio' || el2.getAttribute('aria-checked')) extra += (el2.checked || el2.getAttribute('aria-checked') === 'true') ? ' [已选]' : ' [未选]';
      if (tg === 'select') { var o = el2.options[el2.selectedIndex]; extra += ' 当前="' + cut(o ? o.text : '', 30) + '" 选项数=' + el2.options.length; }
      if (el2.disabled || el2.getAttribute('aria-disabled') === 'true') extra += ' [禁用]';
      if (inFrame(el2)) extra += ' [iframe]';
      if (it.where === 2) extra += ' (上方)'; else if (it.where === 1) extra += ' (下方)';
      lines.push('[' + ref + '] ' + it.role + (it.name ? ' "' + it.name + '"' : '') + extra);
    }

    var se = document.scrollingElement || document.documentElement;
    var head = '网址: ' + location.href + '\n标题: ' + document.title + '\n滚动: ' + Math.round(se.scrollTop) + '/' + Math.max(0, Math.round(se.scrollHeight - H)) + ' (视口 ' + W + 'x' + H + ')';
    for (var c = 0; c < cross.length && c < 6; c++) head += '\n跨源 iframe：' + cut(cross[c], 120) + '（内容不在快照里，可 open 该网址单独查看）';
    var body = '\n\n可交互元素（用 ref 操作，视口内的在前）:\n' + (lines.join('\n') || '(无)');
    if (mode === 'interactive') {
      var t0 = head + body;
      return { url: location.href, title: document.title, count: items.length, text: t0.length > maxChars ? t0.slice(0, maxChars) + '…（已截断）' : t0 };
    }
    // 页面文字优先取正文区域
    var main = null, label = '';
    try { main = document.querySelector('main,[role=main],article'); } catch (e) {}
    var text = '';
    if (main && clean(main.innerText).length >= 200) { text = clean(main.innerText); label = '（正文区域）'; }
    else text = document.body ? clean(document.body.innerText) : '';
    body += '\n\n页面文字' + label + ':\n';
    var room = Math.max(800, maxChars - head.length - body.length);
    return {
      url: location.href, title: document.title, count: items.length,
      text: head + body + (text.length > room ? text.slice(0, room) + '…（已截断，可滚动或用 eval 读取更多）' : text)
    };
  };

  /** 按文字找元素（含同源 iframe 和 shadowRoot），取文字最短的可见匹配 */
  function findByText(text) {
    var best = null;
    eachRoot(document, function (root) {
      var c;
      try { c = root.querySelectorAll('a,button,[role=button],[role=link],[role=tab],[role=menuitem],input[type=submit],input[type=button],[onclick],label,summary,li,span,div'); } catch (e) { c = []; }
      for (var i = 0; i < c.length; i++) {
        var e = c[i];
        var t = clean(e.innerText || e.value || e.getAttribute('aria-label') || '');
        if (!t || t.indexOf(text) < 0) continue;
        var r = e.getBoundingClientRect();
        if (r.width < 2 || r.height < 2) continue;
        if (!best || t.length < best.len) best = { e: e, len: t.length };
      }
    }, null);
    return best ? best.e : null;
  }

  A.locate = function (ref, text) {
    var el = ref ? A.get(ref) : null;
    if (!el && text) el = findByText(text);
    if (!el) return { ok: false, error: ref ? ('找不到元素 ' + ref + '，页面可能已变化，请重新 snapshot') : '找不到包含「' + text + '」的元素' };
    reveal(el);
    var r = rectOf(el);
    try {
      var o = document.createElement('div');
      o.style.cssText = 'position:fixed;pointer-events:none;z-index:2147483647;border:2px solid #ff4d4f;border-radius:4px;box-shadow:0 0 0 3px rgba(255,77,79,.3);' +
        'left:' + (r.left - 2) + 'px;top:' + (r.top - 2) + 'px;width:' + r.width + 'px;height:' + r.height + 'px';
      document.documentElement.appendChild(o);
      setTimeout(function () { o.remove(); }, 700);
    } catch (x) {}
    var x = r.left + r.width / 2, y = r.top + r.height / 2;
    // 遮挡检测在元素自己的文档 / shadowRoot 里用本地坐标做
    var lr = el.getBoundingClientRect(), lx = lr.left + lr.width / 2, ly = lr.top + lr.height / 2;
    var rootNode = el.getRootNode ? el.getRootNode() : el.ownerDocument;
    var hit = null;
    try { hit = (rootNode.elementFromPoint ? rootNode : el.ownerDocument).elementFromPoint(lx, ly); } catch (e) {}
    var covered = hit && hit !== el && !el.contains(hit) && !hit.contains(el);
    window.__pimetTarget = el;
    var vv = window.visualViewport, vx = vv ? x - vv.offsetLeft : x, vy = vv ? y - vv.offsetTop : y, vw = vv ? vv.width : innerWidth;
    return {
      ok: true, x: vx, y: vy, vw: vw, covered: !!covered, frame: inFrame(el), tag: el.tagName.toLowerCase(),
      label: clean(el.innerText || el.value || el.getAttribute('aria-label') || '').slice(0, 40)
    };
  };

  /** 真实触摸失败（后台标签、窗口最小化等）时的 JS 点击 */
  A.jsClick = function () {
    var el = window.__pimetTarget;
    if (!el || !el.isConnected) return { ok: false, error: '目标元素已不存在' };
    var W = winOf(el), r = el.getBoundingClientRect(), x = r.left + r.width / 2, y = r.top + r.height / 2;
    ['pointerdown', 'mousedown', 'pointerup', 'mouseup'].forEach(function (t) {
      try {
        var C = t.indexOf('pointer') === 0 && W.PointerEvent ? W.PointerEvent : W.MouseEvent;
        el.dispatchEvent(new C(t, { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, view: W }));
      } catch (e) {}
    });
    try { el.focus(); } catch (e) {}
    el.click();
    return { ok: true };
  };

  A.type = function (ref, text, clear) {
    var el = ref ? A.get(ref) : deepActive();
    if (!el || el === document.body) return { ok: false, error: ref ? '找不到元素 ' + ref + '，请重新 snapshot' : '没有获得焦点的输入框，请指定 ref' };
    if (!isField(el)) { var inner = el.querySelector && el.querySelector('input,textarea,[contenteditable]'); if (inner) el = inner; }
    reveal(el);
    try { el.focus(); } catch (e) {}
    var W = winOf(el), D = el.ownerDocument;
    if (el.isContentEditable) {
      if (clear) { D.execCommand('selectAll', false); D.execCommand('delete', false); }
      D.execCommand('insertText', false, text);
    } else if (el.tagName === 'INPUT' || el.tagName === 'TEXTAREA') {
      var proto = el.tagName === 'TEXTAREA' ? W.HTMLTextAreaElement.prototype : W.HTMLInputElement.prototype;
      var set = Object.getOwnPropertyDescriptor(proto, 'value').set;
      set.call(el, (clear ? '' : el.value) + text);
      var IE = W.InputEvent || W.Event;
      el.dispatchEvent(new IE('input', { bubbles: true, inputType: 'insertText', data: text }));
      el.dispatchEvent(new W.Event('change', { bubbles: true }));
    } else return { ok: false, error: (ref || '当前元素') + ' 不是输入框' };
    window.__pimetTarget = el;
    return { ok: true, form: !!el.form };
  };

  A.submit = function () {
    var el = window.__pimetTarget;
    try { if (el.form.requestSubmit) el.form.requestSubmit(); else el.form.submit(); } catch (e) { return { ok: false, error: String(e) }; }
    return { ok: true };
  };

  A.select = function (ref, v) {
    var el = A.get(ref);
    if (!el) return { ok: false, error: '找不到元素 ' + ref + '，请先 snapshot' };
    if (el.tagName !== 'SELECT') return { ok: false, error: ref + ' 不是下拉框' };
    var o = Array.prototype.find.call(el.options, function (o) { return o.value === v || o.text.trim() === v; });
    if (!o) return { ok: false, error: '没有选项 ' + v + '，可选：' + Array.prototype.map.call(el.options, function (o) { return o.text.trim(); }).join(' / ') };
    var W = winOf(el);
    el.value = o.value;
    el.dispatchEvent(new W.Event('input', { bubbles: true }));
    el.dispatchEvent(new W.Event('change', { bubbles: true }));
    return { ok: true, result: '已选择 ' + o.text.trim() };
  };

  A.scroll = function (ref, dir, amt) {
    if (ref) {
      var el = A.get(ref);
      if (!el) return { ok: false, error: '找不到元素 ' + ref };
      reveal(el);
      return { ok: true, result: '已滚动到 ' + ref };
    }
    var dy = dir === 'up' ? -1 : dir === 'down' ? 1 : 0, dx = dir === 'left' ? -1 : dir === 'right' ? 1 : 0;
    var t = document.elementFromPoint(innerWidth / 2, innerHeight / 2), sc = null;
    while (t && t !== document.body && t !== document.documentElement) {
      var s = getComputedStyle(t);
      if ((dy && /(auto|scroll)/.test(s.overflowY) && t.scrollHeight > t.clientHeight + 4) || (dx && /(auto|scroll)/.test(s.overflowX) && t.scrollWidth > t.clientWidth + 4)) { sc = t; break; }
      t = t.parentElement;
    }
    var tgt = sc || document.scrollingElement || document.documentElement;
    var h = sc ? sc.clientHeight : innerHeight, w = sc ? sc.clientWidth : innerWidth;
    tgt.scrollBy({ left: dx * w * amt, top: dy * h * amt, behavior: 'instant' });
    return { ok: true, result: '已滚动 ' + dir };
  };

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
    var ok = el.dispatchEvent(new W.KeyboardEvent('keydown', init));
    if (k[0] === 'Enter') el.dispatchEvent(new W.KeyboardEvent('keypress', init));
    el.dispatchEvent(new W.KeyboardEvent('keyup', init));
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

  /** 页面（含同源 iframe）里是否出现某段文字 */
  A.hasText = function (text) {
    var found = false;
    eachRoot(document, function (root) {
      if (found) return;
      var b = root.body || (root.host ? root : null);
      if (!b) return;
      var t = root.body ? root.body.innerText : root.textContent;
      if (t && t.indexOf(text) >= 0) found = true;
    }, null);
    return { ok: true, v: found };
  };

  window.__pimetAgent = A;
})();
