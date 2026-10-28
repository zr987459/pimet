---
name: pimet-browser
description: 用 PiMet 手机上的「Pi 浏览器」完成网页任务，并定制它。操控网页（打开、搜索、提取、填表）时先读这里：多步任务用 browser 的 run 代码模式，做任务前先查 sites/ 里的站点工具和经验，成功后沉淀成工具。也用于改工具栏按钮、配色、首页，写油猴风格用户脚本，制作网页小工具。
---

# Pi 浏览器

用户手机上的 PiMet 有一个「Pi 浏览器」（工作台悬浮球 → 🌐）：独立的全屏浏览器窗口，界面像手机 Chrome（⌂ · 地址栏 · 🧩 扩展 · 标签数 · ⋮ 菜单）。它有两种用法：

1. **你来操控它**：用 `browser` 工具完成网页任务（见下面「操控网页」）。
2. **你来改造它**：它的界面和功能都由容器里的 `/root/pimet-browser/` 决定，你可以随时修改（见「定制」）。

## 操控网页

### 工作流程

1. **先查经验**：`browser` action=`tools`，看 `/root/pimet-browser/sites/` 里有没有这个网站的现成工具和 notes.md（`site` 参数可以直接显示该站点的 notes.md）。有现成工具就直接 `b.tool("站点/工具名", args)`。
2. **单步探索**：不熟悉的页面先用单步 action 看清楚：`open` → `snapshot`（`mode: "interactive"` 只要元素列表，更省）→ `click`/`type`……
3. **多步执行用 `run`**：摸清步骤后，把整个流程写成一段代码一次跑完，只返回你需要的结果。比一步步调工具快得多，也省上下文。
4. **成功后沉淀**（重要）：一个网站任务做成功后，
   - 把稳定的步骤写成 `sites/<站点>/tools/<工具名>.mjs`，下次直接调用；
   - 把踩过的坑写进 `sites/<站点>/notes.md`（选择器、要等待的元素、弹窗、反爬表现、哪些网址能直接拼出来……）；
   - 最后告诉用户你沉淀了什么工具。
5. **需要用户本人的时候停下**：遇到验证码、滑块、支付、登录确认、短信验证码、风险提示，停止自动化，告诉用户在浏览器窗口里完成，完成后再继续。不要替用户点「确认支付」之类的按钮。

### run：代码模式

`browser` action=`run`，`code` 是一段 async 函数体（可以 `await`），有两个变量：`b`（浏览器）和 `ctx`（`ctx.signal` 中止信号、`ctx.tab`）。用 `return` 返回结果（对象会转成 JSON），`b.log()` 的输出也会返回给你。默认超时 120 秒，`timeout` 参数（秒）最大 600。

输出总长约 4 万字符，按优先级分配：出错信息、当前网址、最后 5 步轨迹一定保留；日志只留**最后 8000 字符**；返回值太长时改用紧凑 JSON，再长就截断并注明。所以：**只 return 需要的字段**，大列表先 `slice`。

| 方法 | 说明 |
|---|---|
| `b.open(url)` | 打开网址（或搜索词），等加载完，返回 `{url, title}`（新宿主还会带 `loaded`、`http_status`、`load_error`） |
| `b.snapshot({mode})` | 返回快照文本（`mode: "interactive"` 只要元素列表） |
| `b.click(ref 或 文字, {js})` | `"e12"` 按编号点，其它按包含的文字点；`{js:true}` 强制 JS 点击 |
| `b.fill(ref, text, {submit, clear})` | 输入文字（别名 `b.type`）；`submit:true` 输入后提交 |
| `b.select(ref, value)` / `b.press(key)` | 下拉框选择 / 按键（Enter Tab Escape ArrowDown…） |
| `b.scroll("down" 或 ref, 屏数)` | 滚动页面，或滚到某个元素 |
| `b.back()` `b.forward()` `b.reload()` | 导航 |
| `b.wait(毫秒)` / `b.wait("文字", 超时)` | 等待；等文字时返回是否出现（true/false） |
| `b.waitFor(选择器 或 函数, 超时=15000)` | 等 CSS 选择器出现**且可见**，或等页面里的函数返回真值；返回该真值，超时返回 `false`（不抛错）。页面跳转中也会继续等 |
| `b.js(fn, ...args)` | **推荐**：`fn` 在页面里执行（可 async），`args` 经 JSON 传进去，返回值原样带回。**提取数据首选** |
| `b.js("脚本", {timeout})` | 兼容写法：表达式（`"document.title"`）或带 `return` 的语句体 |
| `b.url()` | 当前网址 |
| `b.screenshot()` | 截图并附在结果里（最多 3 张） |
| `b.sleep(ms)` / `b.log(...)` | 等待 / 记录输出 |
| `b.tool("站点/工具名", args)` | 调用站点工具 |
| `b.tab("user")` / `await b.newTab(url)` | 换一个标签操作（见「标签页」） |

