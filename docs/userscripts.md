# Pi 浏览器 · 油猴脚本管理 + Pi 全面控制（beta.22 合同）

目标：顶栏 🧩 按钮变成"油猴（Tampermonkey）"式用户脚本管理器；Pi 能通过 `browser` 工具全面查看、自定义、操作这个浏览器。
分支：`feature/pi-browser-opt`。每人只改自己负责的文件，不要改别人的文件；需要别人的接口按本文档调用。

## 数据存放（/root/pimet-browser/，即 FloatingApps.appsDir）
- `scripts/<id>.user.js`：脚本源码（兼容旧的 `scripts/*.js`，旧文件当作 id = 文件名去掉 .js）。id = 文件名去掉 `.user.js`，只含 `[A-Za-z0-9_-]`。
- `scripts.json`：`{"<id>": {"enabled": true, "installUrl": "...", "installedAt": 0, "updatedAt": 0}}`。没有记录的脚本默认启用。
- `script-data/<id>.json`：GM_setValue 存的值（JSON 对象）。
- `script-cache/<sha1(url)>`：@require / @resource 下载缓存。

## 1. UserScriptManager.java（新文件，包 com.xm486.pimet.ui）—— 负责人 M1
纯逻辑，不碰 UI。全部方法线程安全；网络方法只能在后台线程调用。
```java
public final class UserScriptManager {
  public static final class Script {
    public String id, name, namespace, version, description, author, icon, runAt; // runAt: document-start|document-end|document-idle（默认 document-idle）
    public List<String> matches, includes, excludes, grants, requires, connects; public Map<String,String> resources;
    public boolean noframes, enabled; public String installUrl, updateUrl, downloadUrl, code; public long mtime;
  }
  public UserScriptManager(File appsDir);
  public List<Script> list();                       // 全部脚本（按 name 排序），带 enabled
  public Script get(String id);
  public List<Script> matching(String url, String runAt); // 启用且匹配该 URL、runAt 的脚本；runAt==null 表示全部
  public static Script parse(String code);          // 解析 ==UserScript== 头；没有头时兼容旧格式 // @match 行；name 缺省用 id
  public Script install(String code, String installUrl) throws Exception; // 保存/覆盖（按 namespace+name 找同一脚本），返回脚本
  public Script installFromUrl(String url) throws Exception;              // 下载后 install
  public boolean setEnabled(String id, boolean on);
  public boolean remove(String id);                 // 删除源码、状态、数据
  public String checkUpdate(String id) throws Exception; // 有新版本返回新代码，否则 null（比较 @version）
  public JSONObject getValues(String id);  public void setValue(String id, String key, String jsonValue); public void deleteValue(String id, String key);
  public String buildInjection(Script s, String tabKey); // 返回可直接 evaluateJavascript 的完整 JS：GM 垫片 + @require 缓存内容 + 脚本，带防重复注入守卫
}
```
- 匹配：`@match` 按 Chrome match pattern（`*://*.example.com/*`、`<all_urls>`）；`@include/@exclude` 支持通配 `*` 和 `/正则/`。
- GM 垫片（buildInjection 生成，每个脚本独立作用域）：`GM_info, unsafeWindow, GM_getValue/GM_setValue/GM_deleteValue/GM_listValues, GM_addStyle, GM_addElement, GM_xmlhttpRequest, GM_openInTab, GM_setClipboard, GM_notification, GM_registerMenuCommand/GM_unregisterMenuCommand, GM_getResourceText, GM_getResourceURL, GM_download, GM_log`，以及 `GM.*` Promise 版本。
  - 值初始快照内嵌进注入脚本（同步读）；写入调用 `window.PiMetGM.setValue(id,key,json)` 持久化。
  - 需要原生的走 JS 接口 `window.PiMetGM`（由 FloatingApps 注册，见 2）：`setValue(id,key,json)`, `deleteValue(id,key)`, `xhr(id, reqJson)`（异步，结果回调 `window.__pimetGMcb(callId, resultJson)`）, `openInTab(url, background)`, `setClipboard(text)`, `notify(id, title, text)`, `registerMenu(id, cmdId, caption)`, `unregisterMenu(id, cmdId)`, `download(url, name)`, `log(id, text)`。菜单点击时原生调用 `window.__pimetGMmenu(id, cmdId)`。
  - GM_xmlhttpRequest 由原生在后台线程用 HttpURLConnection 执行（跨域、带 headers/method/data/responseType text|json|arraybuffer(base64)），受 `@connect` 限制（未声明则允许并在日志提示）。
- 只用 JDK + org.json + Android 自带 API，不加依赖。

