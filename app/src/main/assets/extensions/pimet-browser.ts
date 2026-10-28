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
const MUTATING = new Set(["click", "tap", "type", "fill", "select", "press", "key", "scroll", "back", "forward", "reload"]);

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
  if (signal?.aborted) throw new BrowserError("已中止");
  const t = Math.min(Number(params.timeout) || 20000, 120000);
  const extra = action === "wait" ? Math.min(Number(params.ms) || 0, 60000) : 0;
  const r = await postBridge({ ...params, action: "browser", browser_action: action }, clientMs ?? t + extra + 30000, signal);
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
    const e = hostError(String(j.error ?? "操作失败"));
    (e as any).url = j.url;
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
    case "click": return clip(p.ref ?? p.text, 40);
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

function makeB(st: RunState, tab?: string): any {
  const sig = st.signal;
  const isRef = (s: any) => typeof s === "string" && /^e\d+$/.test(s.trim());

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
    async snapshot(opts: { mode?: string; max_chars?: number } = {}) {
      const j = await go("snapshot", { snapshot: true, ...opts });
      return String(j.snapshot ?? "");
    },
    async click(target: string, opts: { js?: boolean; timeout?: number } = {}) {
      const j = await go("click", isRef(target) ? { ref: target.trim(), ...opts } : { text: String(target), ...opts });
      return String(j.result ?? "");
    },
    async fill(ref: string, text: string, opts: { submit?: boolean; clear?: boolean; timeout?: number } = {}) {
      const j = await go("type", { ref, text: String(text ?? ""), ...opts });
      return String(j.result ?? "");
    },
    async select(ref: string, value: string) {
      const j = await go("select", { ref, value: String(value) });
      return String(j.result ?? "");
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
    async newTab(url?: string) {
      const j = await exec("new_tab", url ? { url } : {});
      return makeB(st, j.tab);
    },
    async closeTab() {
      await exec("close_tab", tab ? { tab } : {});
    },
    // 直接发送任意单步 action（与 browser 工具参数相同）
    async call(action: string, params: Record<string, any> = {}) {
      return await go(action, params);
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

function formatStep(j: any): { text: string; image?: { data: string; mimeType: string } } {
  const parts: string[] = [];
  if (j.result !== undefined) parts.push(String(j.result));
  const hints = hostHints(j);
  if (hints.length) parts.push(hints.join("\n"));
  if (j.snapshot !== undefined) parts.push(String(j.snapshot));
  else if (j.url) parts.push(`网址: ${j.url}`);
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
      "用法细节、常见坑和出错时怎么读轨迹见 pimet-browser 技能，第一次用前先读它。\n" +
      "【多步优先 action=run】code 是 async 函数体，用 b 操作：b.open b.snapshot b.click b.fill b.select b.press b.scroll b.back b.wait b.waitFor(选择器或函数) " +
      "b.js(fn, ...args)（提取数据首选，fn 在页面里运行，参数经 JSON 传入）b.screenshot b.log b.tool('站点/工具名',args) b.tab('user') b.newTab(url)；return 结果。出错会给出错位置、当前网址和最后几步轨迹。\n" +
      "【网站任务先 action=tools】看 sites/ 下的现成工具和 notes.md；成功后沉淀工具和经验。\n" +
      "【单步探索】open | snapshot(mode=interactive 省 token) | click(ref/text) | type(ref,text) | select | press | scroll | back | forward | reload | wait | eval | screenshot | status | tabs | new_tab | switch_tab | close_tab | downloads | show | hide | home | close | reload_ui。ref 如 e3，来自快照。\n" +
      "默认在 Pi 专属标签操作；用户明确要你操作他当前页面才传 tab=\"user\"。不要擅自 show 打断用户。" +
      "返回「用户已叫停」或「用户正在操作标签」（user_takeover）时立即停止所有浏览器操作、告诉用户进展，等用户允许后再继续，不要重试。验证码、支付、登录确认等交给用户本人完成。",
    parameters: Type.Object({
      action: Type.String({
        description:
          "run | tools | open | snapshot | click | type | select | press | scroll | back | forward | reload | home | wait | eval | screenshot | show | hide | close | reload_ui | status | tabs | new_tab | switch_tab | close_tab | downloads",
      }),
      code: Type.Optional(Type.String({ description: "run 的代码：async 函数体，可用 b、ctx 和 await，用 return 返回结果" })),
      tab: Type.Optional(Type.String({ description: "操作哪个标签：默认 Pi 专属标签；\"user\" = 用户当前的标签；或 tabs 列出的 id（如 t3）" })),
      site: Type.Optional(Type.String({ description: "tools 时只看某个站点，并附上它的 notes.md" })),
      url: Type.Optional(Type.String({ description: "open / new_tab 的网址或搜索词" })),
      ref: Type.Optional(Type.String({ description: "元素编号，如 e12（来自快照）" })),
      text: Type.Optional(Type.String({ description: "type 要输入的文字；click 时按包含的文字查找元素；wait 时等待出现的文字" })),
      clear: Type.Optional(Type.Boolean({ description: "type 前清空原内容，默认 true" })),
      submit: Type.Optional(Type.Boolean({ description: "type 后提交表单 / 回车" })),
      value: Type.Optional(Type.String({ description: "select 的选项值或文字" })),
      key: Type.Optional(Type.String({ description: "press 的按键" })),
      direction: Type.Optional(Type.String({ description: "scroll 方向 up/down/left/right，默认 down" })),
      amount: Type.Optional(Type.Number({ description: "scroll 滚动的屏数，默认 0.8" })),
      script: Type.Optional(Type.String({ description: "eval 执行的 JS：表达式或带 return 的语句体，可用 await" })),
      ms: Type.Optional(Type.Number({ description: "wait 的毫秒数" })),
      js: Type.Optional(Type.Boolean({ description: "click 时用 JS 点击而不是模拟真实触摸（被遮挡或真实点击无效时用）" })),
      mode: Type.Optional(Type.String({ description: "快照模式：full（含页面文字）| interactive（只要可交互元素）。open/snapshot 默认 full，点击输入等变更操作默认 interactive" })),
      snapshot: Type.Optional(Type.Boolean({ description: "单步操作后是否返回快照，默认 true" })),
      max_chars: Type.Optional(Type.Number({ description: "快照最大字符数，open/snapshot 默认 6000，变更操作默认 2500" })),
      timeout: Type.Optional(Type.Number({ description: "单步：等待加载 / wait / eval 的超时毫秒，默认 20000，最大 120000；run：整段代码的超时秒数，默认 120，最大 600" })),
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

      const req: Record<string, any> = { ...rest };
      delete req.code;
      delete req.site;
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
      const t = Math.min(Number(req.timeout) || 20000, 120000);
      const extra = action === "wait" ? Math.min(Number(req.ms) || 0, 60000) : 0;
      const r = await postBridge({ ...req, action: "browser", browser_action: action }, t + extra + 30000, signal);
      if (!r.ok) {
        if (r.aborted || signal?.aborted) return fail("已中止（已通知宿主取消该操作）");
        if (r.timedOut) return fail(`等待宿主响应超时（${action}），已发送取消`);
        return fail(`无法连接 PiMet 宿主（127.0.0.1:30143）：${r.body}。请确认 PiMet 应用在运行。`);
      }
      let j: any;
      try {
        j = JSON.parse(r.body);
      } catch (e) {
        return fail(`宿主返回无法解析：${r.body.slice(0, 300)}`);
      }
      if (j.success === false) return fail(`失败: ${j.error ?? r.body}${j.url ? `\n网址: ${j.url}` : ""}`);
      if (isEval) {
        const v = j.result;
        if (typeof v === "string" && (v.startsWith("脚本出错: ") || /^脚本超时（\d+ms）未返回/.test(v))) return fail(`失败: ${v}`);
        const d = decodeEnvelope(v);
        if (d.enveloped) j.result = show(d.value);
      }
      const f = formatStep(j);
      const content: any[] = [{ type: "text", text: truncate(f.text) }];
      if (f.image) content.push({ type: "image", data: f.image.data, mimeType: f.image.mimeType });
      return { content, details: { action, tab: j.tab ?? tab, ok: true } };
    },
  });
}
