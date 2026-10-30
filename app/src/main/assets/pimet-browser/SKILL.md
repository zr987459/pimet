---
name: pimet-browser
description: 用 PiMet 手机上的「Pi 浏览器」完成网页任务，并定制它。操控网页（打开、搜索、提取、填表）时先读这里：在自己的容器标签里工作，语义快照 + @ref，多步用 batch 或 run 一次完成，做任务前先查 sites/ 里的站点工具和经验，成功后沉淀成工具。也用于查/清浏览历史、管理容器（独立登录态），以及通过 ui.json 自定义浏览器的一切（主题、工具栏、菜单、悬浮球、主页、搜索引擎、站点规则、广告拦截、重定向、自定义面板）。也用于写/安装/调试油猴（Tampermonkey）用户脚本（GM API、菜单命令、console），改工具栏按钮、配色、首页、UA、桌面模式、缩放、Cookie，制作网页小工具。
---

# Pi 浏览器

用户手机上的 PiMet 有一个「Pi 浏览器」（工作台悬浮球 → 🌐）：独立的全屏浏览器窗口，界面像手机 Chrome（⌂ · 地址栏 · 🧩 扩展 · 标签数 · ⋮ 菜单）。它有两种用法：

1. **你来操控它**：用 `browser` 工具完成网页任务（见下面「操控网页」）。
2. **你来改造它**：它的界面和功能都由容器里的 `/root/pimet-browser/` 决定，你可以随时修改（见「定制」）。

## 操控网页

思路和 ego-browser 一样：**少轮次、少 token**。先拿一次语义快照，元素带编号 `[ref=N]`；然后用 `@N` 把多步操作一次提交（`batch`），或者把「打开→操作→提取→返回」写成一段 `run` 代码一次跑完。每个动作自带等待（导航等加载完，否则等网络空闲 / DOM 稳定），结果里带 `url`、`title`、`changed`（页面是否变化），**不需要另外调 `wait`**。

### 工作流程

1. **先查经验**：`browser` action=`tools`，看 `/root/pimet-browser/sites/` 里有没有这个网站的现成工具和 notes.md（`site` 参数直接显示该站点的 notes.md）。有现成工具就直接 `b.tool("站点/工具名", args)`。
2. **在自己的标签里干活**：`tab_new`（`url`，`container: "new"` 是默认）开一个后台标签，独立容器、不打扰用户当前标签；记下返回的 `tab` id，之后每次调用都带上 `tab`。需要用户的登录态时见下面「容器」。
3. **看页面**：`snapshot`（`scope: "viewport"` 只要当前屏，最省；默认 `full` 整页，超过 12000 字会折叠长列表并提示「…还有 N 项」）。
4. **批量操作**：`batch`，一次提交多步，最后一步放 `{action: "snapshot", scope: "viewport"}` 看结果：

```json
{"action": "batch", "tab": "t3", "steps": [
  {"action": "fill", "target": "@5", "value": "机械键盘"},
  {"action": "press", "key": "Enter"},
  {"action": "snapshot", "scope": "viewport"}
]}
```

   默认某步失败就停（`stopOnError: false` 可继续），结果逐步列出 `#序号 动作 ✓/✗ 结果`。
5. **要提取数据、流程长或要循环时用 `run`**：一段代码完成整个流程，只 `return` 需要的字段（见下）。
6. **成功后沉淀**（重要）：把稳定的步骤写成 `sites/<站点>/tools/<工具名>.mjs`，把踩过的坑写进 `sites/<站点>/notes.md`，最后告诉用户你沉淀了什么。
7. **需要用户本人的时候停下**：验证码、滑块、支付、登录确认、短信验证码、风险提示——停止自动化，告诉用户在浏览器窗口里完成，完成后再继续。不要替用户点「确认支付」之类的按钮。

### 快照与目标

快照是缩进的语义树：

