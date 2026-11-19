# 定制 Pi 浏览器与油猴脚本

```
/root/pimet-browser/
  ui.json              浏览器外观、工具栏、菜单、悬浮球、主页、搜索引擎、站点规则、拦截/重定向、面板
  config.json          旧版配置：工具栏按钮、配色、首页
  scripts/<id>.user.js 用户脚本（油猴脚本），按网址自动注入网页；旧的 scripts/*.js 也兼容
  scripts.json         脚本启停状态和安装来源（由 🧩 脚本管理 / script_* 动作维护，不要手改）
  script-data/<id>.json  脚本用 GM_setValue 存的值
  pages/<id>/index.html  你做的页面/小工具（可多个，可用 window.pimet 原生接口）
  pages/<id>/app.json    可选：{"name":"记账本","icon":"💰","description":"一句话"}
  home.html            可选：自定义首页（不存在时显示内置首页 + pages 列表）
  sites/<站点>/        站点经验 notes.md 和可复用工具 tools/*.mjs
```

改完配置后通常即时热生效（`ui_*` / `config_set`）；手改文件可调 `browser` 的 `reload_ui` 让配置立即生效；页面和脚本让用户点 ⋮ → 刷新（或你用 `browser` 的 `reload`）即可。

## 自定义浏览器（ui.json）

浏览器的外观、工具栏、菜单、悬浮球、主页/新标签页、搜索引擎、长按菜单、手势、站点规则、拦截/重定向、自定义面板，全部由 `/root/pimet-browser/ui.json` 描述。用 `browser` 的 `ui_*` 动作读写，**写入后立即生效**（热重载：不丢标签、不重载网页），不用重装 App。缺省的字段用内置默认值，不认识的字段会原样保留。

**工作方式**

1. 先看：`ui_schema`（字段说明 + 全部内置按钮 id 及能放的位置），`ui_get`（整份配置）或 `ui_get {pointer: "/toolbar"}`（某一项）。
2. 再改：一处用 `ui_set {pointer, value}`；多处一起改用 `ui_patch {patch}`（RFC 7386 合并补丁：对象逐层合并、`null` 删除字段、**数组整体替换**——改数组要么先 `ui_get` 再整组写回，要么用 `ui_set` 针对某个下标或 `/-` 追加）；删除用 `ui_remove {pointer}`；恢复默认用 `ui_reset {pointer?}`（不填 = 整份，先征得用户同意）。
3. 写入前会校验（未知内置 id、颜色格式、正则写错等），**有错就返回错误、不写入**，按错误改了再写。
4. 改坏了 / 用户不满意：`ui_backups` 列出自动备份（每次写入前一份，保留 20 份），`ui_restore {name}` 回滚。用户也可以在菜单「⚙ 自定义」里直接编辑、恢复默认、从备份恢复。
5. 改完告诉用户改了什么、在哪里看到。

JSON Pointer：`/theme/accent`、`/toolbar/2`（第 3 个按钮，从 0 开始）、`/toolbar/-`（追加到末尾）、`/sites/*.example.com`（键里的 `/` 写成 `~1`，`~` 写成 `~0`）。

**配置结构**

| 字段 | 内容 |
|---|---|
| `theme` | `mode`（auto/light/dark）、`accent` 主题色、`toolbarBg` / `toolbarFg`、`toolbarPosition`（top/bottom）、`fontScale`、`tabStyle`（bar/list）、`css`（注入所有页面的 CSS） |
| `home` | `homepage`（主页网址）、`newTab`（homepage / blank / `panel:<id>` / 网址） |
| `search` | `default` 默认引擎 id、`engines`（`{id,name,url}`，url 里 `%s` 是关键词）、`suggest` |
| `toolbar` | 按钮数组，顺序即显示顺序：内置 `{"id":"back"}`，或自定义 `{"id":"my1","icon":"🧪","label":"测试","action":{…}}`；不想要的按钮直接从数组里去掉 |
| `menu` | ⋮ 菜单项，格式同上，`{"type":"divider"}` 是分隔线 |
| `ball` | 悬浮球：`enabled`、`snapEdge`（是否吸附屏幕边缘）、`items`（展开后的按钮）、`size`、`opacity` |
| `longPress` | 长按 `link` / `image` / `text` 时弹出的菜单项数组 |
| `gestures` | `swipeLeft` / `swipeRight` / `doubleTapToolbar` → action |
| `sites` | `{"*.example.com": {ua: desktop/mobile/字符串, zoom, js, images, darkMode, css, container}}`，按网址通配匹配 |
| `rules` | `block`（`||ads.example.com^`、`*://*/track*`）、`redirect`（`{from: Java 正则, to: 替换，$1 $2…}`） |
| `panels` | 自定义面板 `{id,title,icon,file,where}`，用 `panel_write` 管理，不用手写 |
| `defaults` | `container`（新标签默认 new / Default）、`desktopMode`、`downloadDir` |

