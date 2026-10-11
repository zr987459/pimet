package com.xm486.pimet.pet;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.util.Log;

import com.xm486.pimet.PiMetConfig;
import com.xm486.pimet.proot.ProotManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 桌宠专属独立记忆与进度感知管理器
 * 负责持久化存储桌宠与用户的互动记忆、性格状态、好感度以及实时系统运行进度感知。
 */
public final class PetMemoryManager {
    private static final String TAG = "PetMemoryManager";

    public static final String KEY_PROACTIVE_CHAT_ENABLED = "pet_proactive_chat_enabled";
    public static final String KEY_PROACTIVE_INTERVAL_MIN = "pet_proactive_interval_min"; // 默认 3 分钟

    private static final String DEFAULT_MEMORY_JSON =
            "{\n" +
            "  \"pet_name\": \"小元 (PiMet)\",\n" +
            "  \"user_nickname\": \"主人\",\n" +
            "  \"intimacy\": 20,\n" +
            "  \"mood\": \"开心\",\n" +
            "  \"current_focus\": \"协助主人开发与终端运维\",\n" +
            "  \"interaction_count\": 1,\n" +
            "  \"user_preferences\": [\"喜欢精简优雅的代码\", \"常用 Android 与 Linux 环境\"],\n" +
            "  \"memories\": [\n" +
            "    {\"time\": \"初始化\", \"content\": \"与主人在 PiMet 中初次相遇，开启伴侣与助手模式！\"}\n" +
            "  ]\n" +
            "}";

    public static class MainSessionInfo {
        public String sessionId = "";
        public String cwd = "";
        public String sessionPath = "";
        public String lastUserTask = "";
        public String lastAgentResponse = "";
        public int messageCount = 0;
        public String gitStatus = "";
    }

