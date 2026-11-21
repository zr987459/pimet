---
name: pimet-browser
description: 用 PiMet 手机上的「Pi 浏览器」完成网页任务，并定制它。操控网页（打开、读正文、搜索、提取、填表）时先读这里：用 read / openAndRead 读正文，用 batch(settle:false) 或 run 一次完成多步，在自己的容器标签里工作，做任务前先查 sites/ 工具箱，成功后沉淀成工具。需要改 ui.json 界面/规则/面板或写油猴脚本时按需读取同目录下的 customize.md。
---

# Pi 浏览器

PiMet 内置的「Pi 浏览器」是独立全屏 Android WebView，用户实时可见并可随时接手。核心原则：**少轮次、少 token、不打断用户**。

## ⚡ 场景速查决策表

| 场景 | 最优做法 | 为什么 |
|---|---|---|
| 读文章 / 文档 / 查资料 | `action: "read"` 或 `run` 里 `b.openAndRead(url)` | 自动去噪提取正文与关键链接，比 12000 字 `full` snapshot 快且省 |
| 搜网页 / 查 GitHub | 先 `action: "tools"` 复用 `_common/` 或站点工具 | `b.tool("_common/search-web", {q})` / `b.tool("github/repo-info", ...)` |
| 连续填表 / 提交 | `batch`（中间步加 `settle: false`）或 `b.fillForm(...)` | 跳过中间稳定等待，一次往返完成多步输入与提交 |
| 多步交互 / 滚动抓取 | `run` 组合 `b.clickAny` / `b.dismissPopups` / `b.waitGone` / `b.extract` / `b.scrollUntil` | 一次跑完导航→交互→提取→`return` 精简 JSON |
| 单步探索未知页面 | `snapshot(scope: "viewport")` | 只看当前屏语义树与 `[ref=N]`，最省最快 |

## 操控网页标准流程

1. **先查工具箱**：`browser` action=`tools`（加 `site` 参数可看对应 `notes.md`），优先调用现成 `b.tool("站点/工具名", args)`。
2. **后台容器标签干活**：`tab_new(url, container: "new")` 开独立容器后台标签（默认 `background: true`），记下返回的 `tab` id，后续都带上 `tab`。
3. **读或看页面**：读内容用 `read`；要交互用 `snapshot(scope: "viewport")`。每个动作自带等待（`settle` 默认 `true`），无需额外 `wait`。
4. **批量提交**：`batch` 一次传多步，中间填表/点选加 `"settle": false` 提速，最后一步放 `{action: "snapshot", scope: "viewport"}`：
   ```json
   {"action": "batch", "tab": "t3", "steps": [
     {"action": "fill", "target": "@4", "value": "alice", "settle": false},
     {"action": "fill", "target": "@6", "value": "secret", "settle": false},
     {"action": "click", "target": "@7"},
     {"action": "snapshot", "scope": "viewport"}
   ]}
   ```
5. **成功后沉淀**：通用流程写成 `/root/pimet-browser/sites/<站点>/tools/<工具名>.mjs`（第一行写 `// 说明与参数`），踩坑记录写进 `notes.md`。
6. **人工接管红线**：遇到验证码、滑块、支付、登录确认、短信验证，或收到「用户已叫停」/`user_takeover`，**立即停止并告诉用户进展**，绝不代点支付或重试绕过。

## 目标 target 与快照

快照输出缩进语义树（含同源 iframe 与开放 shadow DOM），元素带 `[ref=N]`；顶部提示 `⚠ 当前有弹窗/遮罩` 时先处理弹窗（或调 `b.dismissPopups()`）。

| target 写法 | 说明 |
|---|---|
| `@12` | 最近一次快照里的 `[ref=12]`（首选；若报 `unknown_ref` 立即重新 `snapshot`） |
| `#kw` / `.btn` / `a >>> b` | CSS 选择器；跨 shadow DOM / 同源 iframe 用 `宿主 >>> 内部` |
| `text=登录` | 按包含的文字匹配（优先视口内可交互元素） |
| `[120, 340]` | 视口 CSS 像素坐标（兜底手段） |