## 2. FloatingApps.java —— 负责人 M2
- 🧩 按钮 → 底部弹出"脚本管理"面板（用代码写的 View/AlertDialog，跟现有风格一致）：
  - 顶部：当前页面匹配的脚本数；"＋ 新建/从网址安装"；"检查更新"。
  - 列表：每个脚本 名称、版本、开关（setEnabled 后提示刷新）、当前页是否生效标记；点开 → 详情（描述、匹配规则、编辑源码（简单 EditText 全屏）、删除、查看存储值、该脚本注册的菜单命令）。
  - 当前页注册的 GM 菜单命令单独一组，点击执行。
  - 原来 config.json 的扩展按钮保留为面板里"扩展按钮"一组，"🏠 Pi 做的页面"保留。
- 注入：用 UserScriptManager.matching()。`document-start` 在 onPageStarted 注入（若 androidx.webkit 可用且支持 DOCUMENT_START_SCRIPT 则优先用 WebViewCompat.addDocumentStartJavaScript 不行就 onPageStarted）；`document-end` 在 onPageCommitVisible；`document-idle` 在 onPageFinished。替换旧的 injectUserScripts。`@noframes` 只主框架（目前只能主框架，忽略即可）。
- 注册 `addJavascriptInterface(new GMBridge(tab), "PiMetGM")`，实现第 1 节列出的方法；每个方法校验脚本 id 存在且启用。
- 打开以 `.user.js` 结尾的网址（导航或下载）时拦截 → 弹"安装脚本"确认框（显示名称、版本、匹配、权限）→ installFromUrl。
- 提供给 BrowserAgent 的包级方法（主线程调用）：`UserScriptManager scripts()`、`JSONArray menuCommands(Tab t)`、`void runMenuCommand(Tab t, String scriptId, String cmdId)`、`JSONArray consoleLogs(Tab t, int max)`（捕获 console.* 和 JS 错误，每页最多 300 条）、`JSONObject getConfig()`、`void setConfig(JSONObject patch)`（合并写入 config.json 并 reloadConfigNow）、`void setUserAgent(String uaOrNull)`、`void setDesktopMode(boolean)`、`void setZoom(int percent)`、`void clearData(String what)`（cookies|cache|storage|all）、`String getCookies(String url)`、`void setCookie(String url, String cookie)`。
- 同时显示 console 日志：控制台捕获用注入 HELPER 或 WebChromeClient.onConsoleMessage。

## 3. BrowserAgent.java —— 负责人 M3
在 handle() 增加以下 action（参数 JSON，返回 ok().put(...)），调用第 2 节的方法：
- `scripts`（列表：id,name,version,enabled,matchesCurrent,runAt）
- `script_get`{id} → 含 code
- `script_install`{code? url?}（二选一）
- `script_enable`{id,on}、`script_remove`{id}、`script_update`{id?}（不填 = 全部检查并更新）
- `script_values`{id, set?:{k:v}, delete?:[k]}
- `menu_commands`、`menu_run`{script,cmd}
- `console`{max?=100, clear?}
- `config_get`、`config_set`{patch}（theme/home/buttons/showButtonBar 等）
- `ua`{ua|null}、`desktop`{on}、`zoom`{percent}
- `cookies`{url, set?}、`clear_data`{what}
- `find`{text}（页面内查找，返回匹配数）、`wait_for`{selector|text, timeout_ms≤30000}
- `inject`{js, persist?:{name,match}}：persist 时等价于生成一个用户脚本并安装
同时更新 `status` 返回值，加入 scripts 数量、当前 UA 模式、zoom。

## 4. pimet-browser.ts + SKILL.md + agent.js —— 负责人 M4
- `pimet-browser.ts` 的 `browser` 工具：把第 3 节的 action 加进 schema（enum + 参数说明，中文简短）。
- `SKILL.md`：新增"油猴脚本"与"自定义浏览器"章节：怎么写一个用户脚本（==UserScript== 模板、可用 GM API、run-at）、怎么用 action 安装/启停/调试（console）、config.json 字段说明、常见示例（去广告、自动签到、深色模式、页面加按钮）。
- `agent.js`：若有 action 白名单/路由，同步加入新 action。

## 5. 主控（host）
- gradle：versionCode 114 / versionName 1.9.0-beta.22；如 M2 需要，加 `androidx.webkit:webkit:1.12.1`。
- 合并、node --check / 查 Java 字符串里的字面换行、CI。

## 交付要求（所有负责人）
- 直接提交到 `feature/pi-browser-opt`（提交前拉最新，只提交自己的文件），提交信息以 `[M1]`…`[M4]` 开头。
- 回复格式：【结论】一句话 【证据】提交 SHA 【问题】接口上需要别人配合的点 【完成】