内置 id：back forward reload stop home url tabs newtab menu history scripts cookies containers desktop zoom find share fullscreen translate theme askpi terminal external downloads settings close（以 `ui_schema` 返回的为准，它还会说明每个能放在哪）。

**action**（自定义按钮、菜单、悬浮球、长按、手势都用它）

| type | 例子 | 作用 |
|---|---|---|
| `builtin` | `{"type":"builtin","id":"reload"}` | 执行内置功能 |
| `open` | `{"type":"open","url":"https://…","newTab":true,"container":"same"}` | 打开网址 |
| `js` | `{"type":"js","code":"document.body.style.zoom=1.2"}` | 在当前页执行 JS |
| `script_menu` | `{"type":"script_menu","script":"<脚本id>","cmd":"<命令id>"}` | 运行油猴脚本的菜单命令 |
| `panel` | `{"type":"panel","id":"notes"}` | 打开自定义面板 |
| `askpi` | `{"type":"askpi","prompt":"总结这个页面：{url}"}` | 把问题发给 Pi，可用 `{url}` `{title}` `{selection}` 占位 |
| `seq` | `{"type":"seq","steps":[{…},{…}]}` | 依次执行多个 action |

自定义功能**优先组合 action**；先用 `action_run {ui_action: {…}}` 试一下效果，满意再写进配置。界面复杂（列表、表单、多按钮）时写成面板。

**面板**：`panel_write {id, title, icon, html, where}`（`where`：menu / toolbar / ball / none；HTML 较长时可先写到文件再用 `html_file` 传路径），`panel_open {id}`、`panel_delete {id}`、`panels`。面板是浮层里的网页，可用 `window.PiMetUI`：`runAction(json)`、`openUrl(url)`、`close()`、`getConfig()`、`currentPage()`（返回 `{url,title}`）。数据可存 `localStorage`。

**站点与规则**：`site_set {pattern, settings}`、`site_get {url}`（看某网址最终生效的设置）；`rule_add {kind: "block"|"redirect", rule}`、`rule_remove {kind, index 或 rule}`、`rules`。

**常见示例**

- 工具栏放到底部：`ui_set {pointer: "/theme/toolbarPosition", value: "bottom"}`
- 加一个「总结本页」按钮：`ui_set {pointer: "/toolbar/-", value: {"id":"sum","icon":"📝","label":"总结本页","action":{"type":"askpi","prompt":"总结这个页面：{title} {url}"}}}`
- 给某站开桌面模式：`site_set {pattern: "*.zhihu.com", settings: {"ua": "desktop", "zoom": 90}}`
- 拦截广告域名：`rule_add {kind: "block", rule: "||ads.example.com^"}`
- reddit 重定向到旧版：`rule_add {kind: "redirect", rule: {"from": "^https?://(www\\.)?reddit\\.com/(.*)", "to": "https://old.reddit.com/$2"}}`
- 悬浮球加按钮、关掉吸附：`ui_patch {patch: {"ball": {"snapEdge": false}}}`，再 `ui_set {pointer: "/ball/items/-", value: {"id": "askpi"}}`
- 深色主题 + 换主题色：`ui_patch {patch: {"theme": {"mode": "dark", "accent": "#E91E63"}}}`
- 笔记面板（放在菜单里）：

