package com.xm486.pimet.terminal;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

/**
 * 环境与依赖自检工具（Environment Doctor）：
 * 专注于 Pi-Web 运行所需依赖（Node.js >= 22.19.0, npm, Termux 工具链, 端口 30141 存活性），
 * 并提供准确的诊断与自愈建议。
 */
public class EnvironmentDoctor {

    public static class DiagnosticResult {
        public String arch = "未知";
        public boolean isArm64 = false;

        public boolean hasNode = false;
        public String nodeVersion = "";
        public boolean isNodeVersionSupported = false; // >= 22.19.0

        public boolean hasNpm = false;
        public boolean hasCurl = false;
        public boolean hasWget = false;

        public boolean hasPiWeb = false;
        public boolean port30141Alive = false;

        public boolean termuxInstalled = false;
        public boolean termuxAccessible = false;

        public List<String> issues = new ArrayList<>();
        public List<String> fixSuggestions = new ArrayList<>();

        public boolean isReadyForPiWeb() {
            return hasNode && isNodeVersionSupported && (hasPiWeb || hasNpm);
        }
    }

    /**
     * 检测本地 127.0.0.1:30141 是否已开放响应
     */
    public static boolean checkPort30141Alive() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", 30141), 600);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 判断 Node.js 版本是否 >= 22.19.0
     */
    public static boolean checkNodeVersionSupported(String versionStr) {
        if (versionStr == null || !versionStr.startsWith("v")) return false;
        try {
            String clean = versionStr.substring(1).trim();
            String[] parts = clean.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            if (major > 22) return true;
            if (major == 22) return minor >= 19;
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 执行全面环境诊断
     */
    public static DiagnosticResult diagnose(Context context) {
        DiagnosticResult result = new DiagnosticResult();

        // 0. 端口 30141 存活检测
        result.port30141Alive = checkPort30141Alive();

        // 1. 架构检测
        String[] abis = Build.SUPPORTED_ABIS;
        if (abis != null && abis.length > 0) {
            result.arch = abis[0];
            for (String abi : abis) {
                if ("arm64-v8a".equalsIgnoreCase(abi) || "aarch64".equalsIgnoreCase(abi)) {
                    result.isArm64 = true;
                    break;
                }
            }
        }
        if (!result.isArm64) {
            String osArch = System.getProperty("os.arch");
            if (osArch != null && (osArch.contains("aarch64") || osArch.contains("arm64"))) {
                result.isArm64 = true;
                result.arch = osArch;
            }
        }

        // 2. Termux 检测
        PackageManager pm = context.getPackageManager();
        try {
            PackageInfo pi = pm.getPackageInfo("com.termux", 0);
            result.termuxInstalled = (pi != null);
        } catch (Throwable ignored) {
            result.termuxInstalled = false;
        }

        File termuxBinDir = new File("/data/data/com.termux/files/usr/bin");
        if (termuxBinDir.exists() && termuxBinDir.canRead()) {
            result.termuxAccessible = true;
        }

        // 3. 命令与工具链检测 (探测 PATH 与常见目录)
        File filesDir = context.getFilesDir();
        File appBin = new File(filesDir, "bin");
        File appUsrBin = new File(filesDir, "usr/bin");

        String customPath = appBin.getAbsolutePath() + ":" + appUsrBin.getAbsolutePath()
                + ":/data/data/com.termux/files/usr/bin:/system/bin:/system/xbin";

        result.nodeVersion = execOutput("node -v", customPath);
        result.hasNode = result.nodeVersion != null && result.nodeVersion.startsWith("v");
        result.isNodeVersionSupported = checkNodeVersionSupported(result.nodeVersion);

        String npmVer = execOutput("npm -v", customPath);
        result.hasNpm = npmVer != null && !npmVer.trim().isEmpty();

        String curlCheck = execOutput("curl --version", customPath);
        result.hasCurl = curlCheck != null && curlCheck.contains("curl");

        String wgetCheck = execOutput("wget --version", customPath);
        result.hasWget = wgetCheck != null && wgetCheck.contains("wget");

        String piWebCheck = execOutput("pi-web --version", customPath);
        result.hasPiWeb = piWebCheck != null && !piWebCheck.trim().isEmpty();

        // 4. 问题分析与修复建议汇总
        if (result.port30141Alive) {
            // 已在运行，状态极佳
            return result;
        }

        if (!result.hasNode) {
            result.issues.add("缺少 Node.js 运行时 (Pi-Web 强依赖 Node >= 22.19.0)");
            result.fixSuggestions.add("【安装 Node.js 指引】：");
            result.fixSuggestions.add("• 推荐在 Termux 运行：pkg update && pkg install nodejs");
            result.fixSuggestions.add("• Termux 最新稳定包提供 Node 24+/26，完美适配 Pi-Web！");
        } else if (!result.isNodeVersionSupported) {
            result.issues.add("当前 Node.js 版本为 " + result.nodeVersion.trim() + "，低于 Pi-Web 要求的 v22.19.0+");
            result.fixSuggestions.add("请升级 Node.js：在 Termux 运行 pkg install nodejs (安装最新 Node 24+)");
        }

        if (result.hasNode && !result.hasNpm) {
            result.issues.add("检测到 node 但未找到 npm 包管理器。");
            result.fixSuggestions.add("请在 Termux 运行：pkg install nodejs-lts 或 npm install -g npm 进行修复。");
        }

        return result;
    }

    /**
     * 格式化为用于终端 ANSI 彩色打印的诊断报告
     */
    public static String formatConsoleReport(DiagnosticResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\u001B[1;36m==================== 🔍 Pi-Web 环境自检报告 ====================\u001B[0m\n");

        sb.append("• 端口 30141: ").append(r.port30141Alive ? "\u001B[1;32m✔ 运行中 (HTTP 服务存活)\u001B[0m" : "\u001B[33m⚪ 未运行\u001B[0m").append("\n");
        sb.append("• 核心架构  : ").append(r.isArm64 ? "\u001B[32m✔ " + r.arch + "\u001B[0m" : "\u001B[31m✘ " + r.arch + "\u001B[0m").append("\n");

        if (r.hasNode) {
            if (r.isNodeVersionSupported) {
                sb.append("• Node.js   : \u001B[1;32m✔ ").append(r.nodeVersion.trim()).append(" (满足 >= 22.19.0)\u001B[0m\n");
            } else {
                sb.append("• Node.js   : \u001B[1;31m✘ ").append(r.nodeVersion.trim()).append(" (版本过低，要求 >= 22.19.0)\u001B[0m\n");
            }
        } else {
            sb.append("• Node.js   : \u001B[31m✘ 未安装 (需 >= 22.19.0)\u001B[0m\n");
        }

        sb.append("• npm 管理器: ").append(r.hasNpm ? "\u001B[32m✔ 已就绪\u001B[0m" : "\u001B[33m✘ 未安装\u001B[0m").append("\n");
        sb.append("• Termux桥接: ").append(r.termuxInstalled ? "\u001B[32m✔ 已安装\u001B[0m" : "\u001B[90m⚪ 未安装\u001B[0m").append("\n");
        sb.append("• Pi-Web产物: ").append(r.hasPiWeb ? "\u001B[32m✔ 已安装\u001B[0m" : "\u001B[33m待一键部署\u001B[0m").append("\n");

        if (r.port30141Alive) {
            sb.append("\n\u001B[1;32m🎉 Pi-Web 服务正在后台平稳运行中！访问端口: 30141\u001B[0m\n");
        } else if (!r.issues.isEmpty()) {
            sb.append("\n\u001B[1;31m⚠️ 发现依赖缺失问题:\u001B[0m\n");
            for (String issue : r.issues) {
                sb.append("  - ").append(issue).append("\n");
            }
            if (!r.fixSuggestions.isEmpty()) {
                sb.append("\n\u001B[1;33m💡 前置环境准备建议:\u001B[0m\n");
                for (String sug : r.fixSuggestions) {
                    sb.append("  ").append(sug).append("\n");
                }
            }
        } else {
            sb.append("\n\u001B[1;32m🎉 基础依赖完整，点击【一键部署】即可拉起 Pi-Web！\u001B[0m\n");
        }

        sb.append("\u001B[1;36m===============================================================\u001B[0m\n");
        return sb.toString();
    }

    private static String execOutput(String command, String customPath) {
        try {
            ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", "export PATH=\"" + customPath + ":$PATH\"; " + command);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor();
                return line != null ? line.trim() : null;
            }
        } catch (Throwable ignored) {
            return null;
        }
    }
}
