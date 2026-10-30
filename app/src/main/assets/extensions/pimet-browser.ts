/**
 * PiMet Browser Extension
 * 让 Pi 操控 PiMet 的「Pi 浏览器」（独立全屏窗口，Android WebView，用户能实时看到并接手）。
 * 用法借鉴 Playwright MCP / browser-use / ego-lite：
 *   - 单步：先拿页面快照，可交互元素带编号 [e1] [e2]…，再按编号点击 / 输入；
 *   - 代码模式 run：一段 async JS 一次跑完多步（b.open / b.fill / b.js …），省去来回；
 *   - 站点工具：/root/pimet-browser/sites/<站点>/tools/<名>.mjs 沉淀可复用流程，b.tool("站点/名", args) 调用。
 * 协议约定见仓库 docs/pi-browser-opt.md（req_id / cancel / fn:true / 返回信封 …）。
 * 本文件由 PiMet 自动生成，每次启动会覆盖。
 */
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import * as http from "http";
import * as fs from "fs";
import * as path from "path";
import * as os from "os";
import { pathToFileURL } from "url";

const BRIDGE_DIR = path.join(os.homedir(), ".pi", "agent");
const BROWSER_DIR = fs.existsSync("/root/pimet-browser") ? "/root/pimet-browser" : path.join(os.homedir(), "pimet-browser");
const SITES_DIR = path.join(BROWSER_DIR, "sites");
const MAX_OUT = 40000;
const LOG_TAIL = 8000;
const TRACE_KEEP = 5;
const MAX_SHOTS = 3;
const NAME_RE = /^[A-Za-z0-9._-]+$/;
/** 变更类动作：单步时默认只回 interactive 快照 */
const MUTATING = new Set(["click", "tap", "type", "fill", "select", "press", "key", "scroll", "back", "forward", "reload", "hover", "scroll_into_view"]);
/** 接受统一目标（@N / CSS / text=文字 / [x,y]）的动作 */
const TARGETED = new Set(["click", "tap", "type", "fill", "select", "hover", "scroll_into_view", "scroll"]);
/** beta.23 新动作名 → 旧宿主上的同义动作（旧宿主回「未知操作」时自动改用） */
const LEGACY: Record<string, string> = { tab_new: "new_tab", tab_switch: "switch_tab", tab_close: "close_tab" };
/** 油猴脚本 / 浏览器设置类动作（beta.22 合同第 3 节）：不带快照，结果按数据字段原样展示 */
const MANAGE = new Set([
  "scripts", "script_get", "script_install", "script_enable", "script_remove", "script_update", "script_values",
  "menu_commands", "menu_run", "console", "config_get", "config_set", "ua", "desktop", "zoom", "cookies", "clear_data",
  "find", "wait_for", "inject",
  "containers", "container_keep", "history", "history_delete", "history_clear", "tab_new", "tab_switch", "tab_close",
]);
/** 可能要联网下载的动作：客户端多等一会 */
const SLOW = new Set(["script_install", "script_update"]);

/** 允许模型把对象参数写成 JSON 字符串 */
function maybeJson(v: any): any {
  if (typeof v !== "string") return v;
  const t = v.trim();
  if (!/^[\[{]/.test(t)) return v;
  try {
    return JSON.parse(t);
  } catch (e) {
    return v;
  }
}

/**
 * 把工具参数翻译成宿主（BrowserAgent）合同里的参数名：
 * - inject：schema 里用 script 写代码（js 在 schema 里是 click 的布尔开关），发给宿主时改名为 js；
 * - menu_run：脚本 id 可写 id 或 script，命令写 cmd；
 * - ua：""/default/reset/null 表示恢复默认（发 null）；
 * - wait_for：没给 timeout_ms 时用 timeout，且不超过 30000；
 * - script_values / config_set / inject.persist：对象参数允许是 JSON 字符串。
 */
function targetStr(t: any): string {
  return Array.isArray(t) ? `[${t.map((x) => Number(x)).join(",")}]` : String(t ?? "");
}

function adaptParams(action: string, p: Record<string, any>): Record<string, any> {
  const q: Record<string, any> = { ...p };
  if (TARGETED.has(action)) {
    // 统一目标：新宿主读 target；同时填 ref 兼容旧宿主（页面辅助脚本两种写法都认）
    if (q.target === undefined && typeof q.ref === "string" && q.ref.trim()) q.target = q.ref.trim();
    if (typeof q.target === "string") {
      const t = q.target.trim();
      const c = t.match(/^\[\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*\]$/);
      q.target = c ? [Number(c[1]), Number(c[2])] : t;
    }
    if (q.target !== undefined && q.target !== "" && (q.ref === undefined || q.ref === "")) q.ref = targetStr(q.target);
    if (action === "fill" || action === "type") {
      if (q.text === undefined && q.value !== undefined) q.text = String(q.value);
      if (q.value === undefined && q.text !== undefined) q.value = String(q.text);
    }
  }
  switch (action) {
    case "batch":
      q.steps = (Array.isArray(q.steps) ? q.steps : maybeJson(q.steps) || []).map((st: any) => {
        if (!st || typeof st !== "object") return st;
        const a = String(st.action ?? "");
        const { action: _a, ...rest } = st;
        return { action: a, ...adaptParams(a, rest) };
      });
      if (q.stopOnError === undefined) q.stopOnError = true;
      break;
    case "tab_new":
      if (q.container === undefined || q.container === "") q.container = "new";
      if (q.background === undefined) q.background = true;
      break;
    case "snapshot":
      if (q.scope === "interactive") { q.mode = "interactive"; delete q.scope; }
      break;
    case "inject":
      if (typeof q.js !== "string") q.js = q.script ?? q.code ?? "";
      delete q.script;
      delete q.code;
      if (q.persist !== undefined) q.persist = maybeJson(q.persist);
      break;
    case "menu_run":
      if (q.script === undefined && q.id !== undefined) q.script = q.id;
      if (q.cmd === undefined && q.value !== undefined) q.cmd = q.value;
      break;
    case "script_values":
      if (q.set !== undefined) q.set = maybeJson(q.set);
      if (typeof q.delete === "string") {
        const d = maybeJson(q.delete);
        q.delete = Array.isArray(d) ? d : String(q.delete).split(/[,，\s]+/).filter(Boolean);
      }
      break;
    case "config_set":
      q.patch = maybeJson(q.patch);
      break;
    case "ua":
      if (q.ua === undefined || q.ua === null || /^(|null|default|reset|默认)$/i.test(String(q.ua).trim())) q.ua = null;
      break;
    case "wait_for": {
      let ms = Number(q.timeout_ms);
      if (!ms) ms = Number(q.timeout) || 15000;
      q.timeout_ms = Math.max(0, Math.min(ms, 30000));
      q.timeout = q.timeout_ms + 5000; // 宿主整体超时要比等待时间长
      break;
    }
  }
  if (MANAGE.has(action) || action === "batch") q.snapshot = false;
  return q;
}

/** 客户端等宿主的时间（毫秒） */
function clientTimeout(action: string, p: Record<string, any>): number {
  const t = Math.min(Number(p.timeout) || 20000, 120000);
  let extra = 0;
  if (action === "wait") extra = Math.min(Number(p.ms) || 0, 60000);
  if (action === "wait_for") extra = Math.min(Number(p.timeout_ms) || 0, 30000);
  if (SLOW.has(action)) extra = Math.max(extra, 90000);
  if (action === "batch") extra = Math.min(120000, (Array.isArray(p.steps) ? p.steps.length : 1) * 12000);
  return t + extra + 30000;
}

/** 旧宿主不认识新动作时给出提示 */
function unknownHint(action: string, msg: string): string {
  if (/unknown_ref/.test(msg)) return "ref 已失效（unknown_ref）：页面已变化，请重新 snapshot 拿新的编号";
  return MANAGE.has(action) && /未知操作/.test(msg) ? `${msg}（当前 PiMet 版本还不支持 ${action}，需要更新 PiMet）` : msg;
}

function isUnknownAction(msg: any): boolean {
  return /未知操作/.test(String(msg ?? ""));
}

/** 宿主错误信息：unknown_ref 带 hint 时一并给出 */
function hostErrText(action: string, j: any): string {
  let m = unknownHint(action, String(j?.error ?? "操作失败"));
  if (j?.hint && !m.includes(String(j.hint))) m += `（${j.hint}）`;
  return m;
}

function readToken(): string {
  try {
    return fs.readFileSync(path.join(BRIDGE_DIR, ".pimet_bridge_token"), "utf-8").trim();
  } catch (e) {
    return "";
  }
}

function newReqId(): string {
  return Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
}

type BridgeResult = { ok: boolean; body: string; aborted?: boolean; timedOut?: boolean };

function rawPost(data: Record<string, any>, timeoutMs: number, onDone: (r: BridgeResult) => void): http.ClientRequest {
  const postData = JSON.stringify(data);
  let finished = false;
  const done = (r: BridgeResult) => {
    if (finished) return;
    finished = true;
    onDone(r);
  };
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
      res.on("end", () => done({ ok: true, body: Buffer.concat(chunks).toString("utf-8") }));
      res.on("error", (err) => done({ ok: false, body: err.message }));
    }
  );
  req.on("timeout", () => {
    (req as any).__timedOut = true;
    req.destroy(new Error(`timeout after ${timeoutMs}ms`));
  });
  req.on("error", (err) => done({ ok: false, body: err.message, timedOut: !!(req as any).__timedOut, aborted: !!(req as any).__aborted }));
  req.write(postData);
  req.end();
  return req;
}

