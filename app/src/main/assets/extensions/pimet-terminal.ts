/**
 * PiMet Terminal Extension
 * 让 Pi 使用 PiMet 的悬浮终端：真 PTY 交互会话（与用户在悬浮窗里看到的是同一批会话）。
 * 适合：长时间运行的服务（dev server、下载、编译）、交互程序（python REPL、vim、top、ssh）、
 * 需要用户一起看的操作。一次性的普通命令仍可直接用 bash 工具。
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

function format(body: string): string {
  try {
    const j = JSON.parse(body);
    if (j.success === false) return `失败: ${j.error ?? body}`;
    const parts: string[] = [];
    if (j.session !== undefined) parts.push(`session: ${typeof j.session === "string" ? j.session : JSON.stringify(j.session)}`);
    if (j.sessions !== undefined) parts.push(`sessions: ${JSON.stringify(j.sessions, null, 1)}`);
    if (j.exit_code !== undefined) parts.push(`exit_code: ${j.exit_code}`);
    if (j.running) parts.push(`running: true`);
    if (j.matched !== undefined) parts.push(`matched: ${j.matched}`);
    if (j.result) parts.push(String(j.result));
    if (j.note) parts.push(String(j.note));
    if (j.output !== undefined) parts.push(`--- output ---\n${j.output}`);
    if (j.text !== undefined) parts.push(`--- text ---\n${j.text}`);
    if (j.screen !== undefined) parts.push(`--- screen ---\n${j.screen}`);
    return parts.join("\n") || body;
  } catch (e) {
    return body;
  }
}

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "terminal",
    label: "Terminal",
    description:
      "操作 PiMet 悬浮终端（PRoot 容器内的真 PTY bash 会话，用户可在悬浮窗里实时看到并接管）。" +
      "action：list(列出会话) | create(新建会话，可选 name/cwd/command，show=true 弹出悬浮窗) | " +
      "run(执行命令并等待结束，返回 output 与 exit_code；超时则返回当前屏幕且 running=true) | " +
      "send(输入文字，默认回车；用于交互程序/回答提示，返回屏幕) | " +
      "keys(发送按键，如 [\"ctrl-c\"]、\"up enter\"、tab、esc、f1-f12、alt-x、pageup) | " +
      "read(读取屏幕；lines=N 取历史最后 N 行；wait=毫秒 等输出静止再读) | " +
      "wait_for(等屏幕出现正则 pattern) | show / hide(显示/隐藏悬浮窗) | rename | clear | close。" +
      "不写 id 时默认使用最近的会话。长期运行的服务请单独 create 一个会话再 send 启动，之后用 read 查看。",
    parameters: Type.Object({
      action: Type.String({ description: "list | create | run | send | keys | read | wait_for | show | hide | rename | clear | close" }),
      id: Type.Optional(Type.String({ description: "会话 id（如 t1）或名称；省略则用最近的会话" })),
      name: Type.Optional(Type.String({ description: "create/rename 的会话名称" })),
      cwd: Type.Optional(Type.String({ description: "create 的工作目录，默认 /root" })),
      command: Type.Optional(Type.String({ description: "run 要执行的命令；create 时作为启动命令" })),
      text: Type.Optional(Type.String({ description: "send 输入的文字" })),
      enter: Type.Optional(Type.Boolean({ description: "send 后是否回车，默认 true" })),
      keys: Type.Optional(Type.Union([Type.Array(Type.String()), Type.String()], { description: "keys 要发送的按键序列" })),
      pattern: Type.Optional(Type.String({ description: "wait_for 的正则" })),
      mode: Type.Optional(Type.String({ description: "read：screen(默认，当前屏幕) | history(含滚动历史)" })),
      lines: Type.Optional(Type.Number({ description: "read 返回历史最后 N 行" })),
      timeout: Type.Optional(Type.Number({ description: "run / wait_for 超时毫秒，默认 30000，最大 600000" })),
      wait: Type.Optional(Type.Number({ description: "send/keys/read 后等待输出静止的最长毫秒" })),
      show: Type.Optional(Type.Boolean({ description: "create 后弹出悬浮终端给用户看" })),
      max_chars: Type.Optional(Type.Number({ description: "返回文本最大字符数，默认 12000" })),
    }),
    async execute(_toolCallId, params, signal) {
      const { action, ...rest } = params as Record<string, any>;
      const t = Math.min(Number(rest.timeout) || 30000, 600000);
      const w = Math.min(Number(rest.wait) || 0, 60000);
      const r = await postBridge({ ...rest, action: "terminal", terminal_action: action }, t + w + 15000, signal);
      const text = r.ok ? format(r.body) : `无法连接 PiMet 宿主（127.0.0.1:30143）：${r.body}。请确认 PiMet 应用在运行。`;
      return { content: [{ type: "text", text }], details: { action, ...rest } };
    },
  });
}
