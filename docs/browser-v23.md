# Pi 浏览器 · 历史 + 容器标签 + ego 式 Pi 操作（beta.23 合同）

分支 `feature/pi-browser-opt`（基于 fba893e / beta.22）。每人只改自己负责的文件；需要别人的接口按本文档调用（大家同时在写）。
参考：容器标签参考 zr987459/cookie-box（`mcp/TabRegistry.kt`、`model/TabItem.kt`：androidx.webkit `ProfileStore` + `WebViewCompat.setProfile`，每个多开标签独立 Profile）；Pi 操作参考 ego lite 的 ego-browser（https://lite.ego.app/document/en/docs/ego-browser）：一次提交整段脚本、语义快照 `[ref=N]`、按 `@N` 操作、内置等待，少轮次少 token。
数据目录仍是 `/root/pimet-browser/`（FloatingApps.appsDir）。Java 字符串里不要出现字面换行。

## 0. 主控（host）
- `app/build.gradle`：加 `implementation 'androidx.webkit:webkit:1.12.1'`；versionCode 115，versionName 1.9.0-beta.23。

## 1. 新文件 ContainerManager.java + HistoryStore.java（包 com.xm486.pimet.ui）—— M1
```java
public final class ContainerManager {          // 容器 = 一个 WebView Profile（独立 Cookie/Storage/缓存）
  public static final String DEFAULT = "Default";   // 共享容器（Pi 和用户的旧标签默认用它之外的新容器）
  public static boolean supported();             // WebViewFeature.isFeatureSupported(MULTI_PROFILE)
  public ContainerManager(File appsDir);         // 元数据存 containers.json: {"<name>":{"label":"容器 3","color":"#4CAF50","createdAt":0,"keep":false}}
  public String newContainer();                  // 生成 "c<递增数字>"，分配颜色和中文标签，返回 name
  public void apply(WebView wv, String name);    // 在 loadUrl 之前调用：supported() 时 WebViewCompat.setProfile(wv, name)（不存在先 getOrCreateProfile）；不支持时什么都不做
  public android.webkit.CookieManager cookies(String name); // 该容器的 CookieManager；不支持时返回全局
  public void release(String name);              // 标签关闭时调：keep==false 且没有其他标签在用 → ProfileStore.deleteProfile；DEFAULT 永不删除
  public void setKeep(String name, boolean keep); public void rename(String name, String label);
  public JSONArray list();                       // [{name,label,color,keep,tabs(由调用方填)}]
  public JSONObject meta(String name);
}
public final class HistoryStore {               // history.jsonl 追加写，最多保留 5000 条
  public HistoryStore(File appsDir);
  public void add(String url, String title, String container); // 同一 URL 30 秒内重复只更新标题；about:/data:/javascript: 不记
  public JSONArray recent(int limit, int offset);              // 新的在前 [{url,title,container,t}]
  public JSONArray search(String q, int limit);                // url/title 不区分大小写包含
  public int delete(String urlOrPrefix); public void clear(); public void clearSince(long ms);
}
```
线程安全；文件 IO 不在主线程做（add 内部用单线程 executor）。

## 2. FloatingApps.java —— M2
- 每个 `Tab` 加字段 `String container`。**新建标签 = 新容器**：`newTab()` 调 `containers.newContainer()` 并在第一次 loadUrl 前 `containers.apply(wv, name)`。长按「+」或菜单可选「在当前容器打开 / 共享容器(Default)」。`GM_openInTab`、`target=_blank` 打开的标签继承来源标签的容器。
- 标签栏/标签列表：每个标签显示容器颜色条 + 标签名（参考 cookie-box 的独立标签徽标）；标签菜单可「保留此容器（关闭后不删数据）」「重命名容器」。关闭标签调 `containers.release`。
- 不支持 MULTI_PROFILE 时 toast 一次「系统 WebView 版本太低，标签共享登录」，其余照常。
- Cookie 相关（beta.22 的 cookie get/set/clear、🍪 面板）改成按当前标签的容器 `containers.cookies(tab.container)`。
- 历史：`onPageFinished` / 标题更新时 `history.add(url,title,container)`。顶栏菜单加「🕘 历史」面板：搜索框、按天分组列表、点开（当前标签）、长按（新标签/删除）、清除（全部/最近一小时/今天）。
- 给 M3 的方法（public 或包内可见）：`Tab newTabInContainer(String url, String container /*null=新容器*/, boolean background)`、`String containerOf(Tab)`、`ContainerManager containers()`、`HistoryStore history()`、`List<Tab> tabs()`（已有就复用）。