```
网址: https://example.com/search?q=键盘
标题: 搜索结果
滚动: 0/5320 (视口 412x780，只含视口内)

- navigation "主导航":
  - link "首页" href=/ [ref=1]
  - link "购物车" href=/cart [ref=2]
- main:
  - heading "键盘" [level=1] [ref=3]
  - form:
    - searchbox "搜索" value="键盘" [ref=4]
    - button "搜索" [ref=5]
  - list:
    - listitem:
      - link "Cherry MX 机械键盘" href=/p/1 [ref=6]
      - text: ¥399 [ref=7]
    - …还有 18 项
```

- 每个可交互元素一行：`角色 "可读名" 状态 [ref=N]`；状态有 `value="…"`、`[checked]`、`[expanded]`、`[disabled]`、`href=…`、`[被遮挡]`、`[iframe]`。标题、正文文字、表格行（`row: a | b`）也会列出，有文字的块也带编号。
- 同一元素多次快照尽量保持同一编号；页面重新渲染后，旧编号会按记录的 CSS 定位自动找回，找不回才报 `unknown_ref`——这时**重新 snapshot**，不要反复重试旧编号。
- 同源 iframe 和开放的 shadow DOM 会展开；跨源 iframe 只标出网址，需要的话单独打开它。
- 顶部 `⚠ 当前有弹窗/遮罩` 时先处理弹窗。

**目标（target）** 在 `click / fill / type / select / hover / scroll_into_view` 和 batch 步骤里通用：

| 写法 | 说明 |
|---|---|
| `@12` | 最近一次快照里的 `[ref=12]`（首选） |
| `#kw`、`form button[type=submit]` | CSS 选择器；跨 shadow / iframe 用 `宿主选择器 >>> 内部选择器` |
| `text=登录` | 按包含的文字找（优先视口内、可交互的） |
| `[120, 340]` | 视口坐标（CSS 像素），最后的手段 |

### run：代码模式

`browser` action=`run`，`code` 是一段 async 函数体（可以 `await`），有两个变量：`b`（浏览器）和 `ctx`（`ctx.signal` 中止信号、`ctx.tab`）。用 `return` 返回结果（对象会转成 JSON），`b.log()` 的输出也会返回给你。默认超时 120 秒，`timeout` 参数（秒）最大 600。

输出总长约 4 万字符，按优先级分配：出错信息、当前网址、最后 5 步轨迹一定保留；日志只留**最后 8000 字符**；返回值太长时改用紧凑 JSON，再长就截断并注明。所以：**只 return 需要的字段**，大列表先 `slice`。

| 方法 | 说明 |
|---|---|
| `b.open(url)` | 打开网址（或搜索词），等加载完，返回 `{url, title, loaded?, http_status?, load_error?}` |
| `b.snapshot({scope})` | 语义树快照文本（`scope: "viewport"` 只要当前屏） |
| `b.click(目标, {js})` | 目标见上表；其它普通字符串按包含的文字找；`{js:true}` 强制 JS 点击 |
| `b.fill(目标, text, {submit, clear})` | 输入文字（别名 `b.type`）；`submit:true` 输入后提交 |
| `b.select(目标, value)` / `b.press(key)` / `b.hover(目标)` | 下拉框选择 / 按键（Enter Tab Escape ArrowDown…）/ 悬停 |
| `b.scroll("down" 或 目标, 屏数)` / `b.scrollIntoView(目标)` | 滚动页面 / 滚到某个元素 |
| `b.scrollUntil(选择器 或 "text=…" 或 函数, {max, direction})` | 边滚边找，出现返回 `true`，滚到底仍没有返回 `false`（无限滚动列表、懒加载） |
| `b.waitFor(选择器 或 函数, 超时=15000)` | 等元素出现**且可见**，或等页面里的函数返回真值；返回真值，超时返回 `false`（不抛错） |
| `b.extract(选择器, {字段: 子选择器}, {limit})` | 批量提取：`"子选择器"` 取文字、`"子选择器@属性"` 取属性、`"@href"` 取自身属性、`""` 取自身文字；不给字段返回每项文字。默认最多 50 项 |
| `b.batch(steps)` | 同 batch 动作，返回每步结果；有失败步骤时抛错 |
| `b.js(fn, ...args)` | `fn` 在页面里执行（可 async），`args` 经 JSON 传进去，返回值原样带回。复杂提取用它 |
| `b.back()` `b.forward()` `b.reload()` `b.url()` | 导航 / 当前网址 |
| `b.wait(毫秒)` / `b.wait("文字", 超时)` | 固定等待 / 等文字出现（一般用不到，动作自带等待） |
| `b.screenshot()` | 截图并附在结果里（最多 3 张） |
| `b.sleep(ms)` / `b.log(...)` | 等待 / 记录输出 |
| `b.tool("站点/工具名", args)` | 调用站点工具 |
| `await b.newTab(url, {container})` / `b.tab(id)` / `b.closeTab()` | 新标签（默认新容器）/ 换一个标签操作 / 关掉当前操作的 Pi 标签 |
| `b.find(文字)` / `b.console(max)` / `b.scripts()` / `b.inject(js, …)` | 查找文字数 / 页面 console / 用户脚本（见「油猴脚本」） |
| `b.call(action, 参数)` | 发送任意单步 action（参数与 browser 工具相同） |

