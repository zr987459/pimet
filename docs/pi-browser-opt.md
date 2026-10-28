# Pi 浏览器优化（分支 feature/pi-browser-opt，测试版 1.9.0-beta.18）

基线：feat/browser-window @90dd8cf（= beta.17）。每人只改自己负责的文件，其他文件只读。

## 分工（文件所有权）
| 成员 | 只改这些文件 |
|---|---|
| A | app/src/main/assets/extensions/pimet-browser.ts、app/src/main/assets/pimet-browser/SKILL.md |
| C | app/src/main/assets/pimet-browser/agent.js |
| D | app/src/main/java/com/xm486/pimet/ui/BrowserAgent.java |
| F | app/src/main/java/com/xm486/pimet/bridge/AppBridgeManager.java |
| 主控 | FloatingApps.java、build.gradle、workflow（已提交 navSeq 等字段） |

## 协议约定（大家按这个对齐，不要自创字段）

### 请求（TS → 宿主，POST /api/app-control）
- 新增 `req_id`: string，TS 每次请求生成（如 `Date.now().toString(36)+随机4位`）。
- 新增动作 `browser_action:"cancel"`，参数 `{req_id}`（取消某个请求）或 `{tab}`（取消该标签上所有在途请求）。宿主立即返回 `{success:true,result:"已取消"}`，**不拿标签锁**。旧宿主会回「未知操作」，TS 忽略即可。
- `eval/js` 新增可选 `fn:true`：表示 `script` 是完整函数体（TS 已包装好、自带 return），宿主**不再**用 `contains("return")` 猜测，直接 `(async function(){ script })()`。不带 fn 时宿主改为：先在页面里用 `new Function("return ("+s+")")` 试解析为表达式，成功则按表达式返回，失败再按语句体执行。
- 变更类动作（click/type/select/press/scroll/back/forward/reload）的默认快照：TS 侧默认传 `mode:"interactive", max_chars:2500`；open/navigate/snapshot 保持 full。

### 宿主行为（BrowserAgent，D）
- 每个请求 `deadline = now + timeout`；`checkStop()` 同时检查：用户叫停、该请求/标签已被 cancel、超过 deadline → 抛异常、立即放锁。
- 标签锁改 `ReentrantLock.tryLock(min(剩余时间, 15s))`，拿不到返回错误 `"标签 tX 正忙（上一个操作还没结束），稍后重试或先 cancel"`。
- `onMain` 提交的 Runnable 开头检查取消标志，已取消就不执行（杜绝超时后的「幽灵点击」）。
- 导航等待：动作前记 `int seq = tab.navSeq`；等待条件 = `tab.navSeq != seq && tab.finishedSeq == tab.navSeq && !tab.loading`，或 URL 已变化且不在加载；超时不报成功。
- agent.js 注入：先发探针 `window.__pimetAgent && window.__pimetAgent.v === <V>`，为 false 才发整份源码。V 由宿主按源码内容哈希算出，替换 agent.js 里的占位 `__PIMET_AGENT_V__`（见下）。
- 结果新增字段：`loaded`(bool，导航类)、`http_status`(int，>=400 时)、`load_error`(string，主框架错误)、`elapsed_ms`(int)。失败时 `err()` 也带上 `url`、`title`。错误文案不带 Java 异常类名。
- back/forward 不能后退/前进时直接报错。
- settle：用 agent.js 的 `A.waitStable(quietMs, maxMs)`（见下）轮询，上限 3000ms，替换固定 sleep。
- 截图：窗口可见时优先 PixelCopy（API 26+），失败回退 draw；ARGB_8888 → JPEG 70。
- 读取 Tab 新字段：`navSeq / finishedSeq / errorCode / errorDesc / httpStatus / userInputAt`（主控已加到 FloatingApps.Tab）。
- 用户接手检测：请求开始时记 `t0 = SystemClock.uptimeMillis()`；每个原子步骤（locate、tap、type、key、select）前若 `tab.userInputAt > t0`，返回错误 `"用户正在操作标签 tX，Pi 已暂停本次操作。请等用户操作完或询问用户后再继续"`（带 url），错误对象加 `user_takeover:true`。TS/SKILL 遇到该错误要停下问用户，不要重试。

