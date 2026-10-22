---
name: pimet-browser
description: 用 PiMet 手机上的「Pi 浏览器」完成网页任务，并定制它。操控网页（打开、搜索、提取、填表）时先读这里：多步任务用 browser 的 run 代码模式，做任务前先查 sites/ 里的站点工具和经验，成功后沉淀成工具。也用于改工具栏按钮、配色、首页，写油猴风格用户脚本，制作网页小工具。
---

# Pi 浏览器

用户手机上的 PiMet 有一个悬浮的「Pi 浏览器」（工作台悬浮球 → 🌐）。它有两种用法：

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

`browser` action=`run`，`code` 是一段 async 函数体（可以 `await`），有两个变量：`b`（浏览器）和 `ctx`（`ctx.signal` 中止信号、`ctx.tab`）。用 `return` 返回结果（对象会转成 JSON），`b.log()` 的输出也会返回给你；总长度截断到 4 万字符。默认超时 120 秒，`timeout` 参数（秒）最大 600。

| 方法 | 说明 |
|---|---|
| `b.open(url)` | 打开网址（或搜索词），等加载完，返回 `{url, title}` |
| `b.snapshot({mode})` | 返回快照文本（`mode: "interactive"` 只要元素列表） |
| `b.click(ref 或 文字)` | `"e12"` 按编号点，其它按包含的文字点；`{js:true}` 强制 JS 点击 |
| `b.fill(ref, text, {submit, clear})` | 输入文字（别名 `b.type`）；`submit:true` 输入后提交 |
| `b.select(ref, value)` / `b.press(key)` | 下拉框选择 / 按键（Enter Tab Escape ArrowDown…） |
| `b.scroll("down" 或 ref, 屏数)` | 滚动页面，或滚到某个元素 |
| `b.back()` `b.forward()` `b.reload()` | 导航 |
| `b.wait(毫秒)` / `b.wait("文字", 超时)` | 等待；等文字时返回是否出现（true/false） |
| `b.js(script)` | 在页面里执行 JS（可 `await`、`return`），返回解析后的值。**提取数据首选** |
| `b.url()` | 当前网址 |
| `b.screenshot()` | 截图并附在结果里（最多 3 张） |
| `b.sleep(ms)` / `b.log(...)` | 等待 / 记录输出 |
| `b.tool("站点/工具名", args)` | 调用站点工具 |

ref（`e12`）来自最近一次快照，页面变了要重新 `b.snapshot()`。在 run 里更稳的做法是：**用 `b.js()` 按 CSS 选择器直接操作和提取**，用 `b.fill`/`b.click` 处理需要真实输入的地方。每个方法出错都会抛异常；没有捕获时 run 结束并返回错误信息和当前网址。

例子：打开网站 → 搜索 → 提取前 5 条结果

```js
await b.open("https://m.jd.com");
const snap = await b.snapshot({ mode: "interactive" });
const box = snap.match(/\[(e\d+)\] textbox[^\n]*/);      // 找到搜索框
if (!box) return snap.slice(0, 2000);                      // 没找到就把快照交回来分析
await b.fill(box[1], "机械键盘", { submit: true });
await b.wait("¥", 15000);
const items = await b.js(`
  return [...document.querySelectorAll('[class*=product], [class*=item]')]
    .map(e => ({ name: e.querySelector('[class*=name], [class*=title]')?.innerText.trim(),
                 price: e.querySelector('[class*=price]')?.innerText.replace(/\\s+/g, '') }))
    .filter(x => x.name && x.price).slice(0, 5);
`);
b.log("当前网址", await b.url());
return items;
```

（选择器只是示意，真实页面要先探索；能直接拼出搜索结果网址时，直接 `b.open` 那个网址更稳。）

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
- 工具里可以调用别的工具：`await b.tool("jd/login-check")`。

```js
// 京东搜索：args {q: 关键词, n: 条数=5}，返回 [{name, price, url}]
export default async function (b, { q, n = 5 }) {
  await b.open("https://so.m.jd.com/ware/search.action?keyword=" + encodeURIComponent(q));
  await b.wait("¥", 15000);
  return await b.js(`return [...document.querySelectorAll('...')].slice(0, ${n}).map(e => ({ ... }))`);
}
```

notes.md 写法：短条目，每条是一个可执行的结论，例如「搜索结果页可以直接拼：so.m.jd.com/ware/search.action?keyword=…」「首次打开会弹定位权限框，点『暂不』」「价格在 .price 里，带 ¥ 和空格」。过时的条目要改掉，不要越堆越长。

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

改完后调用 `browser` 工具的 `reload_ui` 让配置立即生效；页面和脚本让用户点 ⟳（或你用 `browser` 的 `reload`）即可。

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
    "background": "#F5141418", "border": "#3A3F4B", "text": "#C9D1D9",
    "addressBar": "#22272E", "addressText": "#E6EDF3", "button": "#22272E", "page": "#0D1117"
  },
  "home": ""
}
```

- `buttons` 显示在地址栏下方一行（可横向滑动），长按显示 `title`。每个按钮可以有：
  - `script`：在当前网页里执行的 JS。
  - `open`：打开网址；`pages/...` 或 `home.html` 指本目录下的文件，`/` 开头指容器内绝对路径。
  - `askPi`：把文字发给你（Pi）。可用占位符 `{url}` `{title}` `{selection}`（用户选中的文字）。加 `"send": false` 则只填进输入框不发送。
  - `icon`（emoji）、`label`（文字）、`color`（文字颜色）。
- `theme`：颜色都是可选的，格式 `#RRGGBB` 或 `#AARRGGBB`。
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
- 想让页面出现在工具栏上，就在 config.json 加一个 `open: "pages/<id>/index.html"` 按钮。

## 做完之后

用一两句话告诉用户结果：网页任务给出要的信息和沉淀了哪些工具；定制任务说明改了什么、在哪里看（工具栏的新按钮 / 点 ⌂ 回首页 / 点 ⟳ 刷新），不用贴代码。