例子：一次完成「打开 → 搜索 → 等结果 → 提取前 5 条」

```js
const t = await b.newTab("https://m.example.com");          // 自己的后台标签（新容器）
await t.fill("input[type=search]", "机械键盘", { submit: true }); // 动作自带等待
if (!(await t.waitFor(".product", 15000))) return await t.snapshot({ scope: "viewport" }); // 没等到就把页面交回来分析
return await t.extract(".product", { name: ".title", price: ".price", url: "a@href" }, { limit: 5 });
```

（选择器只是示意，真实页面先 `snapshot` 探索。能直接拼出结果页网址时直接 `open`，比找搜索框稳。）

### 常见坑

- 返回「用户正在操作标签 tX」（user_takeover）或「用户已叫停」：立即停下，告诉用户做到哪一步，等用户允许再继续；不要重试、不要换标签绕过。
- **`b.js` / `b.extract` 在网页里运行，看不到 run 里的变量**。`const q = "键盘"; b.js(() => q)` 会报 `q is not defined`；要用的值作为参数传：`b.js((q) => …, q)`。参数和返回值都要能转 JSON：返回 DOM 元素、函数、循环引用都不行。
- **字符串脚本**（eval / `b.js("…")`）：写成表达式（`"document.title"`），或带 `return` 的语句体。语法错误在发出前就会报出。
- **在 `b.js` 里触发跳转会丢掉结果**。要点链接跳转用 `b.click`，或 `b.js` 只取出 `href` 再 `b.open`。
- **`@N` 只对最近的快照可靠**：页面大变（跳转、换页）后先重新 snapshot。普通文字（如 `"登录"`）在 `b.click` 里按文字找；在 `target` 参数里要写成 `text=登录`。
- **超时**：单步默认 20 秒（`timeout` 毫秒，最大 120000）；动作后的自动等待上限约 10 秒；`b.waitFor` 默认 15 秒；整段 run 默认 120 秒（最大 600 秒）。超时或中止时会通知宿主取消还在进行的操作。
- **「标签正忙」**：上一个操作还没结束（比如上次 run 超时了）。稍等再试，不要连发。
- **致命错误**（用户叫停、令牌无效、连不上宿主）：之后所有 `b.*` 立即抛同一个错误，`try/catch` 也拦不住，run 马上结束，结果首行是「⛔ 已叫停/已终止，做到第 N 步」。停下，告诉用户进展。
- 单步变更操作（click/fill…）不带 batch 时会附一个精简快照（约 2500 字）；要看全页时再 `snapshot`。

### 出错时怎么读轨迹

run 出错时返回类似：

