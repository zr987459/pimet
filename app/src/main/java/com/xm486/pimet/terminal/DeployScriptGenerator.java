package com.xm486.pimet.terminal;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * 部署脚本生成器：
 * 专注于 Pi-Web (端口 30141) 从零一键全自动部署，
 * 涵盖：端口探活 ➔ 优选国内源 ➔ Node.js (>=22.19) 自动验证与补齐 ➔ @agegr/pi-web 安装 ➔ 守护进程拉起与自愈健康检查。
 */
public class DeployScriptGenerator {

    public static File generateScript(Context context,
                                      boolean enableResumable,
                                      NetworkRouteSelector.RouteResult route) {

        File filesDir = context.getFilesDir();
        File scriptFile = new File(filesDir, "deploy_pi_web.sh");
        File logDir = new File(filesDir, "logs");
        logDir.mkdirs();

        String fastestNpm = (route != null && route.fastestNpmRegistry != null)
                ? route.fastestNpmRegistry
                : "https://registry.npmmirror.com";

        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("export HOME=\"").append(filesDir.getAbsolutePath()).append("\"\n");
        sb.append("export BIN=\"$HOME/bin\"\n");
        sb.append("export USR_BIN=\"$HOME/usr/bin\"\n");
        sb.append("export LOGDIR=\"$HOME/logs\"\n");
        sb.append("export STATE_FILE=\"$HOME/.deploy_state\"\n");
        sb.append("export NPM_CONFIG_PREFIX=\"$HOME/.npm-global\"\n\n");

        sb.append("export PATH=\"$NPM_CONFIG_PREFIX/bin:$BIN:$USR_BIN:/data/data/com.termux/files/usr/bin:/data/user/999/com.ai.assistance.operit/files/usr/bin:/system/bin:/system/xbin:$PATH\"\n");
        sb.append("if [ -d \"/data/data/com.termux/files/usr/lib\" ]; then\n");
        sb.append("  export LD_LIBRARY_PATH=\"/data/data/com.termux/files/usr/lib:$LD_LIBRARY_PATH\"\n");
        sb.append("fi\n\n");

        sb.append("mkdir -p \"$BIN\" \"$USR_BIN\" \"$LOGDIR\" \"$NPM_CONFIG_PREFIX/bin\"\n");
        sb.append("touch \"$STATE_FILE\"\n\n");

        sb.append("echo \"==================================================\"\n");
        sb.append("echo \"\\033[1;35m🚀 DevPetM Pi-Web (端口 30141) 从零一键部署器\\033[0m\"\n");
        sb.append("echo \"\\033[90m• 优选 npm 镜像源: ").append(fastestNpm).append("\\033[0m\"\n");
        sb.append("echo \"==================================================\"\n\n");

        sb.append("is_step_done() {\n");
        if (enableResumable) {
            sb.append("  grep -q \"^$1=DONE$\" \"$STATE_FILE\" 2>/dev/null\n");
        } else {
            sb.append("  return 1\n");
        }
        sb.append("}\n\n");

        sb.append("mark_step_done() {\n");
        sb.append("  echo \"$1=DONE\" >> \"$STATE_FILE\"\n");
        sb.append("}\n\n");

        // ---- 阶段 0: 端口探活 ----
        sb.append("echo \"\\033[1;34m[阶段 0/4] 探测端口 30141 服务状态...\\033[0m\"\n");
        sb.append("if pgrep -f 'pi-web' >/dev/null 2>&1; then\n");
        sb.append("  echo \"\\033[1;32m✔ Pi-Web 服务已在后台正常运行中 (端口: 30141)！\\033[0m\"\n");
        sb.append("  echo \"\\033[1;36m• 访问地址: http://127.0.0.1:30141\\033[0m\"\n");
        sb.append("  echo \"==================================================\"\n");
        sb.append("  echo \"\\033[1;32m🎉 极速就绪！可直接在顶部或桌宠打开工作台\\033[0m\"\n");
        sb.append("  echo \"==================================================\"\n");
        sb.append("  exit 0\n");
        sb.append("fi\n\n");

        // ---- 阶段 1: 验证与准备 Node.js (>= 22.19.0) ----
        sb.append("echo \"\\033[1;33m[阶段 1/4] 验证 Node.js 运行时 (需 >= 22.19.0)...\\033[0m\"\n");
        sb.append("check_node_version() {\n");
        sb.append("  if ! command -v node >/dev/null 2>&1; then\n");
        sb.append("    return 1\n");
        sb.append("  fi\n");
        sb.append("  local ver=$(node -v 2>/dev/null | tr -d 'v')\n");
        sb.append("  local major=$(echo \"$ver\" | cut -d. -f1)\n");
        sb.append("  local minor=$(echo \"$ver\" | cut -d. -f2)\n");
        sb.append("  if [ \"$major\" -gt 22 ] 2>/dev/null; then\n");
        sb.append("    return 0\n");
        sb.append("  fi\n");
        sb.append("  if [ \"$major\" -eq 22 ] 2>/dev/null && [ \"$minor\" -ge 19 ] 2>/dev/null; then\n");
        sb.append("    return 0\n");
        sb.append("  fi\n");
        sb.append("  return 2\n");
        sb.append("}\n\n");

        sb.append("check_node_version\n");
        sb.append("NODE_STATUS=$?\n");
        sb.append("if [ $NODE_STATUS -ne 0 ]; then\n");
        sb.append("  echo \"\\033[33m[环境补齐] 未检测到合格的 Node 22+，正在尝试自动安装/升级...\\033[0m\"\n");
        sb.append("  if command -v pkg >/dev/null 2>&1; then\n");
        sb.append("    echo \"\\033[36m• 正在通过 Termux pkg 安装最新版 Node.js...\\033[0m\"\n");
        sb.append("    pkg install -y nodejs 2>/dev/null\n");
        sb.append("  elif command -v apt-get >/dev/null 2>&1; then\n");
        sb.append("    apt-get update -y >/dev/null 2>&1 && apt-get install -y nodejs >/dev/null 2>&1\n");
        sb.append("  fi\n");
        sb.append("  check_node_version\n");
        sb.append("  NODE_STATUS=$?\n");
        sb.append("fi\n\n");

        sb.append("if [ $NODE_STATUS -eq 0 ]; then\n");
        sb.append("  echo \"\\033[1;32m✔ 检测到合格 Node.js: $(node -v) (满足 >= 22.19.0)\\033[0m\"\n");
        sb.append("  mark_step_done \"NODE_READY\"\n");
        sb.append("else\n");
        sb.append("  echo \"\\033[1;31m====================================================\\033[0m\"\n");
        sb.append("  echo \"\\033[1;31m✘ 错误：Pi-Web 强要求 Node.js 版本 >= 22.19.0！\\033[0m\"\n");
        sb.append("  if [ $NODE_STATUS -eq 2 ]; then\n");
        sb.append("    echo \"\\033[1;31m当前检测到版本过低: $(node -v)\\033[0m\"\n");
        sb.append("  fi\n");
        sb.append("  echo \"====================================================\\033[0m\"\n");
        sb.append("  echo \"\\033[1;33m【从零准备步骤】：\\033[0m\"\n");
        sb.append("  echo \"1. 请打开手机 Termux 应用。\"\n");
        sb.append("  echo \"2. 执行安装命令：\\033[1;36mpkg install nodejs\\033[0m (安装最新版 Node 24+/26)\"\n");
        sb.append("  echo \"3. 执行后返回本界面，再次点击【开始部署】即可全自动完成。\"\n");
        sb.append("  echo \"====================================================\\033[0m\"\n");
        sb.append("  exit 1\n");
        sb.append("fi\n\n");

        // ---- 阶段 2: 优选源并安装 @agegr/pi-web ----
        sb.append("echo \"\\033[1;33m[阶段 2/4] 配置 npm 镜像源并部署 @agegr/pi-web...\\033[0m\"\n");
        sb.append("if command -v npm >/dev/null 2>&1; then\n");
        sb.append("  npm config set registry \"").append(fastestNpm).append("\" >/dev/null 2>&1\n");
        sb.append("  npm config set prefix \"$NPM_CONFIG_PREFIX\" >/dev/null 2>&1\n");
        sb.append("fi\n\n");

        sb.append("if is_step_done \"PI_WEB_INSTALLED\" && command -v pi-web >/dev/null 2>&1 && command -v pi >/dev/null 2>&1; then\n");
        sb.append("  echo \"\\033[1;32m✔ Pi 命令行与 Pi-Web 已安装就绪\\033[0m\"\n");
        sb.append("else\n");
        sb.append("  echo \"\\033[36m• 正在从镜像源全局安装 @agegr/pi-web (请稍候)...\\033[0m\"\n");
        sb.append("  npm install -g @earendil-works/pi-coding-agent @agegr/pi-web --registry=\"").append(fastestNpm).append("\"\n");
        sb.append("  if command -v pi-web >/dev/null 2>&1; then\n");
        sb.append("    mark_step_done \"PI_WEB_INSTALLED\"\n");
        sb.append("    echo \"\\033[1;32m✔ Pi-Web 与 Pi 命令行安装成功！\\033[0m\"\n");
        sb.append("  else\n");
        sb.append("    echo \"\\033[1;31m✘ 未能在 PATH 中定位到 pi-web，尝试将本地 bin 写入软链...\\033[0m\"\n");
        sb.append("    find \"$HOME\" -name \"pi-web\" -type f -perm /111 2>/dev/null | head -n 1 | while read -r p; do\n");
        sb.append("      ln -sf \"$p\" \"$BIN/pi-web\"\n");
        sb.append("    done\n");
        sb.append("  fi\n");
        sb.append("  find \"$HOME\" -name \"pi\" -type f -perm /111 2>/dev/null | head -n 1 | while read -r p; do\n");
        sb.append("    ln -sf \"$p\" \"$BIN/pi\"\n");
        sb.append("  done\n");
        sb.append("  # 深度调优 Service Worker 超时时长，避免移动端冷启动频繁抛出 offline.html\n");
        sb.append("  find /usr/local/lib/node_modules/@agegr/pi-web /usr/lib/node_modules/@agegr/pi-web \"$HOME\" -name \"sw.js\" -exec sed -i 's/NAVIGATION_TIMEOUT_MS = 2500;/NAVIGATION_TIMEOUT_MS = 15000;/g' {} + 2>/dev/null || true\n");
        sb.append("fi\n\n");

        // ---- 阶段 3: 后台守护拉起 ----
        sb.append("echo \"\\033[1;33m[阶段 3/4] 拉起 Pi-Web 后台守护进程...\\033[0m\"\n");
        sb.append("if pgrep -f 'pi-web' >/dev/null 2>&1; then\n");
        sb.append("  echo \"\\033[1;32m✔ Pi-Web 已经在后台运行中\\033[0m\"\n");
        sb.append("elif command -v pi-web >/dev/null 2>&1; then\n");
        sb.append("  setsid env -u HTTP_PROXY -u http_proxy nohup pi-web --no-open -H 127.0.0.1 -p 30141 > \"$LOGDIR/pi-web.log\" 2>&1 &\n");
        sb.append("  echo \"\\033[1;36m• 已发送后台守护拉起指令，正在验证端口...\\033[0m\"\n");
        sb.append("else\n");
        sb.append("  echo \"\\033[1;31m✘ 缺少 pi-web 执行文件，无法拉起守护\\033[0m\"\n");
        sb.append("  exit 1\n");
        sb.append("fi\n\n");

        // ---- 阶段 4: 健康自检与端口校验 ----
        sb.append("echo \"\\033[1;33m[阶段 4/4] 验证端口 30141 存活应答 (最多 15 秒)...\\033[0m\"\n");
        sb.append("PORT_READY=0\n");
        sb.append("for i in $(seq 1 15); do\n");
        sb.append("  if pgrep -f 'pi-web' >/dev/null 2>&1; then\n");
        sb.append("    PORT_READY=1\n");
        sb.append("    break\n");
        sb.append("  fi\n");
        sb.append("  sleep 1\n");
        sb.append("done\n\n");

        sb.append("if [ $PORT_READY -eq 1 ]; then\n");
        sb.append("  mark_step_done \"PI_WEB_RUNNING\"\n");
        sb.append("  echo \"==================================================\"\n");
        sb.append("  echo \"\\033[1;32m🎉 恭喜！Pi-Web 一键部署全流程成功！\\033[0m\"\n");
        sb.append("  echo \"\\033[1;32m• 访问端口: 30141\\033[0m\"\n");
        sb.append("  echo \"\\033[1;32m• 运行日志: $LOGDIR/pi-web.log\\033[0m\"\n");
        sb.append("  echo \"\\033[1;36m• 提示: 您可直接点击界面上的【直达 Web 控制台】开始使用！\\033[0m\"\n");
        sb.append("  echo \"==================================================\"\n");
        sb.append("else\n");
        sb.append("  echo \"\\033[1;31m✘ 端口未能在预期时间内响应，请查看运行日志: $LOGDIR/pi-web.log\\033[0m\"\n");
        sb.append("fi\n\n");

        try (FileOutputStream fos = new FileOutputStream(scriptFile)) {
            fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            fos.flush();
            scriptFile.setExecutable(true);
        } catch (Throwable ignored) {}

        return scriptFile;
    }
}
