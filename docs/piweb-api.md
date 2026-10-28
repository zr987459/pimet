# Pi-Web HTTP API 接口说明

本文档基于 Pi-Web (`@agegr/pi-web`) Next.js 服务端路由实现整理，供 PiMet 本地 HTTP 客户端直接调用。

## 鉴权与安全校验机制
- **受信任请求检查 (`isTrustedApiRequest`)**：所有 API 均校验请求的 `Host` 头是否为 `127.0.0.1` / `localhost`，并且请求头中若不含 `Origin` / `Sec-Fetch-Site`（或与 Host 相同）则视为可信来源。因此客户端直接发送 HTTP 请求到 `127.0.0.1:<port>` 即可满足安全校验。
- **密码鉴权 (`PI_WEB_PASSWORD`)**：若未设置环境变量 `PI_WEB_PASSWORD`，所有 API 免密直接访问；若设置了密码，请求支持通过 `Cookie: pi_web_session=<token>` 或 `Authorization: Basic <base64(pi:password)>` 鉴权。PiMet 的 Java 客户端会自动从系统的 `CookieManager` 读取 Pi-Web 端口下的 Cookie 发送。

---

## 路由详情

### 1. `/api/web-auth`
- **方法**：`GET`、`POST`、`DELETE`
- **GET**：查询密码认证状态，返回 `{ "enabled": boolean, "authenticated": boolean }`。
- **POST**：提交密码登录，请求体 `{ "password": string }`，成功返回 `{ "ok": true }` 并设置 `pi_web_session` Cookie；失败返回 401。
- **DELETE**：登出并清除 `pi_web_session` Cookie。

### 2. `/api/models`
- **方法**：`GET`（可选参数 `?cwd=<path>`）
- **说明**：获取当前工作目录下可用的模型汇总，包括可见模型、默认模型与思考级别等。
- **返回**：`{ "models": { "provider:modelId": "modelName" }, "modelList": [ { "id": string, "name": string, "provider": string, "input": string[] } ], "defaultModel": { "provider": string, "modelId": string }, "defaultThinkingLevel": string | null, ... }`。

### 3. `/api/models/enabled`
- **方法**：`GET`（可选 `?cwd=<path>`）、`PUT`
- **GET**：列出所有 Provider 及其下的所有模型与启用状态。返回 `{ "allEnabled": boolean, "enabledTotal": number, "availableTotal": number, "providers": [ { "id": string, "name": string, "kind": "builtin"|"custom", "enabledCount": number, "models": [ { "id": string, "name": string, "ref": string, "enabled": boolean } ] } ] }`。
- **PUT**：配置模型启停或全部启用。
  - 启用全部模型：请求体 `{ "op": "clear" }`（清空过滤规则即可全部启用）。
  - 启停单个或多个模型：请求体 `{ "op": "models", "refs": [ "provider/modelId" ], "enabled": boolean }`。
  - 启停单个 Provider：请求体 `{ "op": "provider", "provider": string, "enabled": boolean }`。
  - 返回与 GET 相同的最新模型启用树结构。

### 4. `/api/models/default`
- **方法**：`PUT`
- **说明**：设置全局或当前项目的默认模型及思考级别。
- **请求体**：`{ "provider": string, "modelId": string, "thinkingLevel"?: string, "cwd"?: string }`。
- **返回**：`{ "ok": true, "defaultModel": { "provider": string, "modelId": string } }`。

### 5. `/api/skills`
- **方法**：`GET`（需 `?cwd=<path>`）、`PATCH`
- **GET**：列出当前项目与全局安装的所有技能，返回 `{ "skills": [ { "name": string, "description": string, "filePath": string, "disableModelInvocation": boolean, ... } ], "diagnostics": [] }`。
- **PATCH**：切换技能启停状态（更新技能文件的 frontmatter `disableModelInvocation` 属性）。请求体 `{ "filePath": string, "disableModelInvocation": boolean }` 或 `{ "filePaths": string[], "disableModelInvocation": boolean }`，返回 `{ "success": true }`。

### 6. `/api/skills/install`
- **方法**：`POST`
- **说明**：从 npm / GitHub 安装技能，内部调用 `npx skills add <package> -y --agent pi [-g]`。
- **请求体**：`{ "package": string, "scope"?: "global" | "project", "cwd"?: string }`（默认 global）。
- **返回**：`{ "success": true, "output": string }` 或 500 附带 `{ "error": string }`。