ref（`e12`）来自快照，页面变了要重新 `b.snapshot()`。在 run 里更稳的做法是：**用 `b.js(fn)` 按 CSS 选择器直接提取**，用 `b.fill`/`b.click` 处理需要真实输入的地方，用 `b.waitFor` 代替固定的 `b.sleep`。每个方法出错都会抛异常；没有捕获时 run 结束并返回出错位置、当前网址和轨迹。

例子：搜索 → 等结果 → 提取前 5 条

```js
// 能直接拼出结果页网址时直接打开，比找搜索框、点按钮稳得多
await b.open("https://so.m.jd.com/ware/search.action?keyword=" + encodeURIComponent("机械键盘"));
if (!(await b.waitFor("[class*=price]", 15000))) return await b.snapshot({ mode: "interactive" }); // 没等到就把页面交回来分析
const items = await b.js((n) => {
  return [...document.querySelectorAll("[class*=product], [class*=item]")]
    .map((e) => ({
      name: e.querySelector("[class*=name], [class*=title]")?.innerText.trim(),
      price: e.querySelector("[class*=price]")?.innerText.replace(/\s+/g, ""),
    }))
    .filter((x) => x.name && x.price)
    .slice(0, n);
}, 5);
return items;
```

（选择器只是示意，真实页面要先探索。拼不出网址时：`snapshot` 找到搜索框 ref → `b.fill(ref, 词, {submit:true})` → `b.waitFor(...)`。）

### 常见坑

- **`b.js` 的函数在网页里运行，看不到 run 里的变量**。`const q = "键盘"; b.js(() => q)` 会报 `q is not defined`。要用的值作为参数传：`b.js((q) => …, q)`。参数和返回值都要能转 JSON：返回 DOM 元素、函数、循环引用都不行，先取出文字或属性。
- **字符串脚本**：写成表达式（`"document.title"`），或者带 `return` 的语句体（`"const a = 1; return a"`）。写成 `"const a = 1; a"` 拿到的是 `undefined`。语法错误在发出前就会报出。
- **在 `b.js` 里触发跳转会丢掉结果**（页面换了，脚本跟着没了）。要点链接跳转，用 `b.click`，或者 `b.js` 只取出 `href` 再 `b.open`。
- **返回值不再被猜类型**：页面返回字符串 `"123"`，你拿到的就是字符串 `"123"`。
- **ref 会失效**：页面变化后旧 ref 可能报「ref eN 已失效」，重新 `snapshot` 拿新的就行，不要反复重试旧 ref。
- **超时**：单步默认 20 秒（`timeout` 毫秒，最大 120000）；`b.waitFor` 默认 15 秒；整段 run 默认 120 秒（最大 600 秒）。超时或中止时，工具会通知宿主取消还在进行的操作。
- **「标签正忙」**：上一个操作还没结束（比如上次 run 超时了）。稍等再试，不要连发。
- **用户叫停、令牌无效、连不上宿主属于致命错误**：之后所有 `b.*` 立即抛同一个错误，`try/catch` 也拦不住，run 会马上结束，结果首行是「⛔ 已叫停/已终止，做到第 N 步」。这时停下，告诉用户进展，不要重试。
- 单步操作（click/type/scroll…）默认只回 interactive 快照（约 2500 字），要看页面文字时再 `snapshot`（默认 full）。

### 出错时怎么读轨迹

run 出错时返回类似：

```
出错: 页面已变化，ref e12 已失效，请重新 snapshot
出错位置: run 代码第 4 行
当前网址: https://example.com/list
最后 3 步（共 3 步）:
#1 open https://example.com · 1830ms · ✓（run 代码第 1 行）
#2 type e5 文字4字 +提交 · 920ms · ✓（run 代码第 2 行）
#3 click e12 · 40ms · ✗（run 代码第 4 行） 页面已变化，ref e12 已失效…
```