```json
{"action": "panel_write", "id": "notes", "title": "笔记", "icon": "📝", "where": "menu",
 "html": "<!doctype html><meta name=viewport content='width=device-width'><style>body{font:15px sans-serif;margin:12px}textarea{width:100%;height:60vh}</style><textarea id=t></textarea><button id=add>记下当前网页</button> <button onclick='PiMetUI.close()'>关闭</button><script>var t=document.getElementById('t');t.value=localStorage.notes||'';t.oninput=function(){localStorage.notes=t.value};document.getElementById('add').onclick=function(){var p=PiMetUI.currentPage();if(typeof p==='string')p=JSON.parse(p);t.value+='\\n'+p.title+' '+p.url;t.oninput()}</script>"}
```

在 `run` 里也能用：`await b.ui.get("/theme")`、`await b.ui.set(pointer, value)`、`await b.ui.patch({...})`、`await b.ui.backups()`、`await b.ui.restore(name)`、`await b.runAction({...})`。

## 油猴脚本（用户脚本）

顶栏 🧩 是油猴式的脚本管理器：用户在里面看到每个脚本的开关、当前页是否生效、脚本注册的菜单命令，也能编辑、删除、检查更新。打开以 `.user.js` 结尾的网址会弹出安装确认。你用 `browser` 的下面这些 action 做同样的事：

| action | 参数 | 作用 |
|---|---|---|
| `scripts` | | 列出脚本：id、名称、版本、是否启用、是否匹配当前页、run-at |
| `script_get` | `id` | 读脚本源码和信息 |
| `script_install` | `code` 或 `url` | 安装/覆盖（同 `@namespace`+`@name` 视为同一脚本，覆盖更新） |
| `script_enable` | `id`, `on` | 启用 / 停用（刷新页面后生效） |
| `script_remove` | `id` | 删除脚本及其存储的值（**先征得用户同意**） |
| `script_update` | `id`（不填=全部） | 按 `@updateURL`/`@downloadURL`/安装地址检查新版本并更新（比较 `@version`） |
| `script_values` | `id`, `set?`, `delete?` | 查看 / 写入 / 删除脚本的 GM 存储值 |
| `menu_commands` / `menu_run` | `id`, `cmd` | 列出当前页脚本注册的菜单命令 / 执行一个 |
| `inject` | `script`, `persist?` | 往当前页临时注入 JS；带 `persist: {name, match}` 时同时安装成用户脚本 |
| `console` | `max?`, `clear?` | 当前页的 console 输出和 JS 报错（每页最多 300 条） |
| `find` / `wait_for` | `text` / `selector` 或 `text`, `timeout_ms`（≤30000） | 页面内查找文字返回匹配数 / 等元素或文字出现 |

**写脚本的流程**：先 `open` 目标网站，用 `snapshot`、`b.js` 摸清结构和选择器 → 用 `inject`（不带 persist）在当前页试跑 → `console` 看报错 → 满意后 `script_install`（`code` 传完整源码）→ `reload` 页面，再 `console` + `snapshot` 验证 → 告诉用户脚本名和在 🧩 里怎么开关。改已有脚本：`script_get` 取源码，改完 `script_install` 覆盖，记得把 `@version` 调高。

模板：

```js
// ==UserScript==
// @name         示例：页面加按钮
// @namespace    pimet
// @version      1.0.0
// @description  一句话说明
// @match        https://example.com/*
// @exclude      https://example.com/login*
// @run-at       document-idle
// @grant        GM_addStyle
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_registerMenuCommand
// ==/UserScript==
(function () {
  'use strict';
  // 你的代码
})();
```