### 页面脚本（agent.js，C）
- 版本号写成 `var V = '__PIMET_AGENT_V__';`，宿主注入前替换为源码哈希。
- 新增/改动的函数签名（宿主会调用，名字别改）：
  - `A.snapshot(maxChars, mode)` 保持；输出末尾加「另有 N 个元素未列出」；ref **跨快照稳定**（WeakMap 元素→号，号不重用）；`A.get(ref)` 发现元素已移除/不匹配时返回 `{ok:false,error:"页面已变化，ref eN 已失效，请重新 snapshot"}`。
  - `A.waitStable(quietMs, maxMs)` → 同步返回 `{stable:bool, pending:int, idleMs:int}`（宿主轮询调用；首次调用安装 MutationObserver + fetch/XHR 计数钩子）。
  - `A.locate(ref, text)` 保持返回 `{ok,x,y,vw,label,covered,coveredBy}`；中心被盖时多点采样；`coveredBy` 为遮挡物描述。
  - `A.relocate()` → 触摸前重新量上一次 locate 的元素，返回同结构。
  - `A.type(ref, text, clear)` 补 keydown/beforeinput/keyup，返回 `{ok, form, value}`（密码框 value 只回长度 `len`）。
  - `A.scroll(...)` 返回 `{ok, result, atEnd, moved}`。
  - `A.evalExpr(src)` → 供宿主判断表达式/语句（可选，D 也可以自己内联）。
- 兼容：不用 NodeList.forEach / isConnected / Array.find 等，加小 shim。

### 工具层（pimet-browser.ts + SKILL.md，A）
- 每请求带 `req_id`；AbortSignal 触发或客户端超时时补发 `cancel`（2s 超时、失败忽略）。
- 致命错误（用户叫停、令牌无效、连不上宿主）设 `st.fatal` 并 abort，之后所有 `b.*` 立即抛同一错误；run 结果首行写「⛔ 已叫停/已终止，做到第 N 步」。
- run 输出分预算：错误 + 当前网址 + 最后 5 步轨迹必保留；日志保留尾部 8k；返回值超长用紧凑 JSON 并注明已截断。
- run 轨迹 `{i, action, 参数摘要(fill 只记长度), ms, ok}`；从 e.stack 解析用户代码行号。
- `b.js(fn, ...args)`：传函数时序列化并带 `fn:true`；字符串形式保留兼容。返回值统一用信封 `{u:isUndefined, v}` 解析，不再猜类型。
- 新增 `b.waitFor(selectorOrFn, timeout)`。
- 变更类单步动作默认 interactive 快照；`details` 只留 action/tab/ok，不回写 script/text；失败返回 `isError:true`。
- 工具 description 精简，细节移进 SKILL.md；SKILL.md 新增「常见坑」「出错时怎么读轨迹」，示例改用 `b.js(fn,args)`。

### 桥接（AppBridgeManager，F）
- 浏览器请求处理期间检测客户端断开（socket 关闭 / 读到 EOF），断开即对该 `req_id` 调用 `BrowserAgent.cancel(reqId)`（D 提供 `public static void cancel(String reqId, String tab)`）。
- 浏览器请求的 socket 读超时与处理超时分离（读请求头 5s；处理期间不因 soTimeout 中断）。
- 响应体 >2MB 时截断并标记；写回失败只记日志。
- `cancel` 动作走快速路径，不进任何锁。

## 提交规范
- 直接提交到 `feature/pi-browser-opt`，提交信息前缀 `[A]` / `[C]` / `[D]` / `[F]`。
- 提交前先拉最新的该文件，避免覆盖别人的提交。
- 不动 main，不改别人的文件；发现需要别人配合，在回复【需要谁】里写。