```
出错: ref 已失效（unknown_ref）：页面已变化，请重新 snapshot 拿新的编号
出错位置: run 代码第 4 行
当前网址: https://example.com/list
最后 3 步（共 3 步）:
#1 open https://example.com · 1830ms · ✓（run 代码第 1 行）
#2 fill @5 文字4字 +提交 · 920ms · ✓（run 代码第 2 行）
#3 click @12 · 40ms · ✗（run 代码第 4 行） ref 已失效…
```

- `出错位置` 是你代码的行号（从 code 第 1 行算起；出错在站点工具里时显示 `站点/工具.mjs 第 N 行`）。
- 每步是 `#序号 动作 参数摘要 · 耗时 · 成败（行号）`；输入内容只记字数，不记原文。
- 最后一步 ✗ 就是失败点；某步耗时异常长（接近超时）通常是在等加载；全部 ✓ 但仍出错，是你自己的代码出了问题，看「出错位置」。
- 修正后只重跑失败点之后的部分。页面停在「当前网址」，可以直接从那里继续。

### 站点工具与经验：/root/pimet-browser/sites/

```
/root/pimet-browser/sites/
  jd/
    notes.md            经验笔记：页面结构、要等的元素、弹窗、坑
    tools/
      search.mjs        可复用工具
```

- 站点名、工具名只用 `A-Z a-z 0-9 . _ -`（如 `jd`、`bilibili`、`github.com`）。
- 工具文件**必须用 `.mjs`**（`.js` 会被 Node 当成 CommonJS，`export` 会报错）。
- **第一行**写 `// 一句话说明`（`tools` 列表里显示的就是它），说明里写清参数。
- 导出 `default`（或导出的第一个函数），签名 `async (b, args)`，`b` 与 run 里的完全一样；返回结果。改完立即生效（按修改时间重新加载）。
- 工具里提取数据用 `b.extract` 或 `b.js((参数) => {...}, 参数)`，等元素用 `b.waitFor`，长列表用 `b.scrollUntil`。
- 工具里可以调用别的工具：`await b.tool("jd/login-check")`。

```js
// 京东搜索：args {q: 关键词, n: 条数=5}，返回 [{name, price, url}]
export default async function (b, { q, n = 5 }) {
  await b.open("https://so.m.jd.com/ware/search.action?keyword=" + encodeURIComponent(q));
  await b.waitFor("[class*=price]", 15000);
  return await b.js((n) => [...document.querySelectorAll("...")].slice(0, n).map((e) => ({ /* ... */ })), n);
}
```

notes.md 写法：短条目，每条是一个可执行的结论，例如「搜索结果页可以直接拼：so.m.jd.com/ware/search.action?keyword=…」「首次打开会弹定位权限框，点『暂不』」「价格在 .price 里，带 ¥ 和空格」。过时的条目要改掉，不要越堆越长。

### 标签页与容器

- 浏览器有多个标签：用户自己的标签（🌐）和 **Pi 的标签（🤖）**。Pi 在后台标签操作时，顶栏的标签数按钮会闪烁，地址栏下方出现「🤖 Pi 正在后台标签操作」提示条。**你的操作从不改变用户正在看的页面**。
- **容器**：每个容器是一套独立的 Cookie / 本地存储 / 缓存（像多开）。**新建标签默认就是新容器**，所以在新容器里网站都是未登录状态；标签栏用颜色条和容器名区分。
  - `tab_new` 的 `container`：`"new"`（默认，新容器，干净、不碰用户的登录）｜`"same"`（沿用用户当前标签的容器，**需要用户的登录态时用它**）｜`"Default"`（共享容器）｜容器名（用户指定的某个容器，`containers` 可列出）。`background` 默认 true（后台打开）。
  - 用户说「用我已登录的账号」「在我现在这个号上」时用 `"same"` 或用户点名的容器；不确定时先问。不要在用户的容器里做他没要求的事。
  - 容器在最后一个标签关闭时自动删除（连同数据）；需要保留登录态以便下次再用，`container_keep {name, keep: true}`（先征得用户同意）。
  - 系统 WebView 太旧不支持多容器时，所有标签共享登录（`status` 里 `containers_supported: false`）。
  - Cookie 相关动作（`cookies`）默认作用于当前标签的容器，可传 `container` 指定。