- **匹配**：`@match` 用 Chrome 匹配规则（`*://*.example.com/*`、`https://m.example.com/path/*`、`<all_urls>`）；`@include`/`@exclude` 支持通配 `*` 和 `/正则/`。没有任何 `@match`/`@include` 的脚本不会注入。
- **`@run-at`**：`document-start`（页面脚本运行前，适合拦截/改写变量，此时 `document.body` 可能还不存在）、`document-end`（DOM 可用，图片等还在加载）、`document-idle`（默认，加载完成后）。
- **GM API**（每个脚本独立作用域，`GM.*` 是 Promise 版）：`GM_getValue/GM_setValue/GM_deleteValue/GM_listValues`（持久存储，按脚本隔离）、`GM_addStyle(css)`、`GM_addElement(tag, attrs)`、`GM_xmlhttpRequest({method, url, headers, data, responseType, onload, onerror})`（跨域，由原生发请求；用 `@connect 域名` 声明要访问的域）、`GM_openInTab(url, background)`、`GM_setClipboard(text)`、`GM_notification(text, title)`、`GM_registerMenuCommand(名称, fn)`/`GM_unregisterMenuCommand`（出现在 🧩 面板的菜单命令组）、`GM_getResourceText/GM_getResourceURL`（配合 `@resource 名 网址`）、`GM_download(url, name)`、`GM_log`、`GM_info`、`unsafeWindow`。
- **`@require 网址`**：首次注入前下载并缓存，之后离线可用。
- 脚本里**拿不到** `window.pimet`（防止网站借此控制手机）；需要容器能力时做成 pages 页面，或用 `GM_registerMenuCommand` 配合 `askPi` 按钮。
- `@noframes` 可写可不写，目前只注入主框架。
- 单页应用（SPA）切换路由不会重新注入：脚本里用 `MutationObserver` 或监听 `popstate`/定时检查网址来处理。
- 去广告、隐藏元素优先用 `GM_addStyle` 写 CSS（`display:none !important`），比删 DOM 稳定、不闪烁。

常见示例：

```js
// ==UserScript==
// @name     去广告：示例站
// @namespace pimet
// @version  1.0.0
// @match    *://*.example.com/*
// @run-at   document-start
// @grant    GM_addStyle
// ==/UserScript==
GM_addStyle('.ad, [id^="ad-"], .banner-ad, iframe[src*="ads"] { display: none !important; }');
```

```js
// ==UserScript==
// @name     深色模式
// @namespace pimet
// @version  1.0.0
// @match    https://example.com/*
// @run-at   document-start
// @grant    GM_addStyle
// ==/UserScript==
GM_addStyle('html { filter: invert(1) hue-rotate(180deg); background: #fff; } img, video, picture, canvas { filter: invert(1) hue-rotate(180deg); }');
```

```js
// ==UserScript==
// @name     自动签到：示例站（每天一次）
// @namespace pimet
// @version  1.0.0
// @match    https://example.com/*
// @grant    GM_getValue
// @grant    GM_setValue
// @grant    GM_notification
// ==/UserScript==
(async () => {
  const today = new Date().toDateString();
  if (GM_getValue('last') === today) return;
  const btn = await new Promise((ok) => {
    const t0 = Date.now();
    const iv = setInterval(() => {
      const b = [...document.querySelectorAll('button, a')].find((e) => /签到/.test(e.textContent));
      if (b || Date.now() - t0 > 10000) { clearInterval(iv); ok(b); }
    }, 500);
  });
  if (!btn) return;
  btn.click();
  GM_setValue('last', today);
  GM_notification('已自动签到', '签到');
})();
```

```js
// ==UserScript==
// @name     页面加按钮：复制标题和链接
// @namespace pimet
// @version  1.0.0
// @match    *://*/*
// @grant    GM_setClipboard
// @grant    GM_registerMenuCommand
// ==/UserScript==
const copy = () => GM_setClipboard(document.title + '\n' + location.href);
GM_registerMenuCommand('复制标题和链接', copy);
const btn = document.createElement('button');
btn.textContent = '📋';
btn.style.cssText = 'position:fixed;right:12px;bottom:80px;z-index:2147483647;width:44px;height:44px;border-radius:22px;border:0;background:#1a73e8;color:#fff;font-size:20px;box-shadow:0 2px 6px rgba(0,0,0,.3)';
btn.onclick = copy;
document.body.appendChild(btn);
```

注意：自动签到、自动点击类脚本只对用户自己的账号、按用户要求做；不要写绕过验证码、支付确认或网站风控的脚本。

## 旧版配置（config.json）与旧设置类 action

新功能请优先使用上面的 `ui.json`；下面这些配置和动作仍兼容可用。

```json
{
  "buttons": [
    {"icon": "📝", "title": "让 Pi 总结本页", "askPi": "用 browser 工具读一下当前页面（{url}），给我简要总结。"},
    {"icon": "🌙", "label": "夜间", "script": "document.documentElement.style.filter = document.documentElement.style.filter ? '' : 'invert(1) hue-rotate(180deg)';"},
    {"icon": "💰", "label": "记账", "open": "pages/ledger/index.html"},
    {"icon": "🔍", "open": "https://m.baidu.com"}
  ],
  "theme": {
    "background": "#202124", "text": "#E8EAED", "accent": "#8AB4F8",
    "addressBar": "#303134", "addressText": "#E8EAED", "button": "#303134", "page": "#202124"
  },
  "home": ""
}
```