## `run` 代码模式

`action: "run"` 执行 async 函数体 `code`，可用 `b` 和 `ctx`（`ctx.signal`、`ctx.tab`），默认超时 120s（`timeout` 最大 600s）。输出总预算约 4 万字（错误/网址/最后 5 步必留，日志留尾 8000 字），**只 `return` 需要的字段**。

| 方法 | 说明 |
|---|---|
| `b.open(url)` | 打开网址并等加载完，返回 `{url, title, loaded?, http_status?, load_error?}` |
| `b.read(opts?)` | 去噪提取干净正文，返回 `{ok, url, title, text, length, truncated, links}`（`maxChars`, `selector`, `includeLinks`） |
| `b.openAndRead(url, opts?)` | 打开 URL → 自动 `dismissPopups()`（可设 `false`）→ 返回 `b.read(opts)` 结果 |
| `b.dismissPopups()` | 智能关闭挡路弹窗 / Cookie 栏 / 登录遮罩（最多 3 轮防误伤），返回 `{closed, items}` |
| `b.snapshot({scope})` | 语义树快照（`scope: "viewport" | "full"`） |
| `b.click(target, opts?)` | 点击目标（普通文字按包含文本找）；`opts: {js?, settle?, timeout?}` |
| `b.clickAny(targets, opts?)` | 探测候选目标数组，点第一个可见元素；真实触摸失败自动降级 `js:true` 重试一次 |
| `b.fill(target, text, opts?)` | 输入（别名 `b.type`），`opts: {submit?, clear?, settle?}` |
| `b.fillForm(fields, opts?)` | 批量填表：`[{target,value,kind?}]` 或 `{[target]:value}`，中间步自动 `settle:false`，`opts.submit` 支持 `'Enter'` 或目标 |
| `b.select(target, value, opts?)` / `b.press(key, opts?)` / `b.hover(target, opts?)` | 下拉选择 / 按键（`Enter`/`Escape`/`Tab`…）/ 悬停 |
| `b.scroll(dirOrRef, amount?, opts?)` / `b.scrollIntoView(target)` | 滚动页面或滚到元素 |
| `b.scrollUntil(targetOrFn, {max, direction})` | 边滚边找直到出现（返回 `true`/`false`） |
| `b.waitFor(selOrFn, timeout=15000)` | 等元素可见或函数返回真值，超时返回 `false` |
| `b.waitGone({text?, selector?, timeout?})` | 轮询等待 loading 文字或骨架屏消失，消失返回 `true` |
| `b.waitIdle(quietMs=350, maxMs=5000)` | 等待 DOM 变更与网络请求趋于稳定 |
| `b.extract(sel, fields?, {limit})` | 批量提取（`子选择器`、`子选择器@属性`、`@属性`、`""` 自身文字） |
| `b.batch(steps, {stopOnError?})` | 一次执行多步，有失败步且未禁用时抛错 |
| `b.js(fn, ...args)` | 在页面里执行函数（`args` 经 JSON 传入），**禁止直接引用 `run` 外部变量** |
| `b.tool("站点/工具名", args)` | 调用 `/root/pimet-browser/sites/<站点>/tools/<名>.mjs` |
| `b.newTab(url, {container, profile})` / `b.containerProfile(opts)` / `b.tab(id)` / `b.closeTab()` / `b.tabs()` | 标签页与容器指纹隔离切换与管理 |
| `b.back()` / `b.forward()` / `b.reload()` / `b.url()` / `b.screenshot()` / `b.sleep(ms)` / `b.log(...)` | 导航、截图（最多 3 张）、等待、日志 |
| `b.find(text)` / `b.console(max)` / `b.scripts()` / `b.inject(js, persist?)` / `b.ui.*` / `b.call(action, p)` | 调试、油猴、ui.json 读写与任意 action 透传 |

示例 1 — 一行读文章 / 搜索结果：
```js
const t = await b.newTab();
try {
  return await t.openAndRead("https://example.com/post", { maxChars: 8000 });
} finally { await t.closeTab(); }
```