### 7. `/api/skills/content`
- **方法**：`GET`（参数 `?cwd=<path>&filePath=<filePath>`）
- **说明**：读取技能 Markdown 文件全文。
- **返回**：`{ "content": string }`。

### 8. `/api/subagents/profiles`
- **方法**：`GET`（参数 `?cwd=<path>`）、`PUT`、`PATCH`、`DELETE`
- **GET**：列出所有子代理（内置与自定义配置），返回 `{ "profiles": [ { "name": string, "displayName"?: string, "description"?: string, "scope": "builtin"|"global"|"project", "enabled": boolean, ... } ] }`。
- **PUT**：创建或更新自定义子代理 profile。请求体 `{ "cwd": string, "scope": "global"|"project", "profile": { "name": string, ... } }`。
- **PATCH**：开关子代理 profile。请求体 `{ "cwd": string, "scope": "builtin"|"global"|"project", "name": string, "enabled": boolean }`，返回 `{ "profile": ... }`。
- **DELETE**：删除指定自定义子代理 profile（内置不可删）。请求体 `{ "cwd": string, "scope": "global"|"project", "name": string }`，返回 `{ "ok": true }`。

### 9. `/api/subagents/settings`
- **方法**：`GET`、`PUT`
- **GET**：查询子代理全局总开关及最大并发数，返回 `{ "enabled": boolean, "maxConcurrent": number }`。
- **PUT**：修改子代理全局总开关或最大并发数，请求体 `{ "enabled"?: boolean, "maxConcurrent"?: number }`，返回最新配置。

### 10. `/api/subagents/[id]`
- **方法**：`GET`、`POST`
- **GET**：获取特定运行中子代理实例的执行状态和历史，返回 `{ "run": ... }`。
- **POST**：控制或引导子代理实例，请求体 `{ "action": "steer" | "abort", "message"?: string }`。

### 11. `/api/plugins`
- **方法**：`GET`（参数 `?cwd=<path>`）、`POST`
- **GET**：获取已配置安装的插件包列表及独立扩展。返回 `{ "packages": [ { "source": string, "scope": "global"|"project", "disabled": boolean, "packageName"?: string, "version"?: string, "description"?: string, "status": "disabled"|"loaded"|"installed"|"missing" } ], "standaloneExtensions": [ { "name": string, "enabled": boolean, ... } ] }`。
- **POST**：管理插件，根据 `action` 字段执行操作：
  - 安装：`{ "cwd": string, "action": "install", "source": string, "scope": "global"|"project" }`
  - 卸载：`{ "cwd": string, "action": "remove", "source": string, "scope": "global"|"project" }`
  - 启停单个：`{ "cwd": string, "action": "enable" | "disable", "source": string, "scope": "global"|"project" }`
  - 批量启停：`{ "cwd": string, "action": "enable" | "disable", "packages": [ { "source": string, "scope": "global"|"project" } ] }`
  - 更新：`{ "cwd": string, "action": "update", "source"?: string }`
  - 成功时均返回最新插件包列表结构。

### 12. `/api/mcp`
- **方法**：`GET`（参数 `?cwd=<path>`）、`POST`
- **GET**：获取已配置的 MCP 服务器列表，返回 `{ "servers": [ { "name": string, "scope": "global"|"project", "enabled": boolean, "transport": object, ... } ], ... }`。
- **POST**：管理 MCP 服务器：
  - 开启/关闭：`{ "cwd"?: string, "action": "enable" | "disable", "scope": "global"|"project", "name": string }`
  - 删除：`{ "cwd"?: string, "action": "remove", "scope": "global"|"project", "name": string }`
  - 新增：`{ "cwd"?: string, "action": "add", "scope": "global"|"project", "text": string, "name"?: string }`
  - 成功返回更新后的 MCP 服务器集合。

### 13. `/api/tools/settings`
- **方法**：`GET`、`PUT`
- **GET**：获取基础工具设置（Windows PowerShell 切换、代码模式 codemode 设置等）。返回 `{ "isWindows": boolean, "powerShellEnabled": boolean, "codemode": string, ... }`。
- **PUT**：修改指定工具配置，请求体可包含 `enabled`、`codemode`、`codemodeMode` 或 `codemodeInlineBudget` 之一。
