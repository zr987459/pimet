# Pi 浏览器 · Pi 可自定义浏览器的一切（beta.24 合同）

目标：浏览器的外观、工具栏、菜单、悬浮球、主页/新标签页、搜索引擎、站点规则、拦截/重定向、自定义面板等全部由一份配置描述；Pi 通过 `browser` 工具读写它，改完立即生效（热重载），不用重新打包 App。
分支 `feature/pi-browser-opt`（基于 d019b3e / beta.23 + 悬浮球吸附）。每人只改自己负责的文件。Java 字符串里不要字面换行，不加新依赖。
数据目录 `/root/pimet-browser/`（FloatingApps.appsDir）。

## 配置 ui.json（缺省项用内置默认值；未知字段保留不删）
```json
{
  "version": 1,
  "theme": {"mode":"auto|light|dark", "accent":"#2196F3", "toolbarBg":null, "toolbarFg":null, "toolbarPosition":"top|bottom", "fontScale":1.0, "tabStyle":"bar|list", "css":""},
  "home": {"homepage":"about:blank 或 URL", "newTab":"homepage|blank|panel:<id>|<URL>"},
  "search": {"default":"bing", "engines":[{"id":"bing","name":"必应","url":"https://www.bing.com/search?q=%s"}], "suggest":true},
  "toolbar": [{"id":"back"},{"id":"forward"},{"id":"url"},{"id":"tabs"},{"id":"menu"},{"id":"my1","icon":"🧪","label":"测试","action":{...}}],
  "menu":    [{"id":"history"},{"id":"scripts"},{"id":"cookies"},{"type":"divider"},{"id":"x","icon":"🔧","label":"自定义","action":{...}}],
  "ball":    {"enabled":true, "snapEdge":true, "items":[{"id":"fullscreen"},{"id":"refresh"},{"id":"translate"},{"id":"askpi"}], "size":44, "opacity":1.0},
  "longPress": {"link":[...], "image":[...], "text":[...]},
  "gestures": {"swipeLeft":{...}, "swipeRight":{...}, "doubleTapToolbar":{...}},
  "sites": {"*.example.com": {"ua":"desktop|mobile|<字符串>", "zoom":100, "js":true, "images":true, "darkMode":false, "css":"", "container":"<name>"}},
  "rules": {"block":["||ads.example.com^","*://*/track*"], "redirect":[{"from":"^https?://(www\\.)?reddit\\.com/(.*)","to":"https://old.reddit.com/$2"}]},
  "panels": [{"id":"notes","title":"笔记","icon":"📝","file":"panels/notes.html","where":"menu|toolbar|ball|none"}],
  "defaults": {"container":"new|Default", "desktopMode":false, "downloadDir":"/sdcard/Download"}
}
```
**action** 统一格式：`{"type":"builtin","id":"reload"}` | `{"type":"open","url":"...","newTab":false,"container":"same"}` | `{"type":"js","code":"..."}`（在当前页执行）| `{"type":"script_menu","script":"<id>","cmd":"<cmdId>"}` | `{"type":"panel","id":"notes"}` | `{"type":"askpi","prompt":"总结这个页面：{url}"}`（{url}{title}{selection} 占位）| `{"type":"seq","steps":[action...]}`。
内置 id（toolbar/menu/ball/longPress 都可用，列表由 `builtins()` 返回）：back, forward, reload, stop, home, url, tabs, newtab, menu, history, scripts, cookies, containers, desktop, zoom, find, share, fullscreen, translate, theme, askpi, terminal, external, downloads, settings, close。