/** 取消在途请求：2s 超时，失败（含旧宿主回「未知操作」）一律忽略 */
function sendCancel(reqId: string, tab?: string): void {
  try {
    const data: Record<string, any> = { action: "browser", browser_action: "cancel", req_id: reqId };
    if (tab) data.tab = tab;
    rawPost(data, 2000, () => {});
  } catch (e) {}
}

/** 每次请求带 req_id；AbortSignal 触发或客户端超时时补发 cancel */
function postBridge(data: Record<string, any>, timeoutMs: number, signal?: AbortSignal): Promise<BridgeResult> {
  return new Promise((resolve) => {
    const reqId = newReqId();
    const payload = { ...data, req_id: reqId };
    let onAbort: (() => void) | null = null;
    const req = rawPost(payload, timeoutMs, (r) => {
      if (onAbort && signal) signal.removeEventListener("abort", onAbort);
      if (r.timedOut || r.aborted) sendCancel(reqId, data.tab);
      resolve(r);
    });
    if (signal) {
      onAbort = () => {
        (req as any).__aborted = true;
        req.destroy(new Error("aborted"));
      };
      if (signal.aborted) onAbort();
      else signal.addEventListener("abort", onAbort, { once: true });
    }
  });
}

function show(v: any): string {
  if (v === undefined) return "undefined";
  if (typeof v === "string") return v;
  try {
    return JSON.stringify(v, null, 2) ?? String(v);
  } catch (e) {
    return String(v);
  }
}

function compact(v: any): string {
  if (v === undefined) return "undefined";
  if (typeof v === "string") return v;
  try {
    return JSON.stringify(v) ?? String(v);
  } catch (e) {
    return String(v);
  }
}

function truncate(s: string, n = MAX_OUT): string {
  return s.length > n ? s.slice(0, n) + `\n…（已截断，共 ${s.length} 字符）` : s;
}

function tailOf(s: string, n: number): string {
  return s.length > n ? `…（前面 ${s.length - n} 字符已省略）\n` + s.slice(s.length - n) : s;
}

/** 宿主新字段（loaded/http_status/load_error）存在时给出提示；旧宿主没有就什么都不加 */
function hostHints(j: any): string[] {
  const out: string[] = [];
  if (!j || typeof j !== "object") return out;
  if (typeof j.http_status === "number" && j.http_status >= 400) out.push(`⚠ HTTP ${j.http_status}`);
  if (j.load_error) out.push(`⚠ 页面加载出错: ${j.load_error}`);
  if (j.loaded === false) out.push("⚠ 未确认页面加载完成（可能仍在加载或没有跳转）");
  return out;
}

// ------------------------------------------------------------------ 返回信封（b.js / eval）

const AsyncFunction: any = Object.getPrototypeOf(async function () {}).constructor;

/** 判断字符串脚本是表达式还是语句体（在 Node 里只做语法解析，不执行） */
function isExpression(src: string): boolean {
  try {
    new AsyncFunction("return (" + src + "\n)");
    return true;
  } catch (e) {
    return false;
  }
}

/** 包装成自带 return 的完整函数体（配合 fn:true）：结果统一放进信封 {u, v} */
function envelopeBody(inner: string): string {
  return (
    "const __r = await (async () => {\n" + inner + "\n})();\n" +
    "if (__r === undefined) return JSON.stringify({ u: true, v: null });\n" +
    "try { const __s = JSON.stringify({ u: false, v: __r }); if (__s !== undefined) return __s; } catch (__e) {}\n" +
    "return JSON.stringify({ u: false, v: String(__r), x: 1 });"
  );
}

function wrapScript(script: string | Function, args: any[] = []): string {
  if (typeof script === "function") {
    const src = script.toString();
    let argsJson: string;
    try {
      argsJson = JSON.stringify(args ?? []);
    } catch (e: any) {
      throw new BrowserError(`b.js 的参数无法转成 JSON：${e?.message ?? e}`);
    }
    return envelopeBody(`return await (${src}).apply(null, ${argsJson});`);
  }
  const s = String(script ?? "");
  const body = envelopeBody(isExpression(s) ? `return (${s}\n);` : s);
  try {
    new AsyncFunction(body); // 只做语法检查，语法错误在这里就报出来
  } catch (e: any) {
    throw new BrowserError(`脚本语法错误：${e?.message ?? e}（字符串脚本写表达式，或带 return 的语句体）`);
  }
  return body;
}

/** 解析信封；不是信封（旧宿主、被截断）时原样返回字符串 */
function decodeEnvelope(raw: any): { enveloped: boolean; value: any } {
  if (typeof raw !== "string") return { enveloped: false, value: raw };
  try {
    const j = JSON.parse(raw);
    if (j && typeof j === "object" && !Array.isArray(j) && "u" in j && "v" in j) {
      return { enveloped: true, value: j.u ? undefined : j.v };
    }
  } catch (e) {}
  return { enveloped: false, value: raw };
}

// ------------------------------------------------------------------ 单次桥调用

class BrowserError extends Error {}
/** 致命错误：用户叫停、令牌无效、连不上宿主。run 里一旦出现，之后所有 b.* 立即抛同一错误 */
class FatalError extends BrowserError {}

function hostError(msg: string): BrowserError {
  return /叫停|令牌|用户正在操作/.test(msg) ? new FatalError(msg) : new BrowserError(msg);
}

async function call(action: string, params: Record<string, any>, signal?: AbortSignal, clientMs?: number): Promise<any> {
  try {
    return await call1(action, params, signal, clientMs);
  } catch (e: any) {
    if (!(e instanceof FatalError) && isUnknownAction((e as any)?.rawError)) {
      if (LEGACY[action]) return await call1(LEGACY[action], params, signal, clientMs);
      if (action === "batch") return await batchLocal(params, signal);
    }
    throw e;
  }
}

