# PiMet 界面重构（ui-refactor）

用户授权主控做决策。目标：删无用界面，让 Pi 与 PiMet 插件真正联动。审计结论见下。

## 第一阶段（beta.19，已提交待测）
- 删除插件商店（精选市场 + 热门生态网站），只保留输入框粘贴 npm 包名/git 地址安装
- 启停只写 ~/.pi/agent/settings.json（"-" 前缀=停用），废弃 .disabled 改名与 SharedPreferences；旧 .disabled 自动迁移
- syncPlugins 去掉硬编码 oh-my-pi/@bacnh85，不删 "-" 条目，不再显式追加 extensions/*.ts
- 不再列全局 node_modules；去掉中文注释表
- android-bridge 扩展从 Java 字符串移到 assets/extensions/pimet-android-bridge.ts
- 隐藏 MCP/子代理分类；插件变更后弹「立即重载 Pi」
- versionCode 111 / 1.9.0-beta.19

## 第二阶段（等用户测完 beta.19）
- 隐藏桌宠全套与 Operit/ClawBench/RikkaHub 监控入口及设置端口卡（先隐藏，下一版删代码：pet/*、PetShopActivity、monitor 三件、PetOverlayService）
- 底栏改 3 页：Pi（状态条+Pi-Web）/ 浏览器 / 我的（Pi 能力、文件备份、系统、外观、更新、帮助）
- 主题切换只留设置一处；缩放对话框合并为一份
- 「问 Pi」改走 Pi-Web 会话接口，不再注入 textarea
- bridge 扩展加 pimet_plugins 工具（list/install/toggle）；去掉桌宠类工具
- 无障碍权限由用户手动开启，在「我的-权限」引导
- 帮助重写为 3 节

## 规则
只在 feature/pi-browser-opt 提交；未经用户确认不合并 main。

## beta.20（插件页对齐 Pi-Web）
- 插件页改为 模型 / 技能 / 子代理 / 插件 / MCP 五类，直接调用 Pi-Web 本机 API（见 docs/piweb-api.md，客户端 PiWebApi.java），Pi-Web 是唯一真相
- Pi-Web 未运行时显示「启动 Pi / 重试」
- 仅插件类变更后提示重载 Pi
