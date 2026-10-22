---
name: pimet-browser
description: 定制和扩展 PiMet 手机上的「Pi 浏览器」悬浮窗：改工具栏按钮、配色、首页，写油猴风格的用户脚本给网站加功能，制作自己的网页小工具。用户说「给浏览器加个按钮/功能」「改浏览器界面」「让某个网站…」「做个小工具/页面」时使用。操控浏览器浏览网页用 browser 工具。
---

# Pi 浏览器的定制

用户手机上的 PiMet 有一个悬浮的「Pi 浏览器」（工作台悬浮球 → 🌐）。它有两种用法：

1. **你来操控它**：用 `browser` 工具打开网页、读快照、点击、输入、截图。
2. **你来改造它**：它的界面和功能都由容器里的 `/root/pimet-browser/` 决定，你可以随时修改。

```
/root/pimet-browser/
  config.json          工具栏按钮、配色、首页
  scripts/*.js         用户脚本（油猴风格），按网址自动注入网页
  pages/<id>/index.html  你做的页面/小工具（可多个，可用 window.pimet 原生接口）
  pages/<id>/app.json    可选：{"name":"记账本","icon":"💰","description":"一句话"}
  home.html            可选：自定义首页（不存在时显示内置首页 + pages 列表）
```

改完后调用 `browser` 工具的 `reload_ui` 让配置立即生效；页面和脚本让用户点 ⟳（或你用 `browser` 的 `reload`）即可。

## config.json

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

## 用户脚本 scripts/*.js

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
- 想让页面出现在工具栏上，就在 config.json 加一个 `open: "pages/<id>/index.html"` 按钮。

## 做完之后

用一两句话告诉用户改了什么、在哪里看（工具栏的新按钮 / 点 ⌂ 回首页 / 点 ⟳ 刷新），不用贴代码。
