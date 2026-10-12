# PiMet

> Pi-Web 的独立 Android 运行时：内置终端，零外部依赖部署。

PiMet 在 Android 上通过 PRoot 运行一个精简的 Linux 容器（Node.js、Git、Bash），一键部署并托管 Pi-Web 控制台。不需要 Termux 或其他外部 App，装好 APK 就能用。

## 功能

- **内置 Linux 运行时**：基于 PRoot 的容器，自动下载并解压精简版 rootfs（支持 arm64-v8a 与 x86_64）
- **一键部署 Pi-Web**：本地 Web 服务默认端口 `30141`，前台服务保活，可开机自启
- **原生终端**：ANSI 渲染、自定义快捷键、可调字号、环境诊断（Environment Doctor）
- **网络优化**：npm 镜像（npmmirror / 官方）与 rootfs 下载镜像（gh-proxy、ghfast 等）自动选路
- **AI 桌宠**：全局悬浮窗宠物，支持 AI 对话、记忆，以及 PetDex 社区素材商城
- **子代理（Sub-Agent）管理**：在应用内创建和管理子代理
- **手机自动化**：基于无障碍服务，让大模型 / 桌宠操控手机
- **Shizuku 支持**：通过 Shizuku 获取更高权限
- **网页翻译**：AI、免费或自定义引擎，支持替换和双语对照模式
- **其他**：备份与恢复、插件、主题、应用内更新、多种本地服务监控

## 安装

在 [Releases](https://github.com/zr987459/pimet/releases) 下载最新的 `PiMet-vX.X.X.apk` 并安装。

**系统要求**：Android 8.0（API 26）及以上，arm64-v8a 或 x86_64 设备。

首次启动会下载 Linux 运行底包，请保持网络畅通，并预留足够的存储空间。

## 从源码构建

需要 JDK 17。

```bash
git clone https://github.com/zr987459/pimet.git
cd pimet
./gradlew assembleRelease
```

生成的 APK 位于 `app/build/outputs/apk/release/`。

## 自动构建与发布

- **`build.yml`**：每次推送到 `main` 时自动构建 APK，并按 `app/build.gradle` 中的 `versionName` 发布为新的 Release（如 `v1.6.7`）
- **`build-slim-rootfs.yml`**：手动触发，生成精简版 rootfs 底包（剔除无关组件与缓存，保留 Node.js 24.x、Git、Bash），发布到 `runtime-latest`

> 发布新版本前，记得先更新 `app/build.gradle` 中的 `versionCode` 与 `versionName`。

## 权限说明

PiMet 会申请以下主要权限：

| 权限 | 用途 |
| --- | --- |
| 网络 | 下载运行时、npm 依赖，运行本地 Web 服务 |
| 前台服务 / 唤醒锁 / 忽略电池优化 | 保持 Linux 容器和 Web 服务后台运行 |
| 悬浮窗 | 显示桌面宠物 |
| 存储 / 媒体 | 读写文件、备份与恢复、附件上传 |
| 无障碍服务 | 手机自动化（可选，需手动开启） |
| 开机自启 | 自动启动 Web 服务（可选） |

## 项目结构

```
app/src/main/java/com/xm486/pimet/
├── proot/        PRoot 容器与 Pi-Web 管理
├── terminal/     终端会话、下载器、环境诊断、网络选路
├── pet/          AI 桌宠与 PetDex 商城
├── subagent/     子代理管理
├── automation/   无障碍自动化服务
├── translation/  网页翻译
├── monitor/      本地服务与端口监控
├── bridge/       应用桥接
└── ui/           界面组件
```