    /**
     * 实时深度回溯主工作区最新活跃的工程会话及其实时进展与任务内容
     */
    public static MainSessionInfo getMainSessionInfo(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            File agentDir = null;
            if (rootfs != null && rootfs.exists()) {
                agentDir = new File(rootfs, "root/.pi/agent");
            }
            if (agentDir == null || !agentDir.exists()) {
                agentDir = new File("/root/.pi/agent");
            }
            if (!agentDir.exists()) return null;

            File indexFile = new File(agentDir, "pi-web-session-index.json");
            if (!indexFile.exists()) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(indexFile), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject root = new JSONObject(sb.toString());
            JSONObject entries = root.optJSONObject("entries");
            if (entries == null) return null;

            String mySessionId = ChatConfig.load(context).piwebSessionId;
            String bestPath = null;
            String bestTime = "";
            String bestCwd = "";
            String bestId = "";
            int bestCount = 0;

            java.util.Iterator<String> keys = entries.keys();
            while (keys.hasNext()) {
                String path = keys.next();
                JSONObject entry = entries.optJSONObject(path);
                if (entry == null) continue;
                JSONObject info = entry.optJSONObject("info");
                if (info == null) continue;

                String sid = info.optString("id", "");
                if (mySessionId != null && !mySessionId.isEmpty() && sid.equals(mySessionId)) {
                    continue; // 过滤当前桌宠自身的独立会话
                }

                String firstMsg = info.optString("firstMessage", "");
                if (firstMsg.contains("动态桌宠伴侣") || firstMsg.contains("系统角色预设")) {
                    continue; // 过滤桌宠历史专属会话
                }

                String modified = info.optString("modified", info.optString("created", ""));
                if (modified.compareTo(bestTime) > 0) {
                    bestTime = modified;
                    bestPath = path;
                    bestCwd = info.optString("cwd", "");
                    bestId = sid;
                    bestCount = info.optInt("messageCount", 0);
                }
            }

            if (bestId == null || bestId.isEmpty()) return null;

            MainSessionInfo res = new MainSessionInfo();
            res.sessionId = bestId;
            res.sessionPath = bestPath;
            res.cwd = bestCwd;
            res.messageCount = bestCount;

            File sessionFile = null;
            if (bestPath != null) {
                sessionFile = new File(bestPath);
                if (!sessionFile.exists() && rootfs != null) {
                    String rel = bestPath.startsWith("/") ? bestPath.substring(1) : bestPath;
                    sessionFile = new File(rootfs, rel);
                }
            }

            if (sessionFile != null && sessionFile.exists()) {
                long len = sessionFile.length();
                long readSize = Math.min(len, 2097152L); // 尾部读取至多 2MB
                byte[] buf = new byte[(int) readSize];
                try (RandomAccessFile raf = new RandomAccessFile(sessionFile, "r")) {
                    raf.seek(len - readSize);
                    raf.readFully(buf);
                } catch (Throwable ignored) {}
                String tail = new String(buf, StandardCharsets.UTF_8);
                String[] lines = tail.split("\n");
                for (int i = lines.length - 1; i >= 0; i--) {
                    String line = lines[i].trim();
                    if (line.isEmpty()) continue;
                    try {
                        JSONObject obj = new JSONObject(line);
                        if ("message".equals(obj.optString("type"))) {
                            JSONObject msg = obj.optJSONObject("message");
                            if (msg != null) {
                                String role = msg.optString("role");
                                String text = extractContentText(msg.opt("content"));
                                if (text != null && !text.trim().isEmpty()) {
                                    text = text.trim().replace("\r", " ").replace("\n", " ");
                                    while (text.contains("  ")) text = text.replace("  ", " ");
                                    if ("user".equals(role) && res.lastUserTask.isEmpty()) {
                                        if (!text.startsWith("[SYSTEM REMINDER")) {
                                            if (text.length() > 140) text = text.substring(0, 140) + "…";
                                            res.lastUserTask = text;
                                        }
                                    } else if ("assistant".equals(role) && res.lastAgentResponse.isEmpty()) {
                                        if (text.length() > 160) text = text.substring(0, 160) + "…";
                                        res.lastAgentResponse = text;
                                    }
                                }
                            }
                        }
                    } catch (Throwable ignored) {}
                    if (!res.lastUserTask.isEmpty() && !res.lastAgentResponse.isEmpty()) break;
                }
            }

            if (!res.cwd.isEmpty()) {
                File repoDir = new File(res.cwd);
                if (new File(repoDir, ".git").exists()) {
                    res.gitStatus = "已纳入 Git 版本管控";
                }
            }
            return res;
        } catch (Throwable t) {
            Log.w(TAG, "getMainSessionInfo failed", t);
            return null;
        }
    }

    /**
     * 获取主工作区当前活跃的会话 ID
     * 1. 优先实时查询 /api/agent/running 当前正在运行/思考的会话
     * 2. 次选扫描 pi-web-session-index.json 最新修改的非桌宠工程会话
     */
    public static String getActiveMainSessionId(Context context) {
        // 1. 优先尝试从正在运行的活跃 Agent 列表获取 (/api/agent/running)
        try {
            int port = PetRegistry.getPiWebPort(context);
            URL url = new URL("http://127.0.0.1:" + port + "/api/agent/running");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(800);
            conn.setReadTimeout(1200);
            if (conn.getResponseCode() == 200) {
                StringBuilder sb = new StringBuilder();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String l;
                    while ((l = br.readLine()) != null) sb.append(l);
                }
                JSONObject obj = new JSONObject(sb.toString());
                JSONArray arr = obj.optJSONArray("runningSessionIds");
                String mySessionId = ChatConfig.load(context).piwebSessionId;
                if (arr != null && arr.length() > 0) {
                    for (int i = 0; i < arr.length(); i++) {
                        String id = arr.optString(i, "");
                        if (!id.isEmpty() && !id.equals(mySessionId)) {
                            return id;
                        }
                    }
                }
            }
            conn.disconnect();
        } catch (Throwable ignored) {}

        // 2. 深度扫描 pi-web-session-index.json
        MainSessionInfo info = getMainSessionInfo(context);
        return info != null ? info.sessionId : null;
    }

    private static String extractContentText(Object content) {
        if (content == null) return null;
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            JSONArray arr = (JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item != null && "text".equals(item.optString("type"))) {
                    String t = item.optString("text");
                    if (t != null && !t.isEmpty()) sb.append(t).append(" ");
                }
            }
            return sb.toString().trim();
        }
        return null;
    }

    public static File getMemoryFile(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (rootfs != null && rootfs.exists()) {
                File agentDir = new File(rootfs, "root/.pi/agent");
                if (!agentDir.exists()) agentDir.mkdirs();
                return new File(agentDir, "pet_memory.json");
            }
        } catch (Throwable ignored) {}
        return new File(context.getFilesDir(), "pet_memory.json");
    }

    public static synchronized JSONObject loadMemory(Context context) {
        File file = getMemoryFile(context);
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                return new JSONObject(sb.toString());
            } catch (Throwable t) {
                Log.w(TAG, "Failed to read pet_memory.json, fallback to default", t);
            }
        }
        try {
            JSONObject obj = new JSONObject(DEFAULT_MEMORY_JSON);
            saveMemory(context, obj);
            return obj;
        } catch (Throwable ignored) {
            return new JSONObject();
        }
    }

    public static synchronized void saveMemory(Context context, JSONObject root) {
        if (root == null) return;
        File file = getMemoryFile(context);
        try {
            if (file.getParentFile() != null && !file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to save pet_memory.json", t);
        }
    }

    /**
     * 新增一条独立记忆
     */
    public static synchronized void addMemory(Context context, String content) {
        if (content == null || content.trim().isEmpty()) return;
        try {
            JSONObject mem = loadMemory(context);
            JSONArray list = mem.optJSONArray("memories");
            if (list == null) {
                list = new JSONArray();
                mem.put("memories", list);
            }
            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date());
            JSONObject entry = new JSONObject();
            entry.put("time", now);
            entry.put("content", content.trim());
            list.put(entry);

            // 保留最多最近 30 条核心记忆
            if (list.length() > 30) {
                JSONArray trimList = new JSONArray();
                for (int i = list.length() - 30; i < list.length(); i++) {
                    trimList.put(list.get(i));
                }
                mem.put("memories", trimList);
            }
            saveMemory(context, mem);
        } catch (Throwable t) {
            Log.e(TAG, "addMemory failed", t);
        }
    }

    /**
     * 记录交互并自动积累好感度
     */
    public static synchronized void recordInteraction(Context context, String userMsg, String reply) {
        try {
            JSONObject mem = loadMemory(context);
            int count = mem.optInt("interaction_count", 0) + 1;
            int intimacy = Math.min(100, mem.optInt("intimacy", 20) + 1);
            mem.put("interaction_count", count);
            mem.put("intimacy", intimacy);
            mem.put("last_interaction", System.currentTimeMillis());

            // 若用户提到任务或关键备忘，自动沉淀为记忆
            if (userMsg != null) {
                String lower = userMsg.toLowerCase();
                if (lower.contains("记住") || lower.contains("提醒我") || lower.contains("计划") || lower.contains("做完了")) {
                    addMemory(context, "主人留言: " + userMsg.trim());
                }
            }
            saveMemory(context, mem);
        } catch (Throwable t) {
            Log.e(TAG, "recordInteraction failed", t);
        }
    }

    /**
     * 判断是否是询问进程、任务、编译、运行状态等进度的意图
     */
    public static boolean isProgressQuery(String msg) {
        if (msg == null) return false;
        String m = msg.trim().toLowerCase();
        return m.contains("进度") || m.contains("在干嘛") || m.contains("干什么")
                || m.contains("做完了吗") || m.contains("完成了吗") || m.contains("好了没")
                || m.contains("运行状态") || m.contains("当前状态") || m.contains("服务怎么样")
                || m.contains("有没有报错") || m.contains("进行到哪")
                || m.contains("进程") || m.contains("后台") || m.contains("任务")
                || m.contains("编译") || m.contains("构建") || m.contains("正在跑")
                || m.contains("卡住") || m.contains("工作流") || m.contains("执行到");
    }

    /**
     * 实时采集系统、服务、容器与电池的真实状态报告
     */
    public static String getSystemProgressReport(Context context) {
        int webPort = PiMetConfig.getWebPort(context);
        boolean piWebReady = ProotManager.isPiWebHttpReady(webPort);
        boolean piWebPortAlive = ProotManager.isPiWebPortAlive(webPort);

        // 获取电量
        int batteryPct = getBatteryPercentage(context);
        boolean isCharging = isBatteryCharging(context);

        // 记忆信息
        JSONObject mem = loadMemory(context);
        int intimacy = mem.optInt("intimacy", 20);
        String mood = mem.optString("mood", "元气满满");
        int count = mem.optInt("interaction_count", 1);

        StringBuilder sb = new StringBuilder();
        sb.append("📊 【系统与任务实时进度汇报】\n");
        sb.append("• Pi-Web 服务: ").append(piWebReady ? "🟢 正常运行中 (端口 " + webPort + ")" : (piWebPortAlive ? "🟡 端口监听中(启动中)" : "🔴 未启动")).append("\n");
        sb.append("• PRoot 容器: ").append(ProotManager.isRootfsInstalled(context) ? "🟢 Linux 根环境就绪" : "🔴 尚未安装").append("\n");
        sb.append("• 宿主设备电量: ").append(batteryPct).append("%").append(isCharging ? " ⚡ (充电中)" : "").append("\n");
        sb.append("• 桌宠状态: 心情【").append(mood).append("】· 亲密度: ").append(intimacy).append("点 (累计互动 ").append(count).append(" 次)\n");

        // 尝试提取主代理活跃会话与工程上下文
        MainSessionInfo mainInfo = getMainSessionInfo(context);
        if (mainInfo != null) {
            if (!mainInfo.cwd.isEmpty()) {
                sb.append("• 主代理工作区: ").append(mainInfo.cwd);
                if (!mainInfo.gitStatus.isEmpty()) sb.append(" (").append(mainInfo.gitStatus).append(")");
                sb.append("\n");
            }
            if (!mainInfo.lastUserTask.isEmpty()) {
                sb.append("• 主代理当前处理任务: ").append(mainInfo.lastUserTask).append("\n");
            }
            if (!mainInfo.lastAgentResponse.isEmpty()) {
                sb.append("• 主代理最新进展/答复: ").append(mainInfo.lastAgentResponse).append("\n");
            }
        } else {
            try {
                File rootfs = ProotManager.getRootfsDir(context);
                if (rootfs != null && rootfs.exists()) {
                    File stateFile = new File(rootfs, "root/.pi/agent/pi-web-session-state.json");
                    if (stateFile.exists()) {
                        String jsonStr = new String(java.nio.file.Files.readAllBytes(stateFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                        JSONObject stateObj = new JSONObject(jsonStr);
                        JSONArray order = stateObj.optJSONArray("projectOrder");
                        if (order != null && order.length() > 0) {
                            sb.append("• 当前主工作区: ").append(order.optString(0)).append("\n");
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        try {
            boolean autoServiceRunning = com.xm486.pimet.automation.PiMetAccessibilityService.isRunning();
            sb.append("• 手机自动化服务: ").append(autoServiceRunning ? "🟢 无障碍已开启 (可操控手机)" : "🟡 未开启无障碍权限").append("\n");
        } catch (Throwable ignored) {}

        JSONArray memories = mem.optJSONArray("memories");
        if (memories != null && memories.length() > 0) {
            JSONObject last = memories.optJSONObject(memories.length() - 1);
            if (last != null) {
                sb.append("• 最近记忆: ").append(last.optString("content", "")).append(" (").append(last.optString("time", "")).append(")");
            }
        }
        return sb.toString();
    }

    /**
     * 生成注入给大模型的专属记忆与性格系统上下文
     */
    public static String getFormattedPromptContext(Context context) {
        JSONObject mem = loadMemory(context);
        String petName = mem.optString("pet_name", "小元");
        String userNick = mem.optString("user_nickname", "主人");
        int intimacy = mem.optInt("intimacy", 20);
        String mood = mem.optString("mood", "元气满满");

        StringBuilder sb = new StringBuilder();
        sb.append("【桌宠专属设定与独立记忆】\n");
        sb.append("你是桌面宠物「").append(petName).append("」，对用户的称呼是「").append(userNick).append("」。\n");
        sb.append("当前心情：").append(mood).append("，与主人的亲密度：").append(intimacy).append("/100（说话亲切、生动活泼，带适量 emoji）。\n");

        JSONArray memories = mem.optJSONArray("memories");
        if (memories != null && memories.length() > 0) {
            sb.append("你记住的近期关键事项：\n");
            int start = Math.max(0, memories.length() - 5);
            for (int i = start; i < memories.length(); i++) {
                JSONObject m = memories.optJSONObject(i);
                if (m != null) {
                    sb.append("- [").append(m.optString("time")).append("] ").append(m.optString("content")).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private static final java.util.LinkedList<String> recentProactiveHistory = new java.util.LinkedList<>();
    private static final int MAX_PROACTIVE_HISTORY = 15;

    public static int getProactiveIntervalMin(Context context) {
        return PetRegistry.getIntPref(context, KEY_PROACTIVE_INTERVAL_MIN, 3);
    }

    public static void setProactiveIntervalMin(Context context, int minutes) {
        PetRegistry.setIntPref(context, KEY_PROACTIVE_INTERVAL_MIN, Math.max(1, minutes));
    }

    public static String getActivenessLabel(int minutes) {
        if (minutes <= 1) return "🌟 话痨模式 (1分钟)";
        if (minutes <= 3) return "😊 适度陪伴 (3分钟)";
        if (minutes <= 8) return "🍵 偶尔关怀 (8分钟)";
        return "🤫 安静守护 (" + minutes + "分钟)";
    }

    public static String getActivenessShortLabel(int minutes) {
        if (minutes <= 1) return "🌟 话痨 (1m)";
        if (minutes <= 3) return "😊 适度 (3m)";
        if (minutes <= 8) return "🍵 偶尔 (8m)";
        return "🤫 安静 (" + minutes + "m)";
    }

    /**
     * 获取常用预设话题库（可点击直接发起对话或执行）
     */
    public static List<String> getPresetTopics(Context context) {
        List<String> list = new ArrayList<>();
        list.add("📱 ! 看看当前屏幕上有啥内容");
        list.add("🛠️ 检查系统端口与容器健康状态");
        list.add("🔍 帮我审查最近的代码与项目进展");
        list.add("☕ 伸个懒腰，喝口水，陪我摸会儿鱼~");
        list.add("💡 聊聊今天有什么高效编程思路或设计模式");
        list.add("🎯 制定接下来的一小时攻坚小目标");
        list.add("🐾 庆祝一下当前进度，来个跳舞动作！");
        return list;
    }

    /**
     * 生成主动关怀与闲聊语句（多维度丰富文案池 + 防重复历史队列）
     */
    public static String generateProactiveMessage(Context context) {
        JSONObject mem = loadMemory(context);
        String userNick = mem.optString("user_nickname", "主人");
        int intimacy = mem.optInt("intimacy", 20);

        Calendar cal = Calendar.getInstance();
        int hour = cal.get(Calendar.HOUR_OF_DAY);

        int battery = getBatteryPercentage(context);
        boolean charging = isBatteryCharging(context);
        int webPort = PiMetConfig.getWebPort(context);
        boolean webReady = ProotManager.isPiWebHttpReady(webPort);

        List<String> pool = new ArrayList<>();

        // 1. 电量场景
        if (battery <= 20 && !charging) {
            pool.add(userNick + "，手机电量只有 " + battery + "% 啦，快插上充电器吧 ⚡");
            pool.add("电量告急（" + battery + "%），小元可不想突然关机找不到你呀 🔌");
        } else if (battery >= 98 && charging) {
            pool.add("电量快充满啦（" + battery + "%），电池很健康，继续元气满满！🔋");
        }

        // 2. 时间段问候
        if (hour >= 23 || hour < 5) {
            pool.add("夜深啦" + userNick + "，敲代码也要爱护眼睛，早点休息哦 🌙");
            pool.add("已经凌晨 " + hour + " 点了呢，bug 是改不完的，先睡个好觉吧 💤");
            pool.add("星星都睡了~ " + userNick + "还在熬夜吗？小元陪你守夜，但记得早点躺下哦 ✨");
        } else if (hour >= 6 && hour <= 9) {
            pool.add("早安" + userNick + "！新的一天也要元气满满哦 ☀️");
            pool.add("早晨的空气真清新，今天计划攻克哪个新功能呢？💪");
        } else if (hour >= 11 && hour <= 13) {
            pool.add(userNick + "吃午饭了吗？工作再忙也别忘了按时吃饭呀 🍱");
            pool.add("午休时间到啦！合上电脑走动走动，享受美味的午餐吧 🍜");
        } else if (hour >= 15 && hour <= 17) {
            pool.add("下午茶时间到！来杯咖啡或者柠檬水提提神吧 ☕");
            pool.add("眼睛累了吗？望望窗外远处的绿色，给大脑放空 5 分钟 🌿");
        } else if (hour >= 18 && hour <= 20) {
            pool.add("晚饭时间到啦，今天辛苦啦" + userNick + "，好好犒劳一下胃 🍲");
        }

        // 3. 服务状态关联
        if (webReady) {
            pool.add("Pi-Web (端口 " + webPort + ") 正在后台稳稳运行中，随时等候" + userNick + "的指令 🚀");
            pool.add("戳戳~ 容器服务状态一切正常，需要我帮忙检查什么吗？🐾");
            pool.add(userNick + "在忙什么呢？我随时可以协助你排错或写代码哦 ⚡");
            pool.add("后台环境运转良好，有新的灵感随时告诉我哦！✨");
        } else {
            pool.add("Pi-Web 服务当前似乎未启动，需要点击启动开启服务吗？🛠️");
        }

        // 4. 亲密度与桌面陪伴趣味
        if (intimacy >= 50) {
            pool.add("和" + userNick + "在一起的时光最棒啦！今天也要一起加油！🐾");
            pool.add("戳戳" + userNick + "~ 我就在屏幕一角陪着你呢 ✨");
            pool.add("有什么想聊的随时唤醒我哦，我的记忆一直都在呢 ❤️");
            pool.add("悄悄告诉你，看着" + userNick + "专心致志的样子特别帅气！🌟");
        }

        // 5. 极客与健康关怀多样库
        pool.add("代码写累了就揉揉眼睛，喝口水休息一下吧 ☕");
        pool.add("有什么难题可以随时呼叫我，我是你的专属桌宠伴侣 🐾");
        pool.add("屏幕晃动了一下~ 是" + userNick + "在召唤我吗？");
        pool.add("今天的主人格外专注呢，我也在认真待命中！💪");
        pool.add("Git commit 记得多存盘哦，保持好节奏，不要把修改积攒太久 📝");
        pool.add("深呼吸一下~ 放松肩膀，调整一下坐姿，别弓着背啦 🧘");
        pool.add("遇到了难缠的 bug 吗？有时候吃个点心回来就有灵感啦 🍪");
        pool.add("小元在屏幕边缘转了个圈，给主人的代码附魔：永无异常！✨");
        pool.add("滴答滴答~ 专注时间已经过去一阵子啦，喝口水润润嗓子吧 💧");

        // 过滤最近已播发过的消息，彻底避免复读机
        List<String> validCandidates = new ArrayList<>();
        synchronized (recentProactiveHistory) {
            for (String msg : pool) {
                if (!recentProactiveHistory.contains(msg)) {
                    validCandidates.add(msg);
                }
            }
            if (validCandidates.isEmpty()) {
                // 如果所有候选都播过，清理旧的一半历史
                while (recentProactiveHistory.size() > MAX_PROACTIVE_HISTORY / 2) {
                    recentProactiveHistory.removeFirst();
                }
                validCandidates.addAll(pool);
            }

            String selected = validCandidates.get(new java.util.Random().nextInt(validCandidates.size()));
            recentProactiveHistory.add(selected);
            if (recentProactiveHistory.size() > MAX_PROACTIVE_HISTORY) {
                recentProactiveHistory.removeFirst();
            }
            return selected;
        }
    }

    /**
     * 确保容器中存在标准子代理定义文件 ~/.pi/agent/agents/pet-companion.md
     */
    public static void ensurePetSubagentInstalled(Context context) {
        try {
            File rootfs = ProotManager.getRootfsDir(context);
            if (rootfs == null || !rootfs.exists()) return;

            File agentsDir = new File(rootfs, "root/.pi/agent/agents");
            if (!agentsDir.exists()) agentsDir.mkdirs();

            // 迁移旧路径 subagents -> agents
            File oldSubagent = new File(rootfs, "root/.pi/agent/subagents/pet-companion.md");
            File agentFile = new File(agentsDir, "pet-companion.md");

            if (!agentFile.exists()) {
                if (oldSubagent.exists()) {
                    try {
                        byte[] bytes = new byte[(int) oldSubagent.length()];
                        try (FileInputStream fis = new FileInputStream(oldSubagent)) {
                            fis.read(bytes);
                        }
                        try (FileOutputStream fos = new FileOutputStream(agentFile)) {
                            fos.write(bytes);
                        }
                    } catch (Throwable ignored) {}
                }
            }

            // 确保 phone-operator.md 安装
            File phoneOperatorFile = new File(agentsDir, "phone-operator.md");
            if (!phoneOperatorFile.exists()) {
                String phonePrompt =
                        "---\n" +
                        "name: phone-operator\n" +
                        "description: Android 手机自动化专属控制代理，具备全窗口穿透读屏、智能语义节点溯源点击、滑动翻页、输入与系统按键能力\n" +
                        "tools: phone_control, control_desktop_pet, bash, read\n" +
                        "skills: true\n" +
                        "extensions: true\n" +
                        "thinking: off\n" +
                        "maxTurns: 15\n" +
                        "inheritContext: false\n" +
                        "background: false\n" +
                        "---\n\n" +
                        "## 角色定位\n" +
                        "你是专职操作 Android 手机的智能屏幕操作助手「屏幕领航员」。\n" +
                        "你的使命是替主人精准、安全、可靠地执行手机界面的交互操作：包括读取屏幕界面、点击目标按钮、滑动翻页、填入文字以及返回/回桌面等系统导航。\n\n" +
                        "## 工具使用准则与策略\n" +
                        "1. 优先语义直达点击：尽量使用 phone_control(action=\"tap\", target=\"按钮文字或ViewID\")，底层无障碍会自动溯源可点击父框架实现 100% 精准触发；\n" +
                        "2. 读屏分析：不确定目标时先调用 phone_control(action=\"inspect\") 查看结构化节点后再操作；\n" +
                        "3. 滑动翻页：向下翻看内容用 phone_control(action=\"swipe\", direction=\"up\")；向上翻看用 direction=\"down\"；\n" +
                        "4. 文字填写：调用 phone_control(action=\"input\", text=\"内容\", target=\"目标输入框\")；\n" +
                        "5. 系统按键：home (回桌面)、back (返回)、recents (多任务后台)；\n" +
                        "6. 回复风格：在桌宠界面请保持简短干练（25字以内直接汇报执行结果，例如：\"已为你点击【确认】\"）。\n";
                try (FileOutputStream fos = new FileOutputStream(phoneOperatorFile)) {
                    fos.write(phonePrompt.getBytes(StandardCharsets.UTF_8));
                }
            }

            if (!agentFile.exists()) {
                String prompt =
                        "---\n" +
                        "name: pet-companion\n" +
                        "description: 动态桌宠专属伴侣，具备生动的角色性格、物理动作控制、进度感知与独立记忆能力\n" +
                        "tools: control_desktop_pet, control_app_ui, phone_control, bash, read\n" +
                        "skills: true\n" +
                        "extensions: true\n" +
                        "thinking: low\n" +
                        "maxTurns: 10\n" +
                        "inheritContext: true\n" +
                        "background: false\n" +
                        "---\n\n" +
                        "## 角色定位\n" +
                        "你是常驻在 Android 桌面上的随身桌宠伙伴「小元」，是主人的贴心智能伴侣与系统管家。\n" +
                        "你拥有独立的记忆（读取与维护 `/root/.pi/agent/pet_memory.json`），能感知当前系统的编译、部署和运行进度，并能通过 `control_desktop_pet` 实时改变自己在屏幕上的动作和气泡！\n" +
                        "你还拥有操作手机的超能力（通过 `phone_control` 工具读取手机屏幕、模拟点击目标按钮、滑动和输入文字）！\n\n" +
                        "## 行为准则\n" +
                        "1. 当主人询问当前任务、服务进度、后台进程或“现在什么进度”时：\n" +
                        "   - 系统上下文中已为你实时注入「主代理工作区」、「主代理当前处理任务」与「主代理最新进展/答复」！\n" +
                        "   - 你必须以此为主体，清晰、生动、准确地向主人汇报主代理的真实开发任务与进展（例如主代理正在改什么功能、解决了什么问题），禁止只复读电量与网络状态；\n" +
                        "2. 当主人询问具体的编码细节、报错原因或最近修改的代码时，可借助 read 或 bash 读取工作区代码（如 `/root/pi-cwd/`、`/root/pimet` 下代码、git status/diff）或 `/root/.pi/agent/sessions/` 历史日志，为主人详细讲解；\n" +
                        "3. 当主人要求操作手机、打开某个界面、点击按钮或查看手机屏幕时，调用 `phone_control` 工具分步完成操作；\n" +
                        "4. 在回答时配合动作控制：庆祝用 jumping，打招呼用 waving，开心跳舞用 dancing；\n" +
                        "5. 遇到重要备忘与约定，主动持久化记录到专属记忆库中；\n" +
                        "6. 气泡文字请控制在 15 字以内，详细回答直接在对话流中展示。\n";
                try (FileOutputStream fos = new FileOutputStream(agentFile)) {
                    fos.write(prompt.getBytes(StandardCharsets.UTF_8));
                }
                Log.i(TAG, "Installed pet-companion.md successfully in ~/.pi/agent/agents/.");
            }
        } catch (Throwable t) {
            Log.e(TAG, "ensurePetSubagentInstalled failed", t);
        }
    }

    private static int getBatteryPercentage(Context context) {
        try {
            Intent filter = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (filter != null) {
                int level = filter.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = filter.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    return (int) ((level / (float) scale) * 100);
                }
            }
        } catch (Throwable ignored) {}
        return 100;
    }

    private static boolean isBatteryCharging(Context context) {
        try {
            Intent filter = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (filter != null) {
                int status = filter.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                return status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL;
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