/** 旧宿主没有 batch：在本地按顺序逐步执行，结果格式 {steps:[{i,action,ok,result|error}]} */
async function batchLocal(params: Record<string, any>, signal?: AbortSignal): Promise<any> {
  const p = adaptParams("batch", params);
  const steps: any[] = p.steps || [];
  const out: any[] = [];
  let lastUrl = "", title = "";
  for (let i = 0; i < steps.length; i++) {
    const s = steps[i] || {};
    const { action, ...rest } = s;
    const sp: Record<string, any> = { ...rest };
    if (p.tab && sp.tab === undefined) sp.tab = p.tab;
    if (action !== "snapshot" && sp.snapshot === undefined) sp.snapshot = false;
    try {
      const j = await call(String(action), sp, signal);
      if (j.url) lastUrl = j.url;
      if (j.title !== undefined) title = j.title;
      const r: any = { i: i + 1, action, ok: true };
      if (j.result !== undefined) r.result = j.result;
      if (action === "snapshot") r.text = j.snapshot ?? j.text;
      if (j.changed !== undefined) r.changed = j.changed;
      out.push(r);
    } catch (e: any) {
      if (e instanceof FatalError) throw e;
      out.push({ i: i + 1, action, ok: false, error: e?.message ?? String(e) });
      if (p.stopOnError !== false) break;
    }
  }
  return { success: true, steps: out, url: lastUrl, title, local: true };
}

async function call1(action: string, params: Record<string, any>, signal?: AbortSignal, clientMs?: number): Promise<any> {
  if (signal?.aborted) throw new BrowserError("已中止");
  const p = adaptParams(action, params);
  const r = await postBridge({ ...p, action: "browser", browser_action: action }, clientMs ?? clientTimeout(action, p), signal);
  if (!r.ok) {
    if (signal?.aborted || r.aborted) throw new BrowserError("已中止");
    if (r.timedOut) throw new BrowserError(`等待宿主响应超时（${action}），已发送取消`);
    throw new FatalError(`无法连接 PiMet 宿主（127.0.0.1:30143）：${r.body}。请确认 PiMet 应用在运行。`);
  }
  let j: any;
  try {
    j = JSON.parse(r.body);
  } catch (e) {
    throw new BrowserError(`宿主返回无法解析：${r.body.slice(0, 300)}`);
  }
  if (j.success === false) {
    const e = hostError(hostErrText(action, j));
    (e as any).url = j.url;
    (e as any).rawError = j.error;
    (e as any).code = j.code ?? (j.error === "unknown_ref" ? "unknown_ref" : undefined);
    throw e;
  }
  return j;
}

// ------------------------------------------------------------------ 站点工具

function checkName(s: string, what: string): string {
  if (!s || !NAME_RE.test(s) || s === "." || s === "..") throw new BrowserError(`${what}「${s}」不合法，只允许 A-Z a-z 0-9 . _ -`);
  return s;
}

function toolPath(spec: string): string {
  const m = String(spec || "").split("/");
  if (m.length !== 2) throw new BrowserError(`工具名应写成「站点/工具名」，如 jd/search，收到「${spec}」`);
  const site = checkName(m[0], "站点名");
  const name = checkName(m[1].replace(/\.mjs$/, ""), "工具名");
  return path.join(SITES_DIR, site, "tools", name + ".mjs");
}

// 绕过扩展的转译器，直接用 Node 原生的动态 import（.mjs 必须走 ESM 加载）
const nativeImport: (u: string) => Promise<any> = new Function("u", "return import(u)") as any;

async function loadTool(spec: string): Promise<(b: any, args: any) => Promise<any>> {
  const file = toolPath(spec);
  let st: fs.Stats;
  try {
    st = fs.statSync(file);
  } catch (e) {
    throw new BrowserError(`没有工具 ${spec}（${file}）。可用 browser action=tools 查看现有工具`);
  }
  const mod = await nativeImport(pathToFileURL(file).href + "?v=" + st.mtimeMs);
  let fn = typeof mod.default === "function" ? mod.default : null;
  if (!fn) for (const k of Object.keys(mod)) if (typeof mod[k] === "function") { fn = mod[k]; break; }
  if (!fn) throw new BrowserError(`${spec} 没有导出函数（应 export default async (b, args) => {...}）`);
  return fn;
}

function firstLineDesc(file: string): string {
  try {
    const head = fs.readFileSync(file, "utf-8").split(/\r?\n/, 3);
    for (const l of head) {
      const m = l.match(/^\s*\/\/\s?(.*)$/);
      if (m) return m[1].trim();
    }
  } catch (e) {}
  return "";
}

function listTools(site?: string): string {
  if (site) checkName(site, "站点名");
  let sites: string[] = [];
  try {
    sites = fs.readdirSync(SITES_DIR).filter((s) => NAME_RE.test(s) && fs.statSync(path.join(SITES_DIR, s)).isDirectory()).sort();
  } catch (e) {}
  if (site) sites = sites.filter((s) => s === site);
  if (!sites.length) {
    return site
      ? `还没有站点 ${site} 的经验和工具（目录 ${path.join(SITES_DIR, site)}）。`
      : `还没有任何站点工具（目录 ${SITES_DIR}）。做成一个网站任务后，按 pimet-browser 技能把流程沉淀成 sites/<站点>/tools/<名>.mjs。`;
  }
  const out: string[] = [];
  for (const s of sites) {
    const dir = path.join(SITES_DIR, s);
    const notes = path.join(dir, "notes.md");
    const hasNotes = fs.existsSync(notes);
    out.push(`## ${s}${hasNotes ? "（有 notes.md）" : "（无 notes.md）"}`);
    let tools: string[] = [];
    try {
      tools = fs.readdirSync(path.join(dir, "tools")).filter((f) => f.endsWith(".mjs")).sort();
    } catch (e) {}
    if (!tools.length) out.push("  （暂无工具）");
    for (const f of tools) {
      const desc = firstLineDesc(path.join(dir, "tools", f));
      out.push(`  - ${s}/${f.replace(/\.mjs$/, "")}${desc ? "：" + desc : ""}`);
    }
    if (site && hasNotes) {
      try {
        out.push("\n### notes.md\n" + fs.readFileSync(notes, "utf-8"));
      } catch (e) {}
    }
  }
  if (!site) out.push(`\n用 b.tool("站点/工具名", args) 在 run 里调用；action=tools 加 site 参数可同时查看该站点的 notes.md。`);
  return out.join("\n");
}

// ------------------------------------------------------------------ 代码位置（行号）

/** run 代码由 AsyncFunction 构造，函数头占 2 行 */
const RUN_LINE_OFFSET = 2;

/** 从调用栈里找出 run 代码的行号或站点工具的 文件:行号 */
function codeLocation(stack: string | undefined): string {
  if (!stack) return "";
  for (const line of String(stack).split("\n")) {
    const t = line.match(/sites[\\/]([^\\/]+)[\\/]tools[\\/]([^\\/:?]+\.mjs)(?:\?[^:]*)?:(\d+):\d+/);
    if (t) return `${t[1]}/${t[2]} 第 ${t[3]} 行`;
    if (line.includes("eval at")) {
      const m = line.match(/<anonymous>:(\d+):(\d+)\)?\s*$/);
      if (m) {
        const n = Number(m[1]) - RUN_LINE_OFFSET;
        if (n >= 1) return `run 代码第 ${n} 行`;
      }
    }
  }
  return "";
}

// ------------------------------------------------------------------ 代码模式 run

type Shot = { data: string; mimeType: string };
type Step = { i: number; action: string; arg: string; ms: number; ok: boolean; at: string; err?: string };

interface RunState {
  logs: string[];
  shots: Shot[];
  signal: AbortSignal;
  abort: () => void;
  lastUrl: string;
  trace: Step[];
  fatal: BrowserError | null;
}

function clip(s: any, n = 80): string {
  const v = String(s ?? "").replace(/\s+/g, " ");
  return v.length > n ? v.slice(0, n) + "…" : v;
}