## 1. BrowserCustomizer.java（新文件，包 com.xm486.pimet.ui）—— M1
```java
public final class BrowserCustomizer {
  public BrowserCustomizer(File appsDir);
  public JSONObject config();                 // 合并默认值后的完整配置（副本）
  public JSONObject raw();                    // ui.json 原文
  public Object get(String pointer);          // JSON Pointer，如 "/theme/accent"、"/toolbar/2"
  public void set(String pointer, Object value) throws Exception;   // 校验后写盘；"/toolbar/-" 追加
  public void patch(JSONObject mergePatch) throws Exception;        // RFC 7386 合并补丁
  public void remove(String pointer) throws Exception;
  public void reset(String pointerOrNull);    // 恢复默认（整份或某一项）
  public List<String> validate(JSONObject cfg); // 返回错误列表（未知内置 id、颜色格式、正则编译失败等）；有错拒绝写入
  public static JSONArray builtins();         // [{id,icon,label,where:[toolbar,menu,ball,...]}]
  public static String schemaDoc();           // 给 Pi 看的简明说明（字段、action 类型），纯文本
  public JSONObject siteSettings(String url); // 合并所有匹配 sites 的设置（通配 host）
  public boolean shouldBlock(String url);     // rules.block：支持 ||domain^ 与 * 通配
  public String redirect(String url);         // rules.redirect：Java 正则，无匹配返回 null
  public File panelFile(String id); public void writePanel(String id, String title, String icon, String html, String where) throws Exception; public boolean deletePanel(String id);
  public void addListener(Runnable onChange); // 写入后、以及 ui.json 被外部修改时（FileObserver）回调，主线程
  public JSONObject backup(); public void restore(JSONObject snapshot) throws Exception; // 每次写入前自动备份到 ui.bak/<时间>.json，保留 20 份
}
```
线程安全；规则编译结果缓存，shouldBlock/redirect 要快（在网络线程频繁调用）。

## 2. FloatingApps.java —— M2
- 启动时创建 `BrowserCustomizer customizer`，所有 UI 从 `customizer.config()` 渲染：工具栏按钮顺序/隐藏/自定义按钮、位置 top/bottom、主题色、tabStyle、字体缩放、悬浮球 items/size/opacity/enabled/snapEdge（snapEdge=false 时不吸附）、菜单、长按菜单、手势、主页和新标签页、搜索引擎（地址栏输入非 URL 时用 default engine）。
- `addListener` 收到变化时**热重载**：重建工具栏/菜单/悬浮球，重新应用主题；不丢标签、不重载网页。
- `shouldInterceptRequest`：`shouldBlock` 返回空响应；导航前 `redirect`。`onPageStarted` 按 `siteSettings` 应用 UA/缩放/JS/图片/深色/注入 css/默认容器。`theme.css` 注入所有页面。
- 实现 action 执行器 `void runAction(JSONObject action, Tab tab)`（type 见上）；`askpi` 调已有的「问 Pi」入口。
- 面板：`panel` action 打开一个浮层 WebView 加载 `panels/<id>.html`（file://，同容器 Default），注入 JS 接口 `window.PiMetUI`：`runAction(json)`, `openUrl(url)`, `close()`, `getConfig()`, `currentPage()` 返回 {url,title}；`where` 决定出现在菜单/工具栏/悬浮球。
- 菜单加「⚙ 自定义」：显示当前 ui.json（可编辑文本框 + 保存/恢复默认/从备份恢复）。
- 给 M3：`BrowserCustomizer customizer()`、`void runAction(JSONObject action)`（当前标签）、`void openPanel(String id)`、`void reloadUi()`。

## 3. BrowserAgent.java —— M3
新 action：
- `ui_get {pointer?}` → 值（缺省整份 config）；`ui_schema` → `schemaDoc()` + `builtins()`；
- `ui_set {pointer, value}`、`ui_patch {patch}`、`ui_remove {pointer}`、`ui_reset {pointer?}`、`ui_backups`、`ui_restore {name}`：写入后返回 `{ok, errors[]}`；校验失败返回错误不写入。
- `panel_write {id, title, icon, html, where}`、`panel_open {id}`、`panel_delete {id}`、`panels`。
- `action_run {action}`：立即执行一个 action（方便 Pi 先试再写进配置）。
- `site_set {pattern, settings}` / `site_get {url}`；`rule_add {kind:"block"|"redirect", rule}` / `rule_remove {kind, index|rule}` / `rules`。
- status 加 `ui_version`、`panels` 数量。

## 4. pimet-browser.ts / SKILL.md —— M4
- browser 工具加上述 action（参数原样透传，结果原样显示）；工具描述写清：先 `ui_schema`/`ui_get` 再改；小改用 `ui_set`，多处改用 `ui_patch`；改坏了用 `ui_restore`；自定义功能优先组合 action，复杂界面写成 panel（HTML+JS，可调 `PiMetUI.runAction`）。
- SKILL.md 新增「自定义浏览器」章节：配置结构、action 类型、常见示例（底部工具栏、加一个“总结本页”按钮=askpi、给某站开桌面模式、拦截广告域名、reddit 重定向、写一个笔记面板、悬浮球加按钮/关吸附）、改完立即生效、出错如何回滚。
