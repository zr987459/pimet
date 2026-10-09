package com.xm486.pimet.subagent;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.xm486.pimet.proot.ProotManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pi / Pi-Web 标准子代理核心管理类
 * 负责与 PRoot 容器内 ~/.pi/agent/agents/*.md 进行双向同步与增删改查
 */
public final class SubAgentManager {
    private static final String TAG = "SubAgentManager";

    /**
     * 获取容器内全局子代理目录: ~/.pi/agent/agents/
     */
    public static File getGlobalAgentsDir(Context context) {
        File rootfs = ProotManager.getRootfsDir(context);
        if (rootfs == null) return null;
        File dir = new File(rootfs, "root/.pi/agent/agents");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /**
     * 发现并列出所有子代理（全局与项目）
     */
    public static List<SubAgentInfo> listAllAgents(Context context) {
        List<SubAgentInfo> list = new ArrayList<>();
        File globalDir = getGlobalAgentsDir(context);
        if (globalDir != null && globalDir.exists()) {
            File[] files = globalDir.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (f.isFile() && f.getName().endsWith(".md")) {
                        SubAgentInfo info = parseAgentFile(f, "global");
                        if (info != null) {
                            list.add(info);
                        }
                    }
                }
            }
        }

        // 默认按名称字母排序
        Collections.sort(list, (a, b) -> a.id.compareToIgnoreCase(b.id));
        return list;
    }

    /**
     * 解析单个 .md 文件为 SubAgentInfo
     */
    public static SubAgentInfo parseAgentFile(File file, String scope) {
        if (file == null || !file.exists()) return null;
        SubAgentInfo agent = new SubAgentInfo();
        String fileName = file.getName();
        agent.id = fileName.substring(0, fileName.length() - 3);
        agent.filePath = file.getAbsolutePath();
        agent.scope = scope;
        agent.name = agent.id;

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            boolean inFrontmatter = false;
            boolean frontmatterClosed = false;
            StringBuilder bodySb = new StringBuilder();

            while ((line = reader.readLine()) != null) {
                String trim = line.trim();
                if (!inFrontmatter && !frontmatterClosed && trim.equals("---")) {
                    inFrontmatter = true;
                    continue;
                }
                if (inFrontmatter && trim.equals("---")) {
                    inFrontmatter = false;
                    frontmatterClosed = true;
                    continue;
                }

                if (inFrontmatter) {
                    int colonIdx = line.indexOf(':');
                    if (colonIdx > 0) {
                        String key = line.substring(0, colonIdx).trim().toLowerCase();
                        String val = line.substring(colonIdx + 1).trim();

                        // 剔除引号
                        if ((val.startsWith("\"") && val.endsWith("\"")) ||
                            (val.startsWith("'") && val.endsWith("'"))) {
                            val = val.substring(1, val.length() - 1).trim();
                        }

                        switch (key) {
                            case "name":
                                agent.name = val;
                                break;
                            case "description":
                                agent.description = val;
                                break;
                            case "model":
                                agent.model = val;
                                break;
                            case "thinking":
                                agent.thinking = val;
                                break;
                            case "maxturns":
                                try {
                                    agent.maxTurns = Integer.parseInt(val);
                                } catch (Throwable ignored) {}
                                break;
                            case "inheritcontext":
                                agent.inheritContext = "true".equalsIgnoreCase(val);
                                break;
                            case "background":
                                agent.background = "true".equalsIgnoreCase(val);
                                break;
                            case "skills":
                                agent.loadSkills = "true".equalsIgnoreCase(val);
                                break;
                            case "extensions":
                                agent.loadExtensions = "true".equalsIgnoreCase(val);
                                break;
                            case "tools":
                                parseToolsLine(val, agent.tools);
                                break;
                        }
                    }
                } else if (frontmatterClosed) {
                    bodySb.append(line).append("\n");
                }
            }

            agent.systemPrompt = bodySb.toString().trim();
            return agent;
        } catch (Throwable t) {
            Log.w(TAG, "parseAgentFile failed: " + file.getName(), t);
            return null;
        }
    }

    private static void parseToolsLine(String val, List<String> target) {
        if (TextUtils.isEmpty(val)) return;
        String clean = val.replace("[", "").replace("]", "").trim();
        String[] parts = clean.split(",");
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty() && !target.contains(t)) {
                target.add(t);
            }
        }
    }

    /**
     * 保存或更新子代理文件到容器目录
     */
    public static boolean saveAgent(Context context, SubAgentInfo agent) {
        if (agent == null || TextUtils.isEmpty(agent.id)) return false;
        try {
            File dir = getGlobalAgentsDir(context);
            if (dir == null) return false;
            if (!dir.exists()) dir.mkdirs();

            String cleanId = agent.id.trim().replaceAll("[^a-zA-Z0-9_.-]", "_");
            File targetFile = new File(dir, cleanId + ".md");

            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("name: ").append(TextUtils.isEmpty(agent.name) ? cleanId : agent.name).append("\n");
            if (!TextUtils.isEmpty(agent.description)) {
                sb.append("description: ").append(agent.description.replace("\n", " ")).append("\n");
            }

            if (agent.tools != null && !agent.tools.isEmpty()) {
                sb.append("tools: ");
                for (int i = 0; i < agent.tools.size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append(agent.tools.get(i));
                }
                sb.append("\n");
            }

            if (!TextUtils.isEmpty(agent.model)) {
                sb.append("model: ").append(agent.model.trim()).append("\n");
            }

            if (!TextUtils.isEmpty(agent.thinking)) {
                sb.append("thinking: ").append(agent.thinking.trim()).append("\n");
            }

            sb.append("maxTurns: ").append(agent.maxTurns > 0 ? agent.maxTurns : 10).append("\n");
            sb.append("inheritContext: ").append(agent.inheritContext).append("\n");
            sb.append("background: ").append(agent.background).append("\n");
            sb.append("skills: ").append(agent.loadSkills).append("\n");
            sb.append("extensions: ").append(agent.loadExtensions).append("\n");
            sb.append("---\n\n");

            if (!TextUtils.isEmpty(agent.systemPrompt)) {
                sb.append(agent.systemPrompt.trim()).append("\n");
            }

            try (FileOutputStream fos = new FileOutputStream(targetFile)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            }

            agent.filePath = targetFile.getAbsolutePath();
            Log.i(TAG, "Saved agent to " + targetFile.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "saveAgent failed", t);
            return false;
        }
    }

    /**
     * 删除子代理文件
     */
    public static boolean deleteAgent(Context context, SubAgentInfo agent) {
        if (agent == null) return false;
        try {
            if (!TextUtils.isEmpty(agent.filePath)) {
                File f = new File(agent.filePath);
                if (f.exists()) return f.delete();
            }
            File dir = getGlobalAgentsDir(context);
            if (dir != null) {
                File f = new File(dir, agent.id + ".md");
                if (f.exists()) return f.delete();
            }
            return false;
        } catch (Throwable t) {
            Log.e(TAG, "deleteAgent failed", t);
            return false;
        }
    }

    /**
     * 获取预置常用工具清单
     */
    public static String[] getPresetTools() {
        return new String[]{
                "read", "bash", "edit", "write", "grep", "find", "ls",
                "control_desktop_pet", "control_app_ui"
        };
    }

    /**
     * 获取可选思考级别
     */
    public static String[] getThinkingLevels() {
        return new String[]{
                "跟随父会话 (默认)", "off", "minimal", "low", "medium", "high", "xhigh", "max"
        };
    }
}