/** 参数摘要：fill/type 只记长度，不记内容（可能是密码） */
function argSummary(action: string, p: Record<string, any>): string {
  switch (action) {
    case "open": case "new_tab": return clip(p.url);
    case "click": case "hover": case "scroll_into_view": return clip(p.target !== undefined ? targetStr(p.target) : p.ref ?? p.text, 40);
    case "fill": return `${targetStr(p.target ?? p.ref)} 文字${String(p.text ?? p.value ?? "").length}字${p.submit ? " +提交" : ""}`;
    case "batch": return `${Array.isArray(p.steps) ? p.steps.length : 0} 步`;
    case "tab_new": return `${clip(p.url ?? "", 50)} 容器=${p.container ?? "new"}`;
    case "type": return `${p.ref ?? ""} 文字${String(p.text ?? "").length}字${p.submit ? " +提交" : ""}`;
    case "select": return `${p.ref ?? ""} = ${clip(p.value, 30)}`;
    case "press": return String(p.key ?? "");
    case "scroll": return clip(p.ref ?? p.direction ?? "down", 20);
    case "wait": return p.text !== undefined ? `「${clip(p.text, 30)}」` : `${p.ms ?? ""}ms`;
    case "eval": return p.__label ?? "脚本";
    case "snapshot": return p.mode ?? "full";
    default: return p.tab ? `tab=${p.tab}` : "";
  }
}

function fmtStep(s: Step): string {
  return `#${s.i} ${s.action}${s.arg ? " " + s.arg : ""} · ${s.ms}ms · ${s.ok ? "✓" : "✗"}${s.at ? `（${s.at}）` : ""}${s.err ? " " + clip(s.err, 120) : ""}`;
}