## 3. BrowserAgent.java —— M3
新增/改造 action（参数都是 JSON 字段）：
- `snapshot`：调页面里的 `__pimetAgent.snapshot({scope:'full'|'viewport', maxChars})`（M4 在 agent.js 实现），返回 `{text, url, title, refs}`；text 是缩进的语义树，每个可交互元素一行 `- button "登录" [ref=12]`，同时记录 ref→loc（稳定 CSS 选择器）。
- `click/fill/type/hover/scroll_into_view/select` 的目标统一接受 `@N`（最近一次快照的 ref）、CSS 选择器、`text=...`、或 `[x,y]`；`@N` 失效时自动用 loc 重试，再失败返回 `{error:"unknown_ref", hint:"重新 snapshot"}`。
- 每个动作后自动等待（导航→页面加载完成；否则网络空闲或 DOM 稳定 300ms，上限 timeout 默认 10s），并在结果里附带 `{url,title,changed:true/false}`，不要求 Pi 额外调用 wait。
- `batch`：`{steps:[{action:"click",target:"@3"},{action:"fill",target:"@5",value:"..."},{action:"press",key:"Enter"},{action:"snapshot",scope:"viewport"}], stopOnError:true}` 一次执行多步，返回每步简短结果；这是 Pi 的首选方式。
- `run` 已有：把 `snapshot/click/fill/press/waitFor/scrollUntil/extract` 作为脚本里的 helper（由 M4 在 agent.js 暴露为 `b.*`），保证一次 run 可以完成「打开→操作→提取→返回」。
- 容器/标签：`tab_new {url, container?:"new"|"same"|"Default"|<name>, background?}`（Pi 默认 `"new"` 在自己的后台标签里干活，不打扰用户当前标签），`tabs`（返回 id、url、title、container、active），`tab_switch/tab_close`，`containers`（列表），`container_keep {name,keep}`。
- 历史：`history {q?, limit?, offset?}`、`history_delete {url}`、`history_clear {since_ms?}`。
- cookie 相关 action 增加可选 `container`（默认当前标签的容器）。
- status 里加 `container`、`containers_supported`。

## 4. pimet-browser.ts / SKILL.md / agent.js —— M4
- agent.js（注入页面）：实现 `__pimetAgent.snapshot(opts)`：遍历 DOM（含同源 iframe、shadow DOM），输出精简语义树（role + 可读名 + 关键状态 value/checked/disabled/href），只给可交互/有文本的节点编号 `[ref=N]`，给元素打 `data-pimet-ref=N`，同一元素多次快照尽量保持同一编号；生成 loc（id > data-testid > aria-label > 短路径 nth-of-type）。超过 maxChars（默认 12000）时折叠长列表并提示「…还有 N 项」。还有 `resolve(target)`（@N / CSS / text= / 坐标）、`settle(ms)`（MutationObserver 稳定检测）。`run` 里暴露 `b.snapshot/b.click/b.fill/b.press/b.waitFor/b.scrollUntil/b.extract`。
- pimet-browser.ts：browser 工具加入 snapshot、batch、tab_new(container)、tabs、containers、container_keep、history*、各 action 的 target 说明；工具描述里写清推荐流程：`tab_new(container:"new") → snapshot → batch(@ref 操作…, 最后 snapshot viewport) → 需要提取时用 run 一次完成`。结果原样显示，长快照不截断到 12000 字以内。
- SKILL.md：重写浏览器章节为 ego 式工作流（快照+ref、batch 优先、一次 run 完成整个流程、ref 失效就重新快照、Pi 在自己的容器标签里工作、需要用户登录态时用 `container:"same"` 或用户指定的容器）、容器和历史的用法。