- 动作：`tabs`（id、网址、标题、容器、是否当前）、`tab_new`、`tab_switch`（把某个标签切到前台给用户看，会打断用户，先问）、`tab_close`（只关你自己开的标签，做完任务记得关）、`containers`、`container_keep`。
- 只有用户明确要你操作「我现在这个页面」时，才传 `tab: "user"`（run 里 `b.tab("user")`）。同时做两件事时可以开两个标签并行。
- 用户可以点提示条的「查看」把 Pi 标签切到前台自己操作，也可以点「⏹ 停止」。**任何调用返回「用户已叫停」时，立刻停下所有浏览器操作，告诉用户做到哪一步了，等用户点「▶ 允许 Pi」后再继续**。
- 浏览器窗口在前台时，后台标签里点击、截图都能正常用；窗口不在前台（用户在工作台或别的 App）时只能用 JS 点击，也不能截图。
- **不要打断用户**：不要擅自 `show` / `tab_switch`；需要用户看时先问，或建议用户分屏同时看工作台和浏览器。
- 下载：网页里点下载链接会存到手机「下载/PiMet/」（带当前登录 Cookie）；用 `downloads` 查看最近下载和进度。

### 历史记录

- `history {q?, limit?, offset?}`：最近访问（新的在前，每条 网址、标题、容器、时间）；`q` 按网址或标题搜索（不区分大小写）。用户问「我昨天看的那个页面」「之前打开过的某某」时先搜历史，再 `tab_new` 打开。
- `history_delete {url}`：删除某个网址（或以它开头的所有记录）；`history_clear {since_ms?}`：清除全部，或只清除某个时间之后的（如最近一小时：`Date.now() - 3600000`）。**删除和清除前先征得用户同意**。
- 用户也可以在顶栏菜单「🕘 历史」里自己查看、搜索、清除。

## 定制

```
/root/pimet-browser/
  config.json          工具栏按钮、配色、首页
  scripts/<id>.user.js 用户脚本（油猴脚本），按网址自动注入网页；旧的 scripts/*.js 也兼容
  scripts.json         脚本启停状态和安装来源（由 🧩 脚本管理 / script_* 动作维护，不要手改）
  script-data/<id>.json  脚本用 GM_setValue 存的值
  pages/<id>/index.html  你做的页面/小工具（可多个，可用 window.pimet 原生接口）
  pages/<id>/app.json    可选：{"name":"记账本","icon":"💰","description":"一句话"}
  home.html            可选：自定义首页（不存在时显示内置首页 + pages 列表）
  sites/<站点>/        站点经验 notes.md 和可复用工具 tools/*.mjs
```

（`sites/` 见上文「站点工具与经验」。）

改完后调用 `browser` 工具的 `reload_ui` 让配置立即生效；页面和脚本让用户点 ⋮ → 刷新（或你用 `browser` 的 `reload`）即可。

### config.json

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

### 油猴脚本（用户脚本）

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

### 自定义浏览器（ui.json）

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

在 run 里也能用：`await b.ui.get("/theme")`、`await b.ui.set(pointer, value)`、`await b.ui.patch({...})`、`await b.ui.backups()`、`await b.ui.restore(name)`、`await b.runAction({...})`。

### 旧设置类 action（config.json）

新功能请用上面的 ui.json；下面这些动作仍可用。


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

### 自定义页面 pages/<id>/

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
- 想让页面出现在 🧩 扩展菜单里，就在 config.json 加一个 `open: "pages/<id>/index.html"` 按钮。Pi 做的页面也会自动出现在首页的快捷方式里。

## 做完之后

用一两句话告诉用户结果：网页任务给出要的信息和沉淀了哪些工具；定制任务说明改了什么、在哪里看（🧩 扩展菜单里的新按钮 / 点 ⌂ 回首页 / ⋮ → 刷新），不用贴代码。
