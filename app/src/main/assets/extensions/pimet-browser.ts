/**
 * PiMet Browser Extension
 * 让 Pi 操控 PiMet 的「Pi 浏览器」悬浮窗（Android WebView，用户能实时看到并接手）。
 * 用法借鉴 Playwright MCP / browser-use：先拿页面快照，可交互元素带编号 [e1] [e2]…，再按编号点击 / 输入。
 * 本文件由 PiMet 自动生成，每次启动会覆盖。
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import * as http from "http";
import * as fs from "fs";
import * as path from "path";
import * as os from "os";

const BRIDGE_DIR = path.join(os.homedir(), ".pi", "agent");

function readToken(): string {
  try {
    return fs.readFileSync(path.join(BRIDGE_DIR, ".pimet_bridge_token"), "utf-8").trim();
  } catch (e) {
    return "";
  }
}

function postBridge(data: Record<string, any>, timeoutMs: number, signal?: AbortSignal): Promise<{ ok: boolean; body: string }> {
  return new Promise((resolve) => {
    const postData = JSON.stringify(data);
    const req = http.request(
      {
        hostname: "127.0.0.1",
        port: 30143,
        path: "/api/app-control",
        method: "POST",
        headers: {
          "Content-Type": "application/json; charset=utf-8",
          "Content-Length": Buffer.byteLength(postData),
          "X-PiMet-Token": readToken(),
        },
        timeout: timeoutMs,
      },
      (res) => {
        const chunks: Buffer[] = [];
        res.on("data", (c) => chunks.push(Buffer.isBuffer(c) ? c : Buffer.from(c)));
        res.on("end", () => resolve({ ok: true, body: Buffer.concat(chunks).toString("utf-8") }));
      }
    );
    req.on("timeout", () => req.destroy(new Error(`timeout after ${timeoutMs}ms`)));
    req.on("error", (err) => resolve({ ok: false, body: err.message }));
    if (signal) {
      if (signal.aborted) req.destroy(new Error("aborted"));
      else signal.addEventListener("abort", () => req.destroy(new Error("aborted")), { once: true });
    }
    req.write(postData);
    req.end();
  });
}

function format(body: string): { text: string; image?: { data: string; mimeType: string } } {
  try {
    const j = JSON.parse(body);
    if (j.success === false) return { text: `失败: ${j.error ?? body}` };
    const parts: string[] = [];
    if (j.result !== undefined) parts.push(String(j.result));
    if (j.snapshot !== undefined) parts.push(String(j.snapshot));
    else if (j.url) parts.push(`网址: ${j.url}`);
    if (j.image) return { text: parts.join("\n\n") || "截图", image: { data: j.image, mimeType: j.mime || "image/jpeg" } };
    return { text: parts.join("\n\n") || body };
  } catch (e) {
    return { text: body };
  }
}

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "browser",
    label: "Browser",
    description:
      "操控 PiMet 手机上的「Pi 浏览器」悬浮窗口（真实的 Android WebView，用户会实时看到你的每一步，也能随时接手）。" +
      "用于需要真正打开网页的任务：浏览/搜索/阅读动态页面、登录后的网站、填写表单、点按钮、查看你自己做的本地页面效果等。" +
      "大部分操作完成后都会返回页面快照：网址、标题、带编号的可交互元素（如 [e3] button \"登录\"）和页面文字。" +
      "action：open(打开 url，也可以直接写搜索词) | snapshot(重新读取页面) | click(按 ref 点击，或用 text 点击包含该文字的元素) | " +
      "type(向 ref 输入 text；clear 默认 true；submit=true 输入后提交/回车) | select(下拉框 ref 选 value) | press(按键 key：Enter/Tab/Escape/Backspace/ArrowDown…) | " +
      "scroll(direction=up/down/left/right，amount=屏数，默认 0.8；或给 ref 滚动到该元素) | back | forward | reload | home | " +
      "wait(ms 毫秒，或等页面出现 text) | eval(在页面里执行 JS，支持 await 和 return，返回结果) | screenshot(截图给你看) | show | hide(最小化，仍可操作) | close | reload_ui(修改 /root/pimet-browser/config.json 后让按钮/配色立即生效)。" +
      "这个浏览器的工具栏按钮、配色、首页、用户脚本、自定义页面都可以由你修改，方法见 pimet-browser 技能。" +
      "注意：ref 只在最近一次快照内有效，页面变化后请重新 snapshot；遇到需要用户本人操作的情况（登录验证码、支付确认）请停下来让用户在窗口里完成。",
    parameters: Type.Object({
      action: Type.String({ description: "open | snapshot | click | type | select | press | scroll | back | forward | reload | home | wait | eval | screenshot | show | hide | close | reload_ui" }),
      url: Type.Optional(Type.String({ description: "open 的网址或搜索词" })),
      ref: Type.Optional(Type.String({ description: "元素编号，如 e12（来自最近一次快照）" })),
      text: Type.Optional(Type.String({ description: "type 要输入的文字；click 时按包含的文字查找元素；wait 时等待出现的文字" })),
      clear: Type.Optional(Type.Boolean({ description: "type 前清空原内容，默认 true" })),
      submit: Type.Optional(Type.Boolean({ description: "type 后提交表单 / 回车" })),
      value: Type.Optional(Type.String({ description: "select 的选项值或文字" })),
      key: Type.Optional(Type.String({ description: "press 的按键" })),
      direction: Type.Optional(Type.String({ description: "scroll 方向 up/down/left/right，默认 down" })),
      amount: Type.Optional(Type.Number({ description: "scroll 滚动的屏数，默认 0.8" })),
      script: Type.Optional(Type.String({ description: "eval 执行的 JS（可用 await；用 return 返回值，否则返回表达式的值）" })),
      ms: Type.Optional(Type.Number({ description: "wait 的毫秒数" })),
      js: Type.Optional(Type.Boolean({ description: "click 时用 JS 点击而不是模拟真实触摸（被遮挡或真实点击无效时用）" })),
      snapshot: Type.Optional(Type.Boolean({ description: "操作后是否返回快照，默认 true" })),
      max_chars: Type.Optional(Type.Number({ description: "快照最大字符数，默认 6000" })),
      timeout: Type.Optional(Type.Number({ description: "等待页面加载 / wait / eval 的超时毫秒，默认 20000，最大 120000" })),
    }),
    async execute(_toolCallId, params, signal) {
      const { action, ...rest } = params as Record<string, any>;
      const t = Math.min(Number(rest.timeout) || 20000, 120000);
      const extra = action === "wait" ? Math.min(Number(rest.ms) || 0, 60000) : 0;
      const r = await postBridge({ ...rest, action: "browser", browser_action: action }, t + extra + 30000, signal);
      if (!r.ok) {
        return { content: [{ type: "text", text: `无法连接 PiMet 宿主（127.0.0.1:30143）：${r.body}。请确认 PiMet 应用在运行。` }], details: { action } };
      }
      const f = format(r.body);
      const content: any[] = [{ type: "text", text: f.text }];
      if (f.image) content.push({ type: "image", data: f.image.data, mimeType: f.image.mimeType });
      return { content, details: { action, ...rest } };
    },
  });
}