/** 字符串像不像统一目标（@N、eN、[ref=N]、text=、[x,y]、CSS 选择器）；否则当作「按文字找」 */
const HTML_TAGS = new Set(
  "a abbr article aside audio b button canvas dd details dialog div dl dt em fieldset figure footer form h1 h2 h3 h4 h5 h6 header i iframe img input label li main nav ol option p pre section select span summary table tbody td textarea th thead tr ul video".split(" ")
);
function looksLikeTarget(s: string): boolean {
  const t = s.trim();
  if (!t) return false;
  if (/^@?e?\d+$/i.test(t) || /^\[\s*ref\s*=/i.test(t) || /^text\s*=/i.test(t)) return true;
  if (/^\[\s*-?\d+(\.\d+)?\s*,\s*-?\d+(\.\d+)?\s*\]$/.test(t)) return true;
  if (/^[#.\[]/.test(t) || t.includes(">>>")) return true;
  const m = t.match(/^([a-z][a-z0-9]*)([\s.#\[:>+~]|$)/i);
  return !!m && HTML_TAGS.has(m[1].toLowerCase()) && !/[\u4e00-\u9fff]/.test(t.replace(/\[[^\]]*\]|"[^"]*"|'[^']*'/g, ""));
}
function tgt(t: any): any {
  return Array.isArray(t) ? t : String(t ?? "").trim();
}

function makeB(st: RunState, tab?: string): any {
  const sig = st.signal;
  const isRef = (s: any) => Array.isArray(s) || (typeof s === "string" && looksLikeTarget(s));

  /** 所有 b.* 的总入口：致命错误即停 + 轨迹 */
  const exec = async (action: string, params: Record<string, any>, opts: { trace?: boolean; label?: string; stack?: string } = {}) => {
    if (st.fatal) throw st.fatal;
    const traced = opts.trace !== false;
    const step: Step | null = traced
      ? { i: st.trace.length + 1, action: opts.label ?? action, arg: argSummary(action, params), ms: 0, ok: false, at: codeLocation(opts.stack ?? new Error().stack) }
      : null;
    if (step) st.trace.push(step);
    const t0 = Date.now();
    const { __label, ...clean } = params;
    try {
      const j = await call(action, clean, sig);
      if (j.url) st.lastUrl = j.url;
      if (step) step.ok = true;
      return j;
    } catch (e: any) {
      if (step) step.err = e?.message ?? String(e);
      if (e instanceof FatalError && !st.fatal) {
        st.fatal = e;
        st.abort();
      }
      if (st.fatal) throw st.fatal;
      throw e;
    } finally {
      if (step) step.ms = Date.now() - t0;
    }
  };
  const base = (extra: Record<string, any> = {}) => ({ snapshot: false, ...(tab ? { tab } : {}), ...extra });
  const go = (action: string, extra: Record<string, any> = {}, opts: { trace?: boolean; label?: string; stack?: string } = {}) =>
    exec(action, base(extra), opts);

  const sleep = (ms: number) =>
    new Promise<void>((resolve, reject) => {
      if (st.fatal) return reject(st.fatal);
      if (sig.aborted) return reject(new BrowserError("已中止"));
      const t = setTimeout(() => {
        sig.removeEventListener("abort", onAbort);
        resolve();
      }, Math.max(0, Number(ms) || 0));
      const onAbort = () => {
        clearTimeout(t);
        reject(st.fatal ?? new BrowserError("已中止"));
      };
      sig.addEventListener("abort", onAbort, { once: true });
    });

  /** 执行页面脚本并解析信封 */
  const runJs = async (script: string | Function, args: any[], opts: { timeout?: number; trace?: boolean; stack?: string; label?: string } = {}) => {
    const body = wrapScript(script, args);
    const label = opts.label ?? (typeof script === "function" ? "fn" : clip(script, 50));
    const extra: Record<string, any> = { script: body, fn: true, __label: label };
    if (opts.timeout) extra.timeout = opts.timeout;
    const j = await go("eval", extra, { trace: opts.trace, label: "js", stack: opts.stack });
    const v = j.result;
    if (typeof v === "string" && (v.startsWith("脚本出错: ") || /^脚本超时（\d+ms）未返回/.test(v))) throw new BrowserError(v); // 兼容旧宿主
    const d = decodeEnvelope(v);
    if (d.enveloped) return d.value;
    if (v === "undefined") return undefined;
    return v;
  };

  const b: any = {
    async open(url: string, opts: { timeout?: number } = {}) {
      const j = await go("open", { url, ...opts });
      const r: any = { url: j.url, title: j.title ?? "" };
      if (j.loaded !== undefined) r.loaded = j.loaded;
      if (j.http_status !== undefined) r.http_status = j.http_status;
      if (j.load_error) r.load_error = j.load_error;
      return r;
    },
    /** 语义树快照：{scope:"full"|"viewport", max_chars}；返回文本，元素写成 [ref=N]，之后用 "@N" 操作 */
    async snapshot(opts: { scope?: string; mode?: string; max_chars?: number } = {}) {
      const j = await go("snapshot", { snapshot: true, ...opts });
      return String(j.snapshot ?? j.text ?? "");
    },
    /** 目标：@N / CSS 选择器 / text=文字 / [x,y]；其它字符串按包含的文字找 */
    async click(target: any, opts: { js?: boolean; timeout?: number } = {}) {
      const j = await go("click", isRef(target) ? { target: tgt(target), ...opts } : { text: String(target), ...opts });
      return String(j.result ?? "");
    },
    async fill(target: any, text: string, opts: { submit?: boolean; clear?: boolean; timeout?: number } = {}) {
      const j = await go("fill", { target: tgt(target), text: String(text ?? ""), ...opts });
      return String(j.result ?? "");
    },
    async select(target: any, value: string) {
      const j = await go("select", { target: tgt(target), value: String(value) });
      return String(j.result ?? "");
    },
    async hover(target: any) {
      return String((await go("hover", { target: tgt(target) })).result ?? "");
    },
    async scrollIntoView(target: any) {
      return String((await go("scroll_into_view", { target: tgt(target) })).result ?? "");
    },
    /** 一次执行多步：[{action:"click",target:"@3"},{action:"fill",target:"@5",value:"…"},{action:"press",key:"Enter"}]；返回每步结果数组，有失败步骤时抛错 */
    async batch(steps: any[], opts: { stopOnError?: boolean } = {}) {
      const j = await exec("batch", { ...(tab ? { tab } : {}), steps, ...opts }, { label: "batch" });
      const arr = j.steps ?? j.results ?? [];
      const bad = Array.isArray(arr) ? arr.find((x: any) => x && (x.ok === false || x.error)) : null;
      if (bad && opts.stopOnError !== false) throw new BrowserError(`batch 第 ${bad.i ?? "?"} 步 ${bad.action ?? ""} 失败：${unknownHint("batch", String(bad.error ?? ""))}`);
      return arr;
    },
    /**
     * 一边滚动一边找，直到目标出现（CSS 选择器 / text=文字 / 页面函数返回真值）；出现返回 true，滚到底仍没有返回 false。
     * opts: {max=15 次, direction="down", amount=0.8 屏}
     */
    async scrollUntil(target: string | Function, opts: { max?: number; direction?: string; amount?: number } = {}) {
      const max = Math.max(1, Math.min(Number(opts.max) || 15, 60));
      const probe: Function =
        typeof target === "function"
          ? target
          : new Function(
              "t",
              "if (t.indexOf('text=') === 0) { var s = t.slice(5).replace(/^[\"']|[\"']$/g, ''); return (document.body.innerText || '').indexOf(s) >= 0; }" +
                "var e = document.querySelector(t); if (!e) return false; var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && r.top < innerHeight && r.bottom > 0;"
            );
      const args = typeof target === "function" ? [] : [String(target)];
      const step: Step = { i: st.trace.length + 1, action: "scrollUntil", arg: typeof target === "function" ? "fn" : clip(target, 40), ms: 0, ok: false, at: codeLocation(new Error().stack) };
      if (st.fatal) throw st.fatal;
      st.trace.push(step);
      const t0 = Date.now();
      const check = async () => {
        try {
          return await runJs(probe, args, { timeout: 3000, trace: false });
        } catch (e: any) {
          if (st.fatal || sig.aborted) throw st.fatal ?? e;
          return false;
        }
      };
      try {
        for (let i = 0; i <= max; i++) {
          if (await check()) { step.ok = true; return true; }
          if (i === max) break;
          const j = await go("scroll", { direction: opts.direction || "down", amount: opts.amount ?? 0.8 }, { trace: false });
          if (/没有滚动/.test(String(j.result ?? ""))) {
            await sleep(500); // 到底了：给懒加载一点时间再查最后一次
            const v = await check();
            step.ok = true;
            if (!v) step.err = "滚到底仍未出现";
            return !!v;
          }
          await sleep(250);
        }
        step.ok = true;
        step.err = `滚动 ${max} 次仍未出现`;
        return false;
      } catch (e: any) {
        step.err = e?.message ?? String(e);
        throw e;
      } finally {
        step.ms = Date.now() - t0;
      }
    },
    /**
     * 按选择器批量提取：b.extract(".item", {title: ".name", price: ".price", link: "a@href"}, {limit: 50})
     * 字段写「子选择器」取文字，「子选择器@属性」取属性，"@属性" 取自身属性，"" 取自身文字；不给 fields 返回每项文字。
     */
    async extract(selector: string, fields?: Record<string, string> | null, opts: { limit?: number } = {}) {
      const fn = new Function(
        "sel", "f", "limit",
        "function txt(e){ return ((e.innerText != null ? e.innerText : e.textContent) || '').replace(/\\s+/g, ' ').trim(); }" +
        "function one(el, spec){ var s = String(spec), attr = '', at = s.lastIndexOf('@');" +
        " if (at >= 0 && !/[\\]\"']/.test(s.slice(at))) { attr = s.slice(at + 1); s = s.slice(0, at); }" +
        " var t = s.trim() ? el.querySelector(s.trim()) : el; if (!t) return null;" +
        " if (attr) return (attr === 'href' || attr === 'src') ? (t[attr] || t.getAttribute(attr)) : t.getAttribute(attr);" +
        " return txt(t); }" +
        "var list = Array.prototype.slice.call(document.querySelectorAll(sel), 0, limit);" +
        "return list.map(function(el){ if (!f) return txt(el); var o = {}; for (var k in f) o[k] = one(el, f[k]); return o; });"
      );
      return runJs(fn, [selector, fields ?? null, Math.max(1, Math.min(Number(opts.limit) || 50, 500))], { label: "extract", stack: new Error().stack });
    },
    async press(key: string) {
      const j = await go("press", { key });
      return String(j.result ?? "");
    },
    async scroll(dirOrRef: string = "down", amount?: number) {
      const p: any = isRef(dirOrRef) ? { ref: dirOrRef.trim() } : { direction: dirOrRef || "down" };
      if (amount !== undefined) p.amount = amount;
      const j = await go("scroll", p);
      return String(j.result ?? "");
    },
    async back() { return String((await go("back")).result ?? ""); },
    async forward() { return String((await go("forward")).result ?? ""); },
    async reload() { return String((await go("reload")).result ?? ""); },
    async wait(arg: number | string, timeout?: number) {
      if (typeof arg === "number") {
        await go("wait", { ms: arg });
        return true;
      }
      const j = await go("wait", { text: String(arg), timeout: timeout ?? 15000 });
      return j.found !== undefined ? !!j.found : String(j.result ?? "").startsWith("页面已出现");
    },
    /** b.js(fn, ...args) 传函数（推荐，参数经 JSON 传入页面）；b.js("脚本", {timeout}) 字符串形式兼容 */
    async js(script: string | Function, ...rest: any[]) {
      const stack = new Error().stack;
      if (typeof script === "function") return runJs(script, rest, { stack });
      const opts = rest[0] && typeof rest[0] === "object" ? rest[0] : {};
      return runJs(String(script ?? ""), [], { timeout: opts.timeout, stack });
    },
    /** 等 CSS 选择器出现且可见，或等页面里的函数返回真值；返回真值或 false（超时） */
    async waitFor(target: string | Function, timeout: number = 15000) {
      const stack = new Error().stack;
      const label = typeof target === "function" ? "fn" : clip(target, 40);
      const step: Step = { i: st.trace.length + 1, action: "waitFor", arg: label, ms: 0, ok: false, at: codeLocation(stack) };
      if (st.fatal) throw st.fatal;
      st.trace.push(step);
      const t0 = Date.now();
      const end = t0 + Math.max(0, Number(timeout) || 0);
      const probe: Function =
        typeof target === "function"
          ? target
          : new Function("sel", "var e = document.querySelector(sel); return !!e && (e.offsetParent !== null || e.getClientRects().length > 0);");
      const args = typeof target === "function" ? [] : [String(target)];
      try {
        while (true) {
          let v: any = false;
          try {
            v = await runJs(probe, args, { timeout: 3000, trace: false });
          } catch (e: any) {
            if (st.fatal || sig.aborted) throw st.fatal ?? e;
            v = false; // 页面跳转/加载中：继续等
          }
          if (v) {
            step.ok = true;
            return v;
          }
          if (Date.now() >= end) {
            step.ok = true;
            step.err = "超时未出现";
            return false;
          }
          await sleep(Math.min(300, Math.max(0, end - Date.now())));
        }
      } catch (e: any) {
        step.err = e?.message ?? String(e);
        throw e;
      } finally {
        step.ms = Date.now() - t0;
      }
    },
    async url() {
      return String((await go("status", {}, { trace: false })).url ?? "");
    },
    async screenshot() {
      const j = await go("screenshot");
      if (st.shots.length < MAX_SHOTS) st.shots.push({ data: j.image, mimeType: j.mime || "image/jpeg" });
      else st.logs.push(`（截图已达 ${MAX_SHOTS} 张上限，本张未附上）`);
      return j.url ?? "";
    },
    sleep,
    log(...args: any[]) {
      st.logs.push(args.map((a) => (typeof a === "string" ? a : show(a))).join(" "));
    },
    async tool(spec: string, args: any = {}) {
      if (st.fatal) throw st.fatal;
      const fn = await loadTool(spec);
      return await fn(b, args ?? {});
    },
    // 标签页：默认操作 Pi 专属标签；b.tab("user") 操作用户正在看的标签，b.tab(id) 操作指定标签
    tab(id: string) {
      return makeB(st, id);
    },
    async tabs() {
      return (await exec("tabs", {}, { trace: false })).tabs ?? [];
    },
    /** 新标签：container 默认 "new"（独立容器、独立登录态）；"same" 沿用当前标签的容器，"Default" 共享容器，或容器名 */
    async newTab(url?: string, opts: { container?: string; background?: boolean } = {}) {
      const j = await exec("tab_new", { ...(url ? { url } : {}), ...opts });
      return makeB(st, j.tab);
    },
    async closeTab() {
      await exec("tab_close", tab ? { tab } : {});
    },
    // 直接发送任意单步 action（与 browser 工具参数相同）
    async call(action: string, params: Record<string, any> = {}) {
      return await go(action, params);
    },
    // ---- 油猴脚本 / 调试（宿主 1.9.0-beta.22+）
    /** 页面内查找文字，返回匹配数 */
    async find(text: string) {
      const j = await go("find", { text: String(text) });
      const n = j.count ?? j.matches ?? j.found;
      return typeof n === "number" ? n : Number(String(j.result ?? "").match(/\d+/)?.[0] ?? 0);
    },
    /** 页面 console 日志和 JS 错误（最近 max 条） */
    async console(max: number = 100, opts: { clear?: boolean } = {}) {
      const j = await go("console", { max, ...opts });
      return j.logs ?? j.console ?? j.result ?? [];
    },
    /** 用户脚本列表 */
    async scripts() {
      const j = await go("scripts", {});
      return j.scripts ?? j.result ?? [];
    },
    /** 在当前页注入 JS；persist {name, match} 时安装成用户脚本 */
    async inject(js: string, persist?: { name: string; match: string | string[] }) {
      const j = await go("inject", persist ? { js, persist } : { js });
      return j.result ?? "";
    },
  };
  b.type = b.fill;
  b.eval = b.js;
  b.goto = b.open;
  b.tabId = tab ?? "pi";
  return b;
}

async function runCode(code: string, timeoutSec: number, tab: string | undefined, outer?: AbortSignal) {
  const ctrl = new AbortController();
  const onOuter = () => ctrl.abort();
  if (outer) {
    if (outer.aborted) ctrl.abort();
    else outer.addEventListener("abort", onOuter, { once: true });
  }
  const ms = Math.round(Math.max(1, Math.min(timeoutSec || 120, 600)) * 1000);
  const timer = setTimeout(() => ctrl.abort(), ms);
  const st: RunState = { logs: [], shots: [], signal: ctrl.signal, abort: () => ctrl.abort(), lastUrl: "", trace: [], fatal: null };
  const b = makeB(st, tab);
  const ctx = { signal: ctrl.signal, tab: tab ?? "pi", timeoutMs: ms };
  const t0 = Date.now();
  let value: any;
  let error: any = null;
  try {
    let fn: any;
    try {
      fn = new AsyncFunction("b", "ctx", code);
    } catch (e: any) {
      throw new BrowserError(`代码语法错误：${e?.message ?? e}`);
    }
    const aborted = new Promise((_, reject) => {
      const fire = () =>
        reject(st.fatal ?? new BrowserError(outer?.aborted ? "已中止" : `run 超时（${ms / 1000} 秒）`));
      if (ctrl.signal.aborted) fire();
      else ctrl.signal.addEventListener("abort", fire, { once: true });
    });
    value = await Promise.race([fn(b, ctx), aborted]);
    if (st.fatal) throw st.fatal; // 代码自己 catch 掉了致命错误也要停
  } catch (e: any) {
    error = st.fatal ?? e;
  } finally {
    clearTimeout(timer);
    outer?.removeEventListener("abort", onOuter);
    if (!ctrl.signal.aborted) ctrl.abort(); // 让仍在等待的请求 / sleep 退出（在途请求会补发 cancel）
  }

  // ---- 分预算拼输出：错误 + 网址 + 最后几步轨迹必保留，日志留尾部，返回值最后填
  const head: string[] = [];
  if (error) {
    const msg = error instanceof BrowserError ? error.message : error && error.name && error.message !== undefined ? `${error.name}: ${error.message}` : String(error);
    let url = (error as any)?.url || st.lastUrl;
    if (!st.fatal) {
      try {
        const j = await call("status", tab ? { tab, timeout: 3000 } : { timeout: 3000 }, undefined, 3000);
        url = j.url ?? url;
      } catch (e: any) {}
    }
    const failed = st.trace.length ? st.trace[st.trace.length - 1] : null;
    const where = codeLocation(error?.stack) || (failed && !failed.ok ? failed.at : "");
    if (st.fatal) {
      const kind = /叫停/.test(msg) ? "已叫停" : /用户正在操作/.test(msg) ? "用户接手了浏览器，已暂停" : "已终止";
      head.push(`⛔ ${kind}，做到第 ${st.trace.length} 步：${msg}`);
    } else {
      head.push(`出错: ${msg}`);
    }
    if (where) head.push(`出错位置: ${where}`);
    head.push(`当前网址: ${url || "(未知)"}`);
    if (st.trace.length) {
      const last = st.trace.slice(-TRACE_KEEP);
      head.push(`最后 ${last.length} 步（共 ${st.trace.length} 步）:\n` + last.map(fmtStep).join("\n"));
    }
  }
  const footer = `（共 ${st.trace.length} 步，用时 ${((Date.now() - t0) / 1000).toFixed(1)} 秒）`;
  const headText = head.join("\n");
  let logText = st.logs.length ? "日志:\n" + tailOf(st.logs.join("\n"), LOG_TAIL) : "";
  const parts: string[] = [];
  if (headText) parts.push(headText);
  if (logText) parts.push(logText);
  if (!error) {
    const used = parts.join("\n\n").length + footer.length + 40;
    const budget = Math.max(2000, MAX_OUT - used);
    let vt = show(value);
    if (vt.length > budget) {
      vt = compact(value);
      if (vt.length > budget) vt = vt.slice(0, budget) + `\n…（返回值已截断，紧凑 JSON 共 ${vt.length} 字符；请在代码里只返回需要的字段或用 slice 分批）`;
      else vt += "\n（返回值较大，已改用紧凑 JSON）";
    }
    parts.push("返回值:\n" + vt);
  }
  parts.push(footer);
  let text = parts.join("\n\n");
  if (text.length > MAX_OUT) text = headText ? truncate(headText + "\n\n" + footer, MAX_OUT) : truncate(text);
  return { text, shots: st.shots, ok: !error };
}

// ------------------------------------------------------------------ 单步结果

const PLAIN_KEYS = new Set(["success", "result", "url", "title", "tab", "snapshot", "image", "mime", "req_id", "loaded", "http_status", "load_error", "refs", "changed", "text", "local"]);

/** batch 每步结果：#序号 动作 ✓/✗ 结果；快照步骤原样附上文本 */
function formatSteps(arr: any[]): string {
  const out: string[] = [];
  for (let i = 0; i < arr.length; i++) {
    const s = arr[i];
    if (!s || typeof s !== "object") { out.push(`#${i + 1} ${compact(s)}`); continue; }
    const ok = s.ok !== false && !s.error;
    const head = `#${s.i ?? i + 1} ${s.action ?? ""}${s.target !== undefined ? " " + targetStr(s.target) : ""} ${ok ? "✓" : "✗"}`;
    const msg = ok ? (s.result !== undefined ? String(s.result) : "") : unknownHint("batch", String(s.error ?? "失败")) + (s.hint && !/unknown_ref/.test(String(s.error)) ? `（${s.hint}）` : "");
    const extra: string[] = [];
    if (s.changed === true) extra.push("页面有变化");
    if (s.url && s.url !== arr[i - 1]?.url) extra.push(`网址 ${s.url}`);
    out.push(head + (msg ? " " + msg : "") + (extra.length ? `（${extra.join("，")}）` : ""));
    const snap = s.snapshot ?? s.text;
    if (typeof snap === "string" && snap) out.push(snap);
  }
  return out.join("\n");
}

/** 管理类动作：把宿主返回的数据字段原样列出（字段名以宿主为准，不做假设） */
function dataFields(j: any): string {
  const out: string[] = [];
  for (const k of Object.keys(j || {})) {
    if (PLAIN_KEYS.has(k)) continue;
    const v = j[k];
    if (typeof v === "string") out.push(v.includes("\n") || v.length > 120 ? `${k}:\n${v}` : `${k}: ${v}`);
    else out.push(`${k}: ${show(v)}`);
  }
  return out.join("\n");
}

function formatStep(j: any, action = ""): { text: string; image?: { data: string; mimeType: string } } {
  const parts: string[] = [];
  if (j.result !== undefined) parts.push(String(j.result));
  if (action === "batch") {
    const arr = j.steps ?? j.results;
    if (Array.isArray(arr)) parts.push(formatSteps(arr) + (j.local ? "\n（当前宿主不支持 batch，已在本地逐步执行）" : ""));
    const rest: any = { ...j };
    delete rest.steps;
    delete rest.results;
    const d = dataFields(rest);
    if (d) parts.push(d);
  } else if (MANAGE.has(action) || action === "status") {
    const d = dataFields(j);
    if (d) parts.push(d);
  }
  const hints = hostHints(j);
  if (j.changed === true && MUTATING.has(action)) hints.push("（页面有变化）");
  else if (j.changed === false && MUTATING.has(action)) hints.push("（页面没有变化）");
  if (hints.length) parts.push(hints.join("\n"));
  const snap = j.snapshot !== undefined ? j.snapshot : action === "snapshot" && typeof j.text === "string" ? j.text : undefined;
  if (snap !== undefined) parts.push(String(snap));
  else if (j.url) parts.push(`网址: ${j.url}${j.title ? `\n标题: ${j.title}` : ""}`);
  if (j.image) return { text: parts.join("\n\n") || "截图", image: { data: j.image, mimeType: j.mime || "image/jpeg" } };
  return { text: parts.join("\n\n") || JSON.stringify(j) };
}

// ------------------------------------------------------------------ 注册工具

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "browser",
    label: "Browser",
    description:
      "操控 PiMet 手机上的「Pi 浏览器」（真实 Android WebView，用户能看到并随时接手）。用于需要真正打开网页的任务：动态页面、登录后的网站、填表、点按钮、看本地页面。" +
      "用法细节和常见坑见 pimet-browser 技能，第一次用前先读它。\n" +
      "【推荐流程】tab_new(url, container:\"new\") 在自己的后台容器标签里干活（拿到 tab id，之后都带上 tab）→ snapshot（语义树，元素带 [ref=N]）→ " +
      "batch(steps:[{action:\"click\",target:\"@3\"},{action:\"fill\",target:\"@5\",value:\"…\"},{action:\"press\",key:\"Enter\"},{action:\"snapshot\",scope:\"viewport\"}]) 一次做完多步 → " +
      "要提取数据或流程较长时用 run 一次完成（打开→操作→提取→return）。每个动作自带等待，不用另外 wait。ref 失效（unknown_ref）就重新 snapshot。\n" +
      "【目标 target】@N（最近一次快照的 ref）| CSS 选择器 | text=文字 | [x,y] 坐标。\n" +
      "【run】code 是 async 函数体：b.open b.snapshot b.click b.fill b.select b.press b.hover b.scroll b.scrollIntoView b.scrollUntil b.waitFor b.extract(选择器,{字段}) b.batch " +
      "b.js(fn,...args) b.screenshot b.log b.tool('站点/工具名',args) b.newTab(url,{container}) b.tab(id) b.call(action,参数)；return 结果。出错会给出错位置、当前网址和最后几步轨迹。\n" +
      "【网站任务先 action=tools】看 sites/ 下的现成工具和 notes.md；成功后沉淀工具和经验。\n" +
      "【单步】open | snapshot(scope=full|viewport) | click | fill | type | select | hover | scroll_into_view | press | scroll | back | forward | reload | wait | eval | screenshot | status | downloads | show | hide | home | close | reload_ui。\n" +
      "【标签/容器/历史】tab_new(url,container:new|same|Default|容器名,background) | tabs | tab_switch(tab) | tab_close(tab) | containers | container_keep(name,keep) | history(q,limit,offset) | history_delete(url) | history_clear(since_ms)。" +
      "需要用户的登录态时用 container:\"same\"（沿用用户当前标签的容器）或用户指定的容器。\n" +
      "【油猴脚本】scripts | script_get(id) | script_install(code 或 url) | script_enable(id,on) | script_remove(id) | script_update(id?) | script_values(id,set?,delete?) | menu_commands | menu_run(id,cmd) | inject(script,persist?)；调试用 console、find、wait_for。\n" +
      "【自定义浏览器】config_get | config_set(patch) | ua(ua) | desktop(on) | zoom(percent) | cookies(url,set?,container?) | clear_data(what)。script_remove、clear_data、history_clear 前先征得用户同意。\n" +
      "不传 tab 时在 Pi 专属标签操作；用户明确要你操作他当前页面才传 tab=\"user\"。不要擅自 show / tab_switch 打断用户。" +
      "返回「用户已叫停」或「用户正在操作标签」（user_takeover）时立即停止所有浏览器操作、告诉用户进展，等用户允许后再继续，不要重试。验证码、支付、登录确认等交给用户本人完成。",
    parameters: Type.Object({
      action: Type.String({
        description:
          "run | batch | tools | open | snapshot | click | fill | type | select | hover | scroll_into_view | press | scroll | back | forward | reload | home | wait | eval | screenshot | show | hide | close | reload_ui | status | downloads" +
          " | tab_new | tabs | tab_switch | tab_close | containers | container_keep | history | history_delete | history_clear" +
          " | scripts | script_get | script_install | script_enable | script_remove | script_update | script_values | menu_commands | menu_run | console | find | wait_for | inject" +
          " | config_get | config_set | ua | desktop | zoom | cookies | clear_data",
      }),
      code: Type.Optional(Type.String({ description: "run 的代码：async 函数体，可用 b、ctx 和 await，用 return 返回结果；script_install 时是完整用户脚本源码（含 ==UserScript== 头）" })),
      tab: Type.Optional(Type.String({ description: "操作哪个标签：默认 Pi 专属标签；\"user\" = 用户当前的标签；或 tab_new / tabs 给出的 id（如 t3）；tab_switch / tab_close 的目标标签" })),
      target: Type.Optional(
        Type.Union([Type.String(), Type.Array(Type.Number())], {
          description: "click / fill / type / select / hover / scroll_into_view 的目标：@N（快照里的 [ref=N]）| CSS 选择器 | text=文字 | [x,y] 坐标",
        })
      ),
      steps: Type.Optional(
        Type.Array(Type.Object({ action: Type.String() }, { additionalProperties: true }), {
          description: "batch 的步骤，每步与单步参数相同，如 {action:\"click\",target:\"@3\"} {action:\"fill\",target:\"@5\",value:\"…\"} {action:\"press\",key:\"Enter\"} {action:\"snapshot\",scope:\"viewport\"}",
        })
      ),
      stopOnError: Type.Optional(Type.Boolean({ description: "batch 某步失败时停止，默认 true" })),
      scope: Type.Optional(Type.String({ description: "snapshot 范围：full（整页，默认）| viewport（只要当前屏，最省）" })),
      container: Type.Optional(Type.String({ description: "tab_new：new（新容器，默认，独立登录态）| same（沿用当前标签的容器）| Default（共享容器）| 容器名；cookies：哪个容器（默认当前标签的）" })),
      background: Type.Optional(Type.Boolean({ description: "tab_new 在后台打开，不切走用户当前标签，默认 true" })),
      name: Type.Optional(Type.String({ description: "container_keep 的容器名（containers 列出）" })),
      keep: Type.Optional(Type.Boolean({ description: "container_keep：true = 关闭标签后保留容器数据" })),
      q: Type.Optional(Type.String({ description: "history 搜索词（网址或标题包含，不区分大小写）" })),
      limit: Type.Optional(Type.Number({ description: "history 返回条数" })),
      offset: Type.Optional(Type.Number({ description: "history 跳过条数（翻页）" })),
      since_ms: Type.Optional(Type.Number({ description: "history_clear：只清除这个时间戳（毫秒）之后的；不填 = 全部" })),
      site: Type.Optional(Type.String({ description: "tools 时只看某个站点，并附上它的 notes.md" })),
      url: Type.Optional(Type.String({ description: "open / tab_new 的网址或搜索词；script_install 的脚本网址（.user.js）；cookies 的网址；history_delete 的网址或前缀" })),
      ref: Type.Optional(Type.String({ description: "旧写法的元素编号（同 target），如 @12" })),
      text: Type.Optional(Type.String({ description: "type 要输入的文字；click 时按包含的文字查找元素；wait / wait_for 时等待出现的文字；find 要查找的文字" })),
      clear: Type.Optional(Type.Boolean({ description: "type 前清空原内容，默认 true；console 时读完清空日志" })),
      submit: Type.Optional(Type.Boolean({ description: "type 后提交表单 / 回车" })),
      value: Type.Optional(Type.String({ description: "fill 要输入的文字；select 的选项值或文字" })),
      key: Type.Optional(Type.String({ description: "press 的按键" })),
      direction: Type.Optional(Type.String({ description: "scroll 方向 up/down/left/right，默认 down" })),
      amount: Type.Optional(Type.Number({ description: "scroll 滚动的屏数，默认 0.8" })),
      script: Type.Optional(Type.String({ description: "eval 执行的 JS：表达式或带 return 的语句体，可用 await；inject 要注入当前页的 JS" })),
      ms: Type.Optional(Type.Number({ description: "wait 的毫秒数" })),
      js: Type.Optional(Type.Boolean({ description: "click 时用 JS 点击而不是模拟真实触摸（被遮挡或真实点击无效时用）" })),
      mode: Type.Optional(Type.String({ description: "旧快照模式：interactive = 只列可交互元素（单步变更操作后附带的快照默认用它）" })),
      snapshot: Type.Optional(Type.Boolean({ description: "单步操作后是否返回快照，默认 true" })),
      max_chars: Type.Optional(Type.Number({ description: "快照最大字符数，snapshot 默认 12000（超出时折叠长列表），变更操作后附带的快照默认 2500" })),
      timeout: Type.Optional(Type.Number({ description: "单步：等待加载 / wait / eval 的超时毫秒，默认 20000，最大 120000；run：整段代码的超时秒数，默认 120，最大 600" })),
      id: Type.Optional(Type.String({ description: "用户脚本 id（scripts 列出）：script_get / script_enable / script_remove / script_update（不填=全部检查更新）/ script_values / menu_run" })),
      on: Type.Optional(Type.Boolean({ description: "script_enable：true 启用 / false 停用；desktop：true 桌面版 / false 手机版" })),
      set: Type.Optional(
        Type.Union([Type.String(), Type.Object({}, { additionalProperties: true })], {
          description: "script_values：要写入的值 {键: 值}；cookies：要设置的 Cookie 字符串（如 \"a=1; path=/\"）",
        })
      ),
      delete: Type.Optional(Type.Array(Type.String(), { description: "script_values：要删除的键" })),
      cmd: Type.Optional(Type.String({ description: "menu_run 的菜单命令 id（menu_commands 列出）" })),
      max: Type.Optional(Type.Number({ description: "console 返回最近多少条，默认 100" })),
      patch: Type.Optional(
        Type.Union([Type.Object({}, { additionalProperties: true }), Type.String()], {
          description: "config_set：合并进 config.json 的字段，如 {\"theme\":{...}} {\"buttons\":[...]} {\"home\":\"...\"} {\"showButtonBar\":true}；buttons 会整体替换，先 config_get 再改",
        })
      ),
      ua: Type.Optional(Type.String({ description: "ua：自定义 User-Agent 字符串；空字符串或 default = 恢复默认" })),
      percent: Type.Optional(Type.Number({ description: "zoom：页面缩放百分比，如 100、125" })),
      what: Type.Optional(Type.String({ description: "clear_data：cookies | cache | storage | all" })),
      selector: Type.Optional(Type.String({ description: "wait_for：等待出现的 CSS 选择器（与 text 二选一）" })),
      timeout_ms: Type.Optional(Type.Number({ description: "wait_for 的超时毫秒，默认 15000，最大 30000" })),
      persist: Type.Optional(
        Type.Object(
          {
            name: Type.String({ description: "脚本名" }),
            match: Type.Union([Type.String(), Type.Array(Type.String())], { description: "@match 规则，如 https://example.com/*" }),
          },
          { description: "inject：同时安装成用户脚本，以后匹配的页面自动运行" }
        )
      ),
    }),
    async execute(_toolCallId, params, signal) {
      const { action, ...rest } = params as Record<string, any>;
      const tab = rest.tab;
      if (action === "run") {
        const code = String(rest.code ?? "");
        if (!code.trim()) return { content: [{ type: "text", text: "run 需要 code（async 函数体）" }], details: { action, tab, ok: false }, isError: true };
        const r = await runCode(code, Number(rest.timeout) || 120, tab, signal);
        const content: any[] = [{ type: "text", text: r.text }];
        for (const s of r.shots) content.push({ type: "image", data: s.data, mimeType: s.mimeType });
        return { content, details: { action, tab, ok: r.ok }, isError: !r.ok };
      }
      if (action === "tools") {
        try {
          return { content: [{ type: "text", text: truncate(listTools(rest.site)) }], details: { action, ok: true } };
        } catch (e: any) {
          return { content: [{ type: "text", text: `失败: ${e?.message ?? e}` }], details: { action, ok: false }, isError: true };
        }
      }

      let req: Record<string, any> = { ...rest };
      if (action !== "script_install" && action !== "inject") delete req.code;
      delete req.site;
      req = adaptParams(String(action), req);
      if (MUTATING.has(String(action)) && req.snapshot !== false) {
        if (req.mode === undefined) req.mode = "interactive";
        if (req.max_chars === undefined) req.max_chars = 2500;
      }
      const isEval = action === "eval" || action === "js";
      const fail = (text: string) => ({ content: [{ type: "text", text: truncate(text) }], details: { action, tab, ok: false }, isError: true });
      if (isEval && typeof req.script === "string" && req.script.trim()) {
        try {
          req.script = wrapScript(req.script);
        } catch (e: any) {
          return fail(`失败: ${e?.message ?? e}`);
        }
        req.fn = true;
      }
      let j: any;
      try {
        // call 负责：参数翻译、旧宿主同义动作（tab_new→new_tab…）、旧宿主没有 batch 时本地逐步执行
        j = await call(String(action), req, signal);
      } catch (e: any) {
        const msg = e?.message ?? String(e);
        if (msg === "已中止") return fail("已中止（已通知宿主取消该操作）");
        return fail(`失败: ${msg}${e?.url ? `\n网址: ${e.url}` : ""}`);
      }
      if (isEval) {
        const v = j.result;
        if (typeof v === "string" && (v.startsWith("脚本出错: ") || /^脚本超时（\d+ms）未返回/.test(v))) return fail(`失败: ${v}`);
        const d = decodeEnvelope(v);
        if (d.enveloped) j.result = show(d.value);
      }
      const f = formatStep(j, String(action));
      const content: any[] = [{ type: "text", text: truncate(f.text) }];
      if (f.image) content.push({ type: "image", data: f.image.data, mimeType: f.image.mimeType });
      return { content, details: { action, tab: j.tab ?? tab, ok: true } };
    },
  });
}
