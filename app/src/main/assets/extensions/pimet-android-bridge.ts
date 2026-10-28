/**
 * PiMet Host Android & Desktop Pet Bridge Extension
 * 允许 AI 控制 Android 宿主的桌宠、界面，并通过无障碍服务操作手机（phone_control）
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

function postBridge(actionData: Record<string, any>, timeoutMs: number): Promise<{ ok: boolean; body: string }> {
  return new Promise((resolve) => {
    const postData = JSON.stringify(actionData);
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
    req.write(postData);
    req.end();
  });
}

/** 桌宠/界面控制：HTTP 不通时写入 IPC 文件兜底（宿主轮询执行）。 */
async function sendBridgeAction(actionData: Record<string, any>): Promise<string> {
  const r = await postBridge(actionData, 4000);
  if (r.ok) return `Android 宿主执行成功: ${r.body}`;
  try {
    if (!fs.existsSync(BRIDGE_DIR)) fs.mkdirSync(BRIDGE_DIR, { recursive: true });
    fs.writeFileSync(path.join(BRIDGE_DIR, "app_control.json"), JSON.stringify(actionData), "utf-8");
  } catch (e) {}
  return `指令已写入 IPC 文件缓存 (HTTP: ${r.body})`;
}

export default function (pi: ExtensionAPI) {
  pi.registerTool({
    name: "control_desktop_pet",
    label: "Control Desktop Pet",
    description: "控制 Android 宿主界面的动态桌宠：修改角色/皮肤、尺寸大小(dp)、动作(跳跃/跳舞/挥手)、对话气泡、反弹摩擦力或开关全局桌宠",
    parameters: Type.Object({
      character: Type.Optional(Type.String({ description: "角色目录名，如 cat_maid(猫娘), eva(小初), gup(少女), paimon(派蒙), custom" })),
      size: Type.Optional(Type.Number({ description: "桌宠大小 (dp，范围 32~160)" })),
      bubble_text: Type.Optional(Type.String({ description: "让桌宠说出的气泡文字" })),
      action: Type.Optional(Type.String({ description: "桌宠动作: jumping (跳跃/跟斗), dancing (跳舞), waving (招手), idle (静止)" })),
      bounce: Type.Optional(Type.Number({ description: "物理反弹系数 0~100" })),
      friction: Type.Optional(Type.Number({ description: "空气摩擦力 0~100" })),
      global_pet_enabled: Type.Optional(Type.Boolean({ description: "是否开启全局系统悬浮桌宠" })),
    }),
    async execute(_toolCallId, params) {
      const result = await sendBridgeAction({ action: "control_desktop_pet", ...params });
      return {
        content: [{ type: "text", text: `桌宠控制指令已下发：${result}` }],
        details: params,
      };
    },
  });

  pi.registerTool({
    name: "control_app_ui",
    label: "Control App UI",
    description: "控制 Android 宿主应用的界面与端口：切换选项卡(工作台/终端/设置/操控台)、配置各服务端口",
    parameters: Type.Object({
      switch_tab: Type.Optional(Type.String({ description: "切换界面: launch(主页), web(Pi-Web工作台), terminal(终端), settings(设置)" })),
      ports: Type.Optional(
        Type.Object({
          piweb: Type.Optional(Type.Number()),
          operit: Type.Optional(Type.Number()),
          clawbench: Type.Optional(Type.Number()),
          rikka: Type.Optional(Type.Number()),
        })
      ),
    }),
    async execute(_toolCallId, params) {
      const result = await sendBridgeAction({ action: "control_app_ui", ...params });
      return {
        content: [{ type: "text", text: `Android 宿主界面指令已下发：${result}` }],
        details: params,
      };
    },
  });

  pi.registerTool({
    name: "phone_control",
    label: "Phone Control",
    description:
      "通过 PiMet 无障碍服务操作这台 Android 手机。坐标均为屏幕像素（与 inspect 返回的 bounds/centerX/centerY 相同）。" +
      "action 可选：status(服务状态) | inspect(读屏，返回可见节点 text/desc/id/bounds/clickable) | find(按文字/ID 查找) | " +
      "tap / long_press(按 target 文字、id、desc 或 x,y) | swipe(direction=up/down/left/right 或 from_x,from_y,to_x,to_y) | " +
      "scroll(direction=down 看下方内容/up，可用 target 指定列表) | input(text，可选 target 输入框、append、submit) | " +
      "key(key=back/home/recents/notifications/quick_settings/lock/enter) | launch(package 或 app 应用名) | " +
      "screenshot(Android 11+，返回容器内图片路径，可用 read 查看) | wait_for(target/id 出现，gone=true 等消失，timeout 毫秒) | wait_idle。" +
      "close_ad(关闭广告/跳过开屏：自动识别 ×、关闭、跳过、Skip 倒计时等按钮并点击；timeout 毫秒内轮询，strict=true 只点有明确文字/ID 的按钮)。" +
      "tap/long_press/swipe/scroll/input/key/launch/close_ad 成功后会自动等待界面稳定，并在 screen_after 里返回操作后的读屏结果，无需再单独 inspect（传 observe=false 关闭）。" +
      "读屏节点带 ad_close=true、或结果里出现 ad_close_candidates 时说明有广告弹窗，先 close_ad 再继续。" +
      "建议流程：inspect → tap/input（看 screen_after 判断结果）→ 下一步；需要等加载时用 wait_for。",
    parameters: Type.Object({
      action: Type.String({ description: "操作：status | inspect | find | close_ad | tap | long_press | swipe | scroll | input | key | launch | screenshot | wait_for | wait_idle" }),
      target: Type.Optional(Type.String({ description: "目标文字（匹配 text/desc/hint，先精确后包含）；input 时为目标输入框；launch 时可为应用名" })),
      id: Type.Optional(Type.String({ description: "目标 View ID，如 com.tencent.mm:id/xxx 或仅 xxx" })),
      desc: Type.Optional(Type.String({ description: "按 content-description 匹配" })),
      text: Type.Optional(Type.String({ description: "input 要输入的文字（tap/find 时也可作为目标文字）" })),
      x: Type.Optional(Type.Number({ description: "屏幕像素 X" })),
      y: Type.Optional(Type.Number({ description: "屏幕像素 Y" })),
      from_x: Type.Optional(Type.Number()),
      from_y: Type.Optional(Type.Number()),
      to_x: Type.Optional(Type.Number()),
      to_y: Type.Optional(Type.Number()),
      direction: Type.Optional(Type.String({ description: "swipe/scroll 方向：up | down | left | right" })),
      duration: Type.Optional(Type.Number({ description: "手势时长毫秒（swipe 默认 300，long_press 默认 800）" })),
      key: Type.Optional(Type.String({ description: "按键：back | home | recents | notifications | quick_settings | lock | power | screenshot | enter" })),
      package: Type.Optional(Type.String({ description: "launch 的包名，如 com.android.settings" })),
      app: Type.Optional(Type.String({ description: "launch 的应用名，如 设置、微信" })),
      index: Type.Optional(Type.Number({ description: "多个匹配时选第几个（0 起）" })),
      exact: Type.Optional(Type.Boolean({ description: "只接受精确文字匹配" })),
      append: Type.Optional(Type.Boolean({ description: "input 时追加而不是替换" })),
      submit: Type.Optional(Type.Boolean({ description: "input 后执行回车/搜索（Android 11+）" })),
      gone: Type.Optional(Type.Boolean({ description: "wait_for 等待目标消失" })),
      timeout: Type.Optional(Type.Number({ description: "wait_for / wait_idle 超时毫秒，默认 5000，最大 30000" })),
      max_nodes: Type.Optional(Type.Number({ description: "inspect 返回节点上限，默认 300" })),
      observe: Type.Optional(Type.Boolean({ description: "操作后是否自动附带 screen_after 读屏，默认 true" })),
      strict: Type.Optional(Type.Boolean({ description: "close_ad 只点击有明确文字/ID 的关闭按钮" })),
    }),
    async execute(_toolCallId, params) {
      const { action, ...rest } = params as Record<string, any>;
      const waitMs = Math.min(Number(rest.timeout) || 5000, 30000);
      const r = await postBridge({ ...rest, action: "phone_control", phone_action: action }, waitMs + 20000);
      let text: string;
      if (!r.ok) {
        text = `无法连接 PiMet 宿主（127.0.0.1:30143）：${r.body}。请确认 PiMet 应用在运行。`;
      } else {
        text = r.body;
      }
      return { content: [{ type: "text", text }], details: { action, ...rest } };
    },
  });
}