- `buttons` 显示在顶栏 🧩 扩展菜单里（菜单项显示 `label`，没有 label 时显示 `title`）；加 `"showButtonBar": true` 还会在地址栏下方显示一行按钮。每个按钮可以有：
  - `script`：在当前网页里执行的 JS。
  - `open`：打开网址；`pages/...` 或 `home.html` 指本目录下的文件，`/` 开头指容器内绝对路径。
  - `askPi`：把文字发给你（Pi）。可用占位符 `{url}` `{title}` `{selection}`（用户选中的文字）。加 `"send": false` 则只填进输入框不发送。
  - `icon`（emoji）、`label`（文字）、`color`（文字颜色）。
- `theme`：颜色都是可选的，格式 `#RRGGBB`；不写时跟随 Pi-Web 的主题（浅色/深色）。`background` 顶栏和状态栏，`accent` 进度条和高亮，`page` 网页底色。
- `home`：首页地址，留空则用 `home.html` 或内置首页。
- JSON 写错时用户会看到提示，原配置不生效。改之前先读出原文件，在原基础上修改，不要删掉用户已有的按钮。

| action | 参数 | 作用 |
|---|---|---|
| `config_get` | | 读 config.json（theme / home / buttons / showButtonBar …） |
| `config_set` | `patch` | 把字段合并写入 config.json 并立即生效；数组（如 `buttons`）整体替换，所以先 `config_get` 再在原基础上改 |
| `ua` | `ua` | 设置自定义 User-Agent；`""` 或 `"default"` 恢复默认 |
| `desktop` | `on` | 桌面版网页（true）/ 手机版（false） |
| `zoom` | `percent` | 页面缩放，如 `125` |
| `cookies` | `url`, `set?`, `container?` | 读某网址的 Cookie / 设置一条（如 `"theme=dark; path=/"`）；默认当前标签的容器 |
| `clear_data` | `what` | 清除 `cookies` / `cache` / `storage` / `all`（会让用户退出登录，**先征得用户同意**） |

`status` 还会返回脚本数量、当前 UA 模式、缩放、当前容器和是否支持多容器。直接改 `/root/pimet-browser/config.json` 文件也行（改完调 `reload_ui`）；用 `config_set` 更省事，也不会写坏 JSON。

## 自定义页面 pages/<id>/

- `<id>` 用小写英文和连字符。`index.html` 是入口，用相对路径引用自己的文件。
- 手机竖屏优先：必须写 `<meta name="viewport" content="width=device-width,initial-scale=1">`，按钮至少 40px 高，内容可滚动；默认深色背景。
- 优先做成不依赖网络的单文件页面；需要库时可以用 CDN。
- 这些页面（以及 home.html）可以使用 `window.pimet`，页面加载完成后注入：

```js
function onPimet(fn) { if (window.pimet) fn(); else addEventListener('pimetready', fn, { once: true }); }
```

  - `pimet.exec(cmd, {timeout})` → `Promise<{code, stdout, stderr}>`：在容器里用 bash 执行命令（工作目录 /root，默认 60 秒，最长 600 秒）。往命令里拼用户输入要先转义，或先写进文件。
  - `pimet.askPi(text, send = true)`：发消息给你。
  - `pimet.toast(text)`、`pimet.openExternal(url)`、`pimet.close()`。
- 持久数据：小的用 `localStorage`；要给你读取的写到 `/root/pimet-browser/pages/<id>/data/`。
- 想让页面出现在 🧩 扩展菜单里，就在 `ui.json` 或 `config.json` 加一个打开 `pages/<id>/index.html` 的按钮。Pi 做的页面也会自动出现在首页的快捷方式里。

## 做完之后

用一两句话告诉用户结果：网页任务给出要的信息和沉淀了哪些工具；定制任务说明改了什么、在哪里看（🧩 扩展菜单里的新按钮 / 点 ⌂ 回首页 / ⋮ → 刷新），不用贴代码。
