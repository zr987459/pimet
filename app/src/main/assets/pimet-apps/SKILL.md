---
name: pimet-apps
description: 在 PiMet 手机端给用户制作或修改「Pi 应用」（定制网页小工具/自定义界面），它们显示在工作台悬浮球的应用窗里。用户说「做个应用/工具/面板/界面」「加个功能页」「改一下某个应用」时使用。
---

# PiMet 应用（定制网页）

用户在手机上使用 PiMet。工作台悬浮球里有一个「应用窗」（⊞），会列出 `/root/pimet-apps/` 下的每个应用，点开就在悬浮窗里运行。你可以做任意多个应用，也可以随时修改它们。

## 应用的结构

```
/root/pimet-apps/<应用id>/
  index.html     必需，入口页面
  app.json       可选：{"name": "记账本", "icon": "💰", "description": "一句话说明"}
  其它文件       css / js / 图片 / 数据，用相对路径引用
```

- `<应用id>` 用小写英文和连字符，例如 `todo`、`battery-monitor`。
- 页面以 `file://` 方式加载：用相对路径引用自己的文件，不要用以 `/` 开头的绝对路径。
- 优先做成不依赖网络的单文件页面（内联 CSS/JS）。需要第三方库时可以用 CDN，但手机网络可能不稳定。
- 改完不需要重启任何东西，用户在应用窗里点 ⟳ 就能看到新版本。

## 手机端设计要求

- 必须写 `<meta name="viewport" content="width=device-width,initial-scale=1">`。
- 竖屏、单手操作优先；按钮可点区域至少 40px；不要依赖鼠标悬停。
- 窗口可能只有屏幕的一半高，内容要能滚动，重要操作放在上方。
- 默认深色背景（#0d1117 左右）；也可以用 `prefers-color-scheme` 适配浅色。
- 应用要保存的数据：小数据用 `localStorage`；需要持久化或给 Pi 读取的数据，用 `pimet.exec` 写到 `/root/pimet-apps/<应用id>/data/`。

## 页面可用的原生接口 `window.pimet`

只有 `/root/pimet-apps/` 下的页面能用。页面加载完成后才会注入，所以要等它就绪：

```js
function onPimet(fn) {
  if (window.pimet) fn(); else window.addEventListener('pimetready', fn, { once: true });
}
```

- `pimet.exec(cmd, {timeout})` → `Promise<{code, stdout, stderr}>`
  在 Linux 容器里用 bash 执行命令（工作目录 /root，默认超时 60 秒，最长 600 秒）。可以读写文件、跑 node/python 脚本、调用 curl 等。
- `pimet.askPi(text, send = true)`：把文字填进工作台的 Pi 输入框，`send` 为 true 时直接发送，适合做「让 Pi 帮我…」按钮。
- `pimet.toast(text)`：手机底部弹一条提示。
- `pimet.openExternal(url)`：用系统浏览器或对应 App 打开链接。
- `pimet.close()`：关闭应用窗。

例子：

```js
onPimet(async () => {
  const r = await pimet.exec('df -h /root | tail -1');
  document.querySelector('#disk').textContent = r.code === 0 ? r.stdout : r.stderr;
});
```

往 shell 命令里拼用户输入时，要先做转义（例如用单引号包起来，并把内容里的 `'` 替换成 `'\''`），或者先把内容写进文件再处理。

## 做完之后

告诉用户应用的名字，并提醒他打开悬浮球 → ⊞ 应用窗（已经开着的话点 ⟳）就能看到。
