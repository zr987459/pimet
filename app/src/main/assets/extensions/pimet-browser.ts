/**
 * PiMet Browser Extension
 * 让 Pi 操控 PiMet 的「Pi 浏览器」（独立全屏窗口，Android WebView，用户能实时看到并接手）。
 * 用法借鉴 Playwright MCP / browser-use / ego-lite：
 *   - 单步：先拿页面快照，可交互元素带编号 [e1] [e2]…，再按编号点击 / 输入；
 *   - 代码模式 run：一段 async JS 一次跑完多步（b.open / b.fill / b.js …），省去来回；
 *   - 站点工具：/root/pimet-browser/sites/<站点>/tools/<名>.mjs 沉淀可复用流程，b.tool("站点/名", args) 调用。
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
const MAX_SHOTS = 3;
const NAME_RE = /^[A-Za-z0-9._-]+$/;

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
    if (j.success === false) return { text: `失败: ${j.error ?? body}${j.url ? `\n网址: ${j.url}` : ""}` };
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

function truncate(s: string, n = MAX_OUT): string {
  return s.length > n ? s.slice(0, n) + `\n…（已截断，共 ${s.length} 字符）` : s;
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

// ------------------------------------------------------------------ 单次桥调用

class BrowserError extends Error {}

async function call(action: string, params: Record<string, any>, signal?: AbortSignal): Promise<any> {
  if (signal?.aborted) throw new BrowserError("已中止");
  const t = Math.min(Number(params.timeout) || 20000, 120000);
  const extra = action === "wait" ? Math.min(Number(params.ms) || 0, 60000) : 0;
  const r = await postBridge({ ...params, action: "browser", browser_action: action }, t + extra + 30000, signal);
  if (!r.ok) {
    if (signal?.aborted) throw new BrowserError("已中止");
    throw new BrowserError(`无法连接 PiMet 宿主（127.0.0.1:30143）：${r.body}。请确认 PiMet 应用在运行。`);
  }
  let j: any;
  try {
    j = JSON.parse(r.body);
  } catch (e) {
    throw new BrowserError(`宿主返回无法解析：${r.body.slice(0, 300)}`);
  }
  if (j.success === false) throw new BrowserError(String(j.error ?? "操作失败"));
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

// ------------------------------------------------------------------ 代码模式 run

type Shot = { data: string; mimeType: string };

interface RunState {
  logs: string[];
  shots: Shot[];
  signal: AbortSignal;
  lastUrl: string;
}

function makeB(st: RunState, tab?: string): any {
  const sig = st.signal;
  const base = (extra: Record<string, any> = {}) => ({ snapshot: false, ...(tab ? { tab } : {}), ...extra });
  const go = async (action: string, extra: Record<string, any> = {}) => {
    const j = await call(action, base(extra), sig);
    if (j.url) st.lastUrl = j.url;
    return j;
  };
  const isRef = (s: any) => typeof s === "string" && /^e\d+$/.test(s.trim());
  const sleep = (ms: number) =>
    new Promise<void>((resolve, reject) => {
      if (sig.aborted) return reject(new BrowserError("已中止"));
      const t = setTimeout(() => {
        sig.removeEventListener("abort", onAbort);
        resolve();
      }, Math.max(0, Number(ms) || 0));
      const onAbort = () => {
        clearTimeout(t);
        reject(new BrowserError("已中止"));
      };
      sig.addEventListener("abort", onAbort, { once: true });
    });

  const b: any = {
    async open(url: string, opts: { timeout?: number } = {}) {
      const j = await go("open", { url, ...opts });
      return { url: j.url, title: j.title ?? "" };
    },
    async snapshot(opts: { mode?: string; max_chars?: number } = {}) {
      const j = await go("snapshot", { snapshot: true, ...opts });
      return String(j.snapshot ?? "");
    },
    async click(target: string, opts: { js?: boolean } = {}) {
      const j = await go("click", isRef(target) ? { ref: target.trim(), ...opts } : { text: String(target), ...opts });
      return String(j.result ?? "");
    },
    async fill(ref: string, text: string, opts: { submit?: boolean; clear?: boolean } = {}) {
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
    async js(script: string, opts: { timeout?: number } = {}) {
      const j = await go("eval", { script, ...opts });
      const v = j.result;
      if (typeof v !== "string") return v;
      if (v.startsWith("脚本出错: ") || /^脚本超时（\d+ms）未返回/.test(v)) throw new BrowserError(v); // 兼容旧宿主
      if (v === "undefined") return undefined;
      try {
        return JSON.parse(v);
      } catch (e) {
        return v;
      }
    },
    async url() {
      return String((await go("status")).url ?? "");
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
      const fn = await loadTool(spec);
      return await fn(b, args ?? {});
    },
    // 标签页：默认操作 Pi 专属标签；b.tab("user") 操作用户正在看的标签，b.tab(id) 操作指定标签
    tab(id: string) {
      return makeB(st, id);
    },
    async tabs() {
      return (await call("tabs", {}, sig)).tabs ?? [];
    },
    async newTab(url?: string) {
      const j = await call("new_tab", url ? { url } : {}, sig);
      return makeB(st, j.tab);
    },
    async closeTab() {
      await call("close_tab", tab ? { tab } : {}, sig);
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

const AsyncFunction: any = Object.getPrototypeOf(async function () {}).constructor;

async function runCode(code: string, timeoutSec: number, tab: string | undefined, outer?: AbortSignal) {
  const ctrl = new AbortController();
  const onOuter = () => ctrl.abort();
  if (outer) {
    if (outer.aborted) ctrl.abort();
    else outer.addEventListener("abort", onOuter, { once: true });
  }
  const ms = Math.round(Math.max(1, Math.min(timeoutSec || 120, 600)) * 1000);
  const timer = setTimeout(() => ctrl.abort(), ms);
  const st: RunState = { logs: [], shots: [], signal: ctrl.signal, lastUrl: "" };
  const b = makeB(st, tab);
  const ctx = { signal: ctrl.signal, tab: tab ?? "pi", timeoutMs: ms };
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
      const fire = () => reject(new BrowserError(outer?.aborted ? "已中止" : `run 超时（${ms / 1000} 秒）`));
      if (ctrl.signal.aborted) fire();
      else ctrl.signal.addEventListener("abort", fire, { once: true });
    });
    value = await Promise.race([fn(b, ctx), aborted]);
  } catch (e: any) {
    error = e;
  } finally {
    clearTimeout(timer);
    outer?.removeEventListener("abort", onOuter);
    if (!ctrl.signal.aborted) ctrl.abort(); // 让仍在等待的请求 / sleep 退出
  }

  const parts: string[] = [];
  if (st.logs.length) parts.push("日志:\n" + st.logs.join("\n"));
  if (error) {
    let url = st.lastUrl;
    try {
      const j = await call("status", tab ? { tab, timeout: 3000 } : { timeout: 3000 });
      url = j.url ?? url;
    } catch (e: any) {
      if (String(e?.message ?? "").includes("叫停")) url = url || "(用户已叫停)";
    }
    const msg = error instanceof BrowserError ? error.message : error?.stack ?? String(error);
    parts.push(`出错: ${msg}`);
    parts.push(`当前网址: ${url || "(未知)"}`);
  } else {
    parts.push("返回值:\n" + show(value));
  }
  return { text: truncate(parts.join("\n\n")), shots: st.shots, ok: !error };
}

// ------------------------------------------------------------------ 注册工具

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "browser",
    label: "Browser",
    description:
      "操控 PiMet 手机上的「Pi 浏览器」（独立的全屏浏览器窗口，真实的 Android WebView，用户打开它就能看到你的操作，也能随时接手）。" +
      "用于需要真正打开网页的任务：搜索/阅读动态页面、登录后的网站、填表、点按钮、查看你做的本地页面等。\n" +
      "【多步任务优先用 action=run】传 code（一段 async 函数体，可用 await），通过对象 b 一次跑完整个流程，只把 log 和 return 的结果返回给你：" +
      "b.open(url)→{url,title}；b.snapshot({mode})→快照文本；b.click(ref 或文字)；b.fill(ref,text,{submit,clear})（别名 type）；b.select(ref,value)；" +
      "b.press(key)；b.scroll('down'|ref, 屏数)；b.back() b.forward() b.reload()；b.wait(毫秒) 或 b.wait('文字',超时)→是否出现；" +
      "b.js(在页面里执行的 JS，可 await/return)→解析后的值（提取数据首选）；b.url()；b.screenshot()（附图，最多 3 张）；b.sleep(ms)；b.log(...)；" +
      "b.tool('站点/工具名',args) 调用沉淀好的站点工具；b.tab('user')/b.newTab(url) 切换操作的标签。出错时抛异常，结果里会带当前网址。默认超时 120 秒（timeout 最大 600）。\n" +
      "【做网站任务前先 action=tools】看 /root/pimet-browser/sites 下有没有现成工具和 notes.md 经验；成功后按 pimet-browser 技能把稳定步骤沉淀成工具、把坑写进 notes.md。\n" +
      "【单步探索用原来的 action】open(url 或搜索词) | snapshot(mode=interactive 只要元素列表) | click(ref 或 text) | type(ref,text,clear,submit) | select(ref,value) | press(key) | " +
      "scroll(direction/amount 或 ref) | back | forward | reload | home | wait(ms 或 text) | eval(script) | screenshot | show | hide | close | reload_ui | status | " +
      "tabs | new_tab(url) | switch_tab(tab) | close_tab(tab) | downloads(最近下载及状态，文件存到手机 下载/PiMet/)。快照里可交互元素编号为 [e3]，ref 只在最近一次快照内有效，页面变化后要重新 snapshot。\n" +
      "【标签】默认在 Pi 专属标签里操作（不打扰用户正在看的页面，共用登录 Cookie）；只有用户明确要你操作他当前页面时才传 tab=\"user\"。" +
      "浏览器窗口不在前台时不要用 show 把它切过来打断用户，照常在后台操作即可（此时只能 JS 点击、不能截图）。" +
      "如果返回「用户已叫停」，立即停止所有浏览器操作并告诉用户，等用户重新允许。\n" +
      "遇到验证码、支付、登录确认等需要用户本人操作的情况，停下来让用户在窗口里完成。" +
      "这个浏览器的按钮、配色、首页、用户脚本、自定义页面也都可以由你修改，方法见 pimet-browser 技能。",
    parameters: Type.Object({
      action: Type.String({
        description:
          "run | tools | open | snapshot | click | type | select | press | scroll | back | forward | reload | home | wait | eval | screenshot | show | hide | close | reload_ui | status | tabs | new_tab | switch_tab | close_tab | downloads",
      }),
      code: Type.Optional(Type.String({ description: "run 的代码：async 函数体，可用 b、ctx 和 await，用 return 返回结果" })),
      tab: Type.Optional(Type.String({ description: "操作哪个标签：默认 Pi 专属标签；\"user\" = 用户当前的标签；或 tabs 列出的 id（如 t3）" })),
      site: Type.Optional(Type.String({ description: "tools 时只看某个站点，并附上它的 notes.md" })),
      url: Type.Optional(Type.String({ description: "open / new_tab 的网址或搜索词" })),
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
      mode: Type.Optional(Type.String({ description: "snapshot 模式：full（默认，含页面文字）| interactive（只要可交互元素，省 token）" })),
      snapshot: Type.Optional(Type.Boolean({ description: "单步操作后是否返回快照，默认 true" })),
      max_chars: Type.Optional(Type.Number({ description: "快照最大字符数，默认 6000" })),
      timeout: Type.Optional(Type.Number({ description: "单步：等待加载 / wait / eval 的超时毫秒，默认 20000，最大 120000；run：整段代码的超时秒数，默认 120，最大 600" })),
    }),
    async execute(_toolCallId, params, signal) {
      const { action, ...rest } = params as Record<string, any>;
      if (action === "run") {
        const code = String(rest.code ?? "");
        if (!code.trim()) return { content: [{ type: "text", text: "run 需要 code（async 函数体）" }], details: { action } };
        const r = await runCode(code, Number(rest.timeout) || 120, rest.tab, signal);
        const content: any[] = [{ type: "text", text: r.text }];
        for (const s of r.shots) content.push({ type: "image", data: s.data, mimeType: s.mimeType });
        return { content, details: { action, ok: r.ok } };
      }
      if (action === "tools") {
        let text: string;
        try {
          text = listTools(rest.site);
        } catch (e: any) {
          text = `失败: ${e?.message ?? e}`;
        }
        return { content: [{ type: "text", text: truncate(text) }], details: { action } };
      }
      const t = Math.min(Number(rest.timeout) || 20000, 120000);
      const extra = action === "wait" ? Math.min(Number(rest.ms) || 0, 60000) : 0;
      const r = await postBridge({ ...rest, action: "browser", browser_action: action }, t + extra + 30000, signal);
      if (!r.ok) {
        return { content: [{ type: "text", text: `无法连接 PiMet 宿主（127.0.0.1:30143）：${r.body}。请确认 PiMet 应用在运行。` }], details: { action } };
      }
      const f = format(r.body);
      const content: any[] = [{ type: "text", text: truncate(f.text) }];
      if (f.image) content.push({ type: "image", data: f.image.data, mimeType: f.image.mimeType });
      return { content, details: { action, ...rest } };
    },
  });
}