- `出错位置` 是你代码的行号（从 code 第 1 行算起；出错在站点工具里时显示 `站点/工具.mjs 第 N 行`）。
- 每步是 `#序号 动作 参数摘要 · 耗时 · 成败（行号）`；输入内容只记字数，不记原文。
- 怎么判断：最后一步 ✗ 就是失败点；某步耗时异常长（接近超时），通常是在等加载或等元素；全部 ✓ 但仍出错，是你自己的代码（如取空值的属性）出了问题，看「出错位置」。
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
- 工具里提取数据同样用 `b.js((参数) => {...}, 参数)`，等元素用 `b.waitFor`。
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

### 标签页

- 浏览器窗口有多个标签：用户自己的标签（🌐）和 **Pi 专属标签（🤖 Pi）**。`browser` 默认在 Pi 标签里操作（没有就新建），**不会改变用户正在看的页面**；Pi 在后台标签操作时，顶栏的标签数按钮会闪烁，地址栏下方出现「🤖 Pi 正在后台标签操作」提示条。
- 所有标签共用 Cookie，用户在任意标签登录过的网站，你在 Pi 标签里也是登录状态。
- 只有用户明确要你操作「我现在这个页面」时，才传 `tab: "user"`（run 里用 `b.tab("user")`）。
- 单步 action：`tabs` 列出标签，`new_tab`（可带 url，返回新标签 id）、`switch_tab`（把某个标签切到前台给用户看）、`close_tab`（只能关 Pi 标签）。同时做两件事时，可以 `const t2 = await b.newTab(url)` 在另一个标签里并行。
- 用户可以点提示条的「查看」（或在标签页网格里点 Pi 标签）把 Pi 标签切到前台自己操作，也可以点「⏹ 停止」。**任何 browser 调用返回「用户已叫停」时，立刻停下所有浏览器操作，告诉用户做到哪一步了，等用户点「▶ 允许 Pi」后再继续**，不要重试。
- 浏览器窗口在前台时，后台标签（用户没在看的 Pi 标签）里点击、截图都能正常用；浏览器窗口不在前台（用户在工作台或别的 App）时只能用 JS 点击，也不能截图。
- **不要打断用户**：你的操作从不把浏览器窗口切到前台（`show` 会切过去、`hide` 会退到后台）。不要为了截图擅自 `show`；需要用户看时先问，或建议用户分屏同时看工作台和浏览器。
- 下载：网页里点下载链接会存到手机「下载/PiMet/」（带当前登录 Cookie）；用 `downloads` 动作查看最近下载和进度。

### 快照说明

- 快照列出可交互元素 `[e3] button "登录"`，视口内的排在前面，其后是 `(下方)`、`(上方)`；嵌套的重复可点击元素只保留最外层。
- 同源 iframe 和开放的 shadow DOM 里的元素也会列出（iframe 里的带 `[iframe]`），可以直接按 ref 点击/输入。跨源 iframe 只会标出「跨源 iframe：网址」，需要的话单独 `open` 那个网址。
- 页面文字优先取正文区域（main/article）。只需要元素时用 `mode: "interactive"`。

## 定制

```
/root/pimet-browser/
  config.json          工具栏按钮、配色、首页
  scripts/*.js         用户脚本（油猴风格），按网址自动注入网页
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

### 用户脚本 scripts/*.js

和油猴（Tampermonkey）脚本一样，用注释头声明匹配的网址，网页加载完成后注入：

```js
// ==UserScript==
// @name   知乎去登录弹窗
// @match  https://www.zhihu.com/*
// @match  https://zhuanlan.zhihu.com/*
// @exclude https://www.zhihu.com/signin*
// ==/UserScript==
const kill = () => document.querySelectorAll('.Modal-wrapper').forEach(e => e.remove());
kill(); new MutationObserver(kill).observe(document.body, { childList: true, subtree: true });
```

- `@match` / `@include`：`*` 匹配任意字符；用 `/.../` 包起来表示正则。`@exclude` 排除。没有 `@match` 的脚本不会注入。
- 脚本运行在网页里（页面加载完成后），可以改样式、加按钮、删广告、自动填写、提取数据等。
- 网页里的脚本**拿不到** `window.pimet`（防止网站借此控制手机）。需要原生能力时做成 pages 页面。
- 文件名决定注入顺序。要停用脚本，把它改名成 `.js.off` 或删除。

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