示例 2 — 批量填表 + 容错点击 + 等加载消失 + 提取：
```js
await b.open("https://example.com/form");
await b.dismissPopups();
await b.fillForm({ "#name": "Pi", "#query": "AI" }, { submit: "Enter" });
await b.clickAny(["text=确认", "button[type=submit]", ".submit-btn"]);
await b.waitGone({ selector: ".loading-spinner", text: "加载中" });
await b.waitIdle(300, 4000);
return await b.extract(".result-item", { title: "h3", url: "a@href" }, { limit: 10 });
```

## 常见坑与出错读轨迹

- **作用域隔离**：`b.js(() => q)` 看不到外部变量 `q`，必须写成 `b.js((q) => ..., q)`；参数与返回值都须可 JSON 序列化。
- **`b.js` 内跳页会丢返回值**：跳转用 `b.click` 或取出 `href` 后 `b.open`。
- **后台窗口限制**：Pi 浏览器不在前台时，后台标签只能用 `js: true` 点击，且无法截图。
- **读错轨迹**：`run` 失败会返回 `出错位置（run 第 N 行 或 工具.mjs 第 N 行）`、`当前网址` 和 `最后 5 步`（`#序号 动作 · 耗时 · ✓/✗`）；从当前网址修复后继续即可。
- **致命中断**：遇到 `⛔ 已叫停` 或 `用户正在操作标签（user_takeover）`，所有 `b.*` 会立即中止；立刻停手并汇报当前进度。

## 容器、指纹隔离、标签、历史与工具规范

- **容器登录态与指纹隔离（防风控）**：
  - `tab_new` 默认 `container: "new"`（独立 Cookie/Storage 与独立 `auto` 设备指纹，自动去除 `; wv`、`X-Requested-With` 与全局 `window.PiMet*` 桥暴露）。
  - 需要特定国家/地区环境时，在建标签时直接传入 `profile`（推荐建前就定好环境，避免旧会话污染）：
    - 例：`tab_new(url, container: "new", profile: "ca-en")`（`ca-en` / `us-en` / `us-west` / `uk-en` / `au-en` / `jp-ja` / `cn-zh` / `auto` / `native`），或对象 `{preset, timezone, locale, languages, device, desktop, seed, webrtc}`。
    - `run` 里可用 `await b.newTab(url, { profile: "ca-en" })`。
  - 查询/修改现有容器环境用 `container_profile {name?, profile?, randomize?, reload?}`（或 `b.containerProfile(...)`）；若容器已有打开页面，改完环境会返回 `warning`，防风控场景强烈建议直接 `tab_new` 新建容器。
  - `Default` 容器始终为 `native`（不伪装，保持原有登录态）；需要复用用户当前标签登录态时用 `container: "same"`；用 `container_keep {name, keep: true}` 保留容器（先征得用户同意）。
- **不打断用户**：不传 `tab` 时只操作 Pi 标签；仅当用户明确要求操作当前页才传 `tab: "user"`；不要擅自 `show` / `tab_switch`。
- **历史与下载**：`history {q?, limit?, offset?}` 查最近访问；`downloads` 查下载进度；`history_delete` / `history_clear` / `clear_data` / `script_remove` / `ui_reset` **须先征得用户同意**。
- **站点工具规范**：文件路径 `/root/pimet-browser/sites/<站点>/tools/<工具名>.mjs`（仅用 `A-Za-z0-9._-`），**第一行写 `// 一句话说明与参数`**，默认导出 `async (b, args) => { ... }`。

## 定制浏览器与油猴脚本

需要修改 `ui.json`（主题、工具栏、菜单、悬浮球、主页、搜索引擎、站点规则、广告拦截/重定向、自定义面板）、编写/调试油猴（Tampermonkey）用户脚本、制作 `pages/<id>/` 小工具或使用旧版 `config.json` 时，请按需读取同目录下的 **`customize.md`**（绝对路径：`/root/.pi/agent/skills/pimet-browser/customize.md`）。

## 做完之后

用一两句话直接交付结果（提取的信息、沉淀的工具/笔记路径）；不要贴冗长源码。
