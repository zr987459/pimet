package com.xm486.pimet.pet;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 桌宠 AI 对话桥：把状态卡上的输入框接入大模型，让桌宠能聊天。
 *
 * 两种模式（配置统一存在 SharedPreferences，由 ChatConfig 管理）：
 *   - operit     本地模式：POST 到 Operit 的 external-chat 接口，走 Operit 内置模型
 *   - custom_api 自定义 API：标准 OpenAI 兼容接口（/v1/chat/completions）
 *
 * 聊天指令（直接在输入框里发）：
 *   #test                 测试当前接口连通性（Toast 显示 HTTP 码）
 *   #operit               切换为 Operit 本地模式
 *   #api                  切换为自定义 API 模式
 *   #model <模型名>       更新自定义 API 模型
 *   #url <地址>           更新自定义 API 地址
 *   #key <密钥>           更新自定义 API Key
 *   #prompt <提示词>      更新系统提示词
 *   #group <角色分组>     切换 Operit 角色分组（#group default 切回默认）
 *
 * 会话管理：本地对话走「专属会话」，不占用你在 Operit 里正在用的对话（避免排队变慢、
 * 界面回复错乱）。输入结尾加「+」即开一个全新对话；不加则继续当前专属会话。
 */
public class PetChatBridge {

    private static final String TAG = "DevPetM.ChatBridge";

    private final PetOverlayService service;
    private final Handler handler = new Handler(Looper.getMainLooper());

    /** 正在等待 AI 回复（状态变化气泡不抢占聊天气泡） */
    private volatile boolean chatting = false;

    public boolean isChatting() {
        return chatting;
    }

    public PetChatBridge(PetOverlayService service) {
        this.service = service;
    }

    /** 快捷短语发送（chip 点击调用） */
    public void sendQuickMessage(String text) {
        if (TextUtils.isEmpty(text)) return;
        new ChatTask(text).start();
    }

    /** 挂载到状态卡：输入框获焦时解除悬浮窗 FLAG_NOT_FOCUSABLE，发送按钮提交消息 */
    public void attach() {
        updateInputHint();
        EditText input = service.getChatInput();
        if (input != null) {
            input.setOnFocusChangeListener(new View.OnFocusChangeListener() {
                @Override
                public void onFocusChange(View v, boolean hasFocus) {
                    WindowManager.LayoutParams lp =
                            (WindowManager.LayoutParams) service.getOverlayRoot().getLayoutParams();
                    if (hasFocus) {
                        // 解除「不抢焦点」标记，键盘才能弹起
                        lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                        service.updateOverlayLayout(lp);
                        InputMethodManager imm = (InputMethodManager)
                                service.getSystemService(Context.INPUT_METHOD_SERVICE);
                        if (imm != null) imm.showSoftInput(v, 0);
                    } else {
                        lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                        service.updateOverlayLayout(lp);
                    }
                }
            });
        }

        android.widget.Button send = service.getSendButton();
        if (send != null) {
            send.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    EditText et = service.getChatInput();
                    if (et == null) return;
                    String text = et.getText().toString().trim();
                    if (TextUtils.isEmpty(text)) return;
                    et.setText("");
                    new ChatTask(text).start();
                }
            });
        }

        android.widget.Button attach = service.getAttachButton();
        if (attach != null) {
            attach.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    com.xm486.pimet.AttachmentPickerActivity.sCallback = (tag, uri) -> {
                        handler.post(() -> {
                            EditText et = service.getChatInput();
                            if (et != null) {
                                String curr = et.getText().toString();
                                et.setText(curr.isEmpty() ? tag + " " : curr + " " + tag + " ");
                                et.setSelection(et.getText().length());
                            }
                        });
                    };
                    Intent pickIntent = new Intent(service, com.xm486.pimet.AttachmentPickerActivity.class);
                    pickIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    service.startActivity(pickIntent);
                }
            });
        }
    }

    public void updateInputHint() {
        handler.post(() -> {
            EditText input = service.getChatInput();
            if (input != null) {
                ChatConfig config = ChatConfig.load(service);
                input.setHint("💬 向 " + config.modeLabel() + " 发送消息…");
            }
        });
    }

    // ---------------- 指令解析 ----------------

    /** 处理 # 开头的指令；返回 true 表示已处理（不再发往大模型） */
    private boolean handleCommand(String input) {
        try {
            if ("#test".equals(input)) {
                ChatConfig config = ChatConfig.load(service);
                new Thread(new ConnectionTest(service, config)).start();
                return true;
            }
            if (switchMode(input, "#operit", ChatConfig.MODE_OPERIT, "已切换为 Operit 本地模式")) return true;
            if (switchMode(input, "#piweb", ChatConfig.MODE_PIWEB, "已切换为 pi-web 对话模式")) return true;
            if (switchMode(input, "#clawbench", ChatConfig.MODE_CLAWBENCH, "已切换为 ClawBench 对话模式")) return true;
            if (switchMode(input, "#cb", ChatConfig.MODE_CLAWBENCH, "已切换为 ClawBench 对话模式")) return true;
            if (switchMode(input, "#api", ChatConfig.MODE_CUSTOM_API, "已切换为自定义 API 模式")) return true;
            if (setCustomApiValue(input, "#model ", ChatConfig.KEY_API_MODEL, "模型已更新")) return true;
            if (setCustomApiValue(input, "#url ", ChatConfig.KEY_API_URL, "API 地址已更新")) return true;
            if (setCustomApiValue(input, "#key ", ChatConfig.KEY_API_KEY, "API Key 已更新")) return true;
            if (setCustomApiValue(input, "#prompt ", ChatConfig.KEY_API_PROMPT, "系统提示词已更新")) return true;
            if (handleGroupCommand(input)) return true;
        } catch (Throwable t) {
            Log.w(TAG, "handleCommand failed: " + input, t);
        }
        return false;
    }

    private boolean switchMode(String input, String cmd, String mode, String reply) {
        if (!input.startsWith(cmd)) return false;
        try {
            ChatConfig config = ChatConfig.load(service);
            config.mode = mode;
            config.save(service);
            String modePet = PetRegistry.getPetDirForMode(service, mode);
            if (!modePet.equals(PetRegistry.getPetDir(service))) {
                PetRegistry.setPetDir(service, modePet);
            }
            showReply(reply);
            service.onChatConfigChanged();
        } catch (Throwable t) {
            Log.w(TAG, "switchMode failed: " + input, t);
        }
        return true;
    }

    private boolean setCustomApiValue(String input, String cmd, String key, String reply) {
        if (!input.startsWith(cmd)) return false;
        try {
            String value = input.substring(cmd.length());
            ChatConfig config = ChatConfig.load(service);
            switch (key) {
                case ChatConfig.KEY_API_MODEL:  config.apiModel = value;  break;
                case ChatConfig.KEY_API_URL:    config.apiUrl = value;    break;
                case ChatConfig.KEY_API_KEY:    config.apiKey = value;    break;
                case ChatConfig.KEY_API_PROMPT: config.apiPrompt = value; break;
            }
            config.save(service);
            showReply(reply);
        } catch (Throwable t) {
            Log.w(TAG, "setCustomApiValue failed: " + input, t);
        }
        return true;
    }

    private boolean handleGroupCommand(String input) {
        if (!input.startsWith("#group ")) return false;
        try {
            String value = input.substring("#group ".length()).trim();
            ChatConfig config = ChatConfig.load(service);
            if ("default".equals(value)) {
                config.operitGroup = "";
                showReply("已切回默认角色");
            } else {
                config.operitGroup = value;
                showReply("已切换角色分组: " + value);
            }
            config.save(service);
        } catch (Throwable t) {
            Log.w(TAG, "handleGroupCommand failed: " + input, t);
        }
        return true;
    }

    /** 在气泡里显示一句提示（走打字机） */
    private void showReply(String text) {
        handler.post(new PetTypewriter(service, text));
    }

    // ---------------- 发送任务 ----------------

    private class ChatTask extends Thread {
        private final String input;

        ChatTask(String input) {
            this.input = input;
        }

        @Override
        public void run() {
            List<String> segments;
            chatting = true;
            boolean needPhase = false;
            String cleanInput = input;
            try {
                if (handleCommand(input)) {
                    segments = null; // # 指令已自行回复（showReply），不再走模型
                } else {
                    boolean forceNew = false;
                    if (cleanInput != null && cleanInput.trim().endsWith("+")) {
                        forceNew = true;
                        cleanInput = cleanInput.trim();
                        cleanInput = cleanInput.substring(0, cleanInput.length() - 1).trim();
                    }
                    if (cleanInput == null || cleanInput.isEmpty()) {
                        cleanInput = "你好";
                    }

                    // 详细状态：真正发请求才让顶栏进入阶段态（「正在处理…」），#指令不触发
                    needPhase = true;
                    final boolean fNew = forceNew;
                    handler.post(() -> {
                        com.xm486.pimet.ui.StatusCardView card = service.getStatusCard();
                        if (card != null) card.startChatPhase(fNew ? "⚡ 开启新会话中…" : "正在处理…");
                    });
                    ChatConfig config = ChatConfig.load(service);
                    // 询问具体进度时，若未配置或处于快速模式，可直接由桌宠独立记忆与进度汇报直接响应
                    if (PetMemoryManager.isProgressQuery(cleanInput)) {
                        String realProgress = PetMemoryManager.getSystemProgressReport(service);
                        if (ChatConfig.MODE_CUSTOM_API.equals(config.mode) && (config.apiKey == null || config.apiKey.isEmpty())) {
                            segments = Collections.singletonList(realProgress);
                        }
                    }
                    if (segments == null) {
                        if (ChatConfig.MODE_OPERIT.equals(config.mode)) {
                            segments = sendViaOperit(config, cleanInput, forceNew);
                        } else if (ChatConfig.MODE_PIWEB.equals(config.mode)) {
                            segments = sendViaPiWeb(config, cleanInput, forceNew);
                        } else if (ChatConfig.MODE_CLAWBENCH.equals(config.mode)) {
                            segments = sendViaClawBench(config, cleanInput, forceNew);
                        } else {
                            segments = sendViaCustomApi(config, cleanInput);
                        }
                    }
                }
            } catch (Throwable t) {
                segments = Collections.singletonList(
                        "请求异常（请检查配置或接口）: "
                        + t.getClass().getSimpleName() + " - " + t.getMessage());
                handler.post(() -> service.showFailureBubble("对话异常: " + t.getMessage()));
            } finally {
                chatting = false;
                // 结束阶段态，恢复监控驱动的状态刷新（顶栏回到 思考中/工具/回复中 等）
                if (needPhase) {
                    handler.post(() -> {
                        com.xm486.pimet.ui.StatusCardView card = service.getStatusCard();
                        if (card != null) card.endChatPhase();
                    });
                }
            }
            if (segments != null && !segments.isEmpty()) {
                StringBuilder full = new StringBuilder();
                for (String seg : segments) full.append(seg).append(" ");
                PetMemoryManager.recordInteraction(service, cleanInput, full.toString());
                playSegments(segments, 0); // 只打字已回复的正文（长文自动分段）
            }
        }
    }

    /** 分段逐条打进气泡：非末段打完立刻接下一段，末段走默认 1.5s 隐藏 */
    private void playSegments(final List<String> segs, final int idx) {
        if (segs == null || idx >= segs.size()) return;
        final String s = segs.get(idx);
        if (idx == 0 && s != null) {
            String lower = s.toLowerCase();
            if (lower.contains("跳跃") || lower.contains("好耶") || lower.contains("庆祝") || lower.contains("完成")) {
                if (service.getPetView() != null) service.getPetView().playOneShot("jumping");
            } else if (lower.contains("跳舞") || lower.contains("啦啦") || lower.contains("开心")) {
                if (service.getPetView() != null) service.getPetView().playOneShot("dancing");
            } else if (lower.contains("你好") || lower.contains("戳戳")) {
                if (service.getPetView() != null) service.getPetView().playOneShot("waving");
            }
        }
        if (idx < segs.size() - 1) {
            handler.post(new PetTypewriter(service, s, new Runnable() {
                @Override
                public void run() {
                    playSegments(segs, idx + 1);
                }
            }));
        } else {
            handler.post(new PetTypewriter(service, s));
        }
    }

    // ---------------- 网络 ----------------

    /**
 * Operit 本地模式：external-chat 接口（sync 同步阻塞返回 ExternalChatResult）。
 * 返回「气泡分段列表」：工具/思考先给一行摘要，正文按 ~60 字自然断句分段。
 */
    private List<String> sendViaOperit(ChatConfig config, String message, boolean forceNew) {
        String msg = message == null ? "" : message.trim();
        if (msg.isEmpty()) {
            return Collections.singletonList("请输入内容（结尾加 + 开新对话）");
        }

        // 专属会话 ID：Operit external-chat 的 create_new_chat 不可靠（源码里建会话结果被丢弃，
        // 失败会掉回 App 当前对话）。改为先经 Web API 可靠建好专属对话，再用显式 chat_id 发送。
        String chatId = ensureChatId(config, forceNew);
        if (chatId == null) {
            return Collections.singletonList("开新对话没成功，再试一次嘛");
        }
        config.operitChatId = chatId;

        HttpURLConnection conn = null;
        try {
            JSONObject body = buildOperitBody(config, msg);
            conn = (HttpURLConnection) new URL(config.operitUrl).openConnection();
            conn.setConnectTimeout(30000);
            conn.setReadTimeout(300000); // sync 模式：Operit 阻塞生成完整个回复才返回，长文本/多工具时可能很久
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Authorization", "Bearer " + config.operitToken);
            byte[] payload = body.toString().getBytes("UTF-8");
            conn.getOutputStream().write(payload);
            conn.getOutputStream().close();

            int code = conn.getResponseCode();
            if (code != 200) {
                String errBody = firstLine(readErrorStream(conn));
                conn.disconnect();
                return Collections.singletonList("HTTP " + code + " · " + errBody);
            }

            // sync 模式：Operit 阻塞执行完，返回 {"success":true,"chat_id":..,"ai_response":..}
            String respText = readStream(conn.getInputStream());
            conn.disconnect();
            JSONObject result = new JSONObject(respText);
            if (result.optBoolean("success", false)) {
                String ai = result.optString("ai_response", "").trim();
                if (ai.isEmpty()) {
                    return Collections.singletonList("唔...Operit 没返回内容，再试一次嘛");
                }
                return cleanWithNote(ai); // 工具/思考摘要 + 正文分段
            } else {
                String err = firstLine(result.optString("error", "").trim());
                return Collections.singletonList("Operit 出错: " + (err.isEmpty() ? "未知" : err));
            }
        } catch (Throwable t) {
            Log.w(TAG, "sendViaOperit failed", t);
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            if (t instanceof java.net.SocketTimeoutException) {
                return Collections.singletonList("AI 思考/生成太久了（超过5分钟），没等到回复");
            }
            return Collections.singletonList("请求异常: "
                    + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    /**
     * 确保有可用的专属会话 ID。
     * - forceNew（消息结尾 +）→ 经 Web API 强制新建一个专属对话
     * - 非 forceNew 且已有归属专属会话 → 直接复用
     * - 非 forceNew 但还没有 → 首次自动新建一个
     * 成功返回 chat_id（新建时已落盘），失败返回 null（此时若有可复用旧会话则回退返回它）。
     */
    private String ensureChatId(ChatConfig config, boolean forceNew) {
        boolean reuse = !forceNew && config.operitChatOwned
                && config.operitChatId != null && !config.operitChatId.trim().isEmpty();
        if (reuse) {
            return config.operitChatId.trim();
        }
        String newId = createExclusiveChat(config);
        if (newId == null) {
            // 新建失败：非强制场景下若有可复用的归属专属会话则回退，避免整个发送被阻断
            if (!forceNew && config.operitChatOwned
                    && config.operitChatId != null && !config.operitChatId.trim().isEmpty()) {
                return config.operitChatId.trim();
            }
            return null;
        }
        config.operitChatId = newId;
        config.operitChatOwned = true;
        config.save(service);
        return newId;
    }

    /**
     * 经 Operit Web API 建一个专属对话：POST /api/web/chats（setCurrent=false 不切 App 当前对话）。
     * 成功返回新对话 id，失败返回 null。
     */
    private String createExclusiveChat(ChatConfig config) {
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("setCurrent", false);
            body.put("title", "DevPetM 专属对话");
            if (!TextUtils.isEmpty(config.operitGroup)) {
                body.put("characterCardName", config.operitGroup);
            }
            String url = config.operitWebBase() + "/api/web/chats";
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Authorization", "Bearer " + config.operitToken);
            byte[] payload = body.toString().getBytes("UTF-8");
            conn.getOutputStream().write(payload);
            conn.getOutputStream().close();

            int code = conn.getResponseCode();
            if (code != 200) {
                Log.w(TAG, "createExclusiveChat HTTP " + code);
                conn.disconnect();
                return null;
            }
            String resp = readStream(conn.getInputStream());
            conn.disconnect();
            JSONObject obj = new JSONObject(resp);
            String id = obj.optString("id", "").trim();
            return id.isEmpty() ? null : id;
        } catch (Throwable t) {
            Log.w(TAG, "createExclusiveChat failed", t);
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            return null;
        }
    }

    /**
     * 构造 Operit external-chat 请求体。
     * 专属对话已由 Web API（ensureChatId）建好并记入 config.operitChatId，故发送端一律
     * create_new_chat=false + 显式 chat_id，不再依赖 external-chat 内部不稳定的 create_new_chat。
     * 「自定义规则格式」模板支持占位符 {message}/{chat_id}/{new_chat}；为空或非法 JSON 时回退默认。
     */
    private JSONObject buildOperitBody(ChatConfig config, String message)
            throws org.json.JSONException {
        String chatId = config.operitChatId == null ? "" : config.operitChatId.trim();
        String tmpl = config.operitTemplate == null ? "" : config.operitTemplate.trim();
        if (!tmpl.isEmpty()) {
            try {
                return new JSONObject(tmpl
                        .replace("{message}", message)
                        .replace("{chat_id}", chatId)
                        .replace("{new_chat}", "false"));
            } catch (Throwable ignored) {
                // 模板不是合法 JSON → 回退默认，避免 400
            }
        }
        JSONObject body = new JSONObject();
        body.put("message", message);
        body.put("response_mode", "sync");
        if (!chatId.isEmpty()) {
            body.put("chat_id", chatId);
        }
        body.put("create_new_chat", false);
        if (!TextUtils.isEmpty(config.operitGroup)) body.put("group", config.operitGroup);
        return body;
    }

    /**
     * 把回复拆成气泡分段：工具/思考先给一行摘要，正文按 ~60 字自然断句分段。
     * 默认 return_tool_status=true 时 ai_response 可能带 <tool>/<think> 等标记，
     * 这里剔除标记并把「是否调用了工具/思考」提炼成一句短摘要，正文照常分段显示。
     */
// ===== 过程标签家族正则（与 Operit 侧 ChatMarkupRegex 对齐）=====
    // Operit return_tool_status=true 时 ai_response 原样返回；工具标签名不是固定 <tool>，
    // 而是 <tool> / <tool_abc> / <tool_result> / <tool_result_abc> 家族。旧版按 7 个固定名
    // + \b 边界剥离，tool_ 带后缀的标签整体漏进正文（"无用工具内容"根因）。
    private static final java.util.regex.Pattern PAIR_TOOL_RESULT =
            java.util.regex.Pattern.compile("<([Tt]ool_result(?:_[A-Za-z0-9_]+)?)\\b[^>]*>.*?</\\1\\s*>",
                    java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern PAIR_TOOL =
            java.util.regex.Pattern.compile("<([Tt]ool(?:_(?!result)[A-Za-z0-9_]+)?)\\b[^>]*>.*?</\\1\\s*>",
                    java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern PAIR_OTHER =
            java.util.regex.Pattern.compile("<([Tt]hinking|[Tt]hink|[Ss]earch|[Ss]tatus|[Mm]ood|[Mm]eta)\\b[^>]*>.*?</\\1\\s*>",
                    java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern SELF_TOOL_RESULT =
            java.util.regex.Pattern.compile("<\\s*[Tt]ool_result(?:_[A-Za-z0-9_]+)?\\b[^>]*/>");
    private static final java.util.regex.Pattern SELF_TOOL =
            java.util.regex.Pattern.compile("<\\s*[Tt]ool(?:_(?!result)[A-Za-z0-9_]+)?\\b[^>]*/>");
    private static final java.util.regex.Pattern SELF_OTHER =
            java.util.regex.Pattern.compile("<\\s*(thinking|think|search|status|mood|meta)\\b[^>]*/>",
                    java.util.regex.Pattern.CASE_INSENSITIVE);
    /** 过程标签检测（家族级，用于摘要行判断） */
    private static final java.util.regex.Pattern HAS_TOOL =
            java.util.regex.Pattern.compile("<\\s*[Tt]ool(?:_|\\s|/|>)");
    private static final java.util.regex.Pattern HAS_THINK =
            java.util.regex.Pattern.compile("<\\s*[Tt]hink(?:ing)?(?:_|\\s|/|>)");
    private static final java.util.regex.Pattern HAS_SEARCH =
            java.util.regex.Pattern.compile("<\\s*[Ss]earch(?:_|\\s|/|>)");

    /** 保留「已回复的正文」：剥掉 工具/思考/搜索/状态 等过程标签（家族级匹配），正文按 ~60 字分段；
     *  工具/思考/检索等过程提炼成一句短摘要，排在正文后面单独一条显示（不影响正文内容）。 */
    private static List<String> cleanWithNote(String raw) {
        String src = raw == null ? "" : raw;
        String cleaned = src;
        // 顺序：先 tool_result 家族，再 tool 家族（避免 tool 误吞 result 闭合），再固定名与自闭合兜底
        for (int pass = 0; pass < 2; pass++) {
            cleaned = PAIR_TOOL_RESULT.matcher(cleaned).replaceAll("");
            cleaned = PAIR_TOOL.matcher(cleaned).replaceAll("");
            cleaned = PAIR_OTHER.matcher(cleaned).replaceAll("");
            cleaned = SELF_TOOL_RESULT.matcher(cleaned).replaceAll("");
            cleaned = SELF_TOOL.matcher(cleaned).replaceAll("");
            cleaned = SELF_OTHER.matcher(cleaned).replaceAll("");
        }
        cleaned = cleaned.replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n[ \\t]+", "\n")
                .replaceAll("\\n{3,}", "\n\n").trim();

        if (cleaned.isEmpty()) {
            return Collections.singletonList("任务完成啦！🐾\n点击气泡查看详情");
        }

        // 实时聊天内容太长时折叠成「任务完成啦！」，避免巨幅文本刷屏，用户点击气泡可直接打开网页看详情
        if (cleaned.length() > 85) {
            String shortSnippet = cleaned.length() > 32 ? cleaned.substring(0, 32) + "…" : cleaned;
            return Collections.singletonList(shortSnippet + "\n\n🎉 任务完成！点击气泡查看详情");
        }

        java.util.List<String> segs = segment(cleaned);
        String note = buildNote(src); // 基于原始 raw 判断过程，追加在正文之后
        if (note != null) segs.add(note);
        return segs;
    }

    /** 把工具/思考/检索/状态/情绪提炼成一句短摘要；无过程则返回 null */
    private static String buildNote(String raw) {
        if (raw == null) return null;
        boolean tool = HAS_TOOL.matcher(raw).find();
        boolean think = HAS_THINK.matcher(raw).find();
        boolean search = HAS_SEARCH.matcher(raw).find();
        String note = "";
        if (tool) note += "🔧调用了工具 ";
        if (think) note += "💭已思考 ";
        if (search) note += "🔎检索过 ";
        return note.length() > 0 ? note.trim() : null;
    }

    /** 按段落 + ~60 字自然断句把长文拆成气泡分段；短文本原样返回单段 */
    private static List<String> segment(String text) {
        List<String> out = new ArrayList<>();
        String t = text == null ? "" : text.trim();
        if (t.isEmpty()) {
            out.add("");
            return out;
        }
        for (String para : t.split("\n+")) {
            para = para.trim();
            if (para.isEmpty()) continue;
            if (para.length() <= 60) {
                out.add(para);
                continue;
            }
            int start = 0;
            while (start < para.length()) {
                int end = Math.min(start + 60, para.length());
                if (end < para.length()) {
                    // 尽量在标点/空格处断开，避免把词切断
                    int cut = -1;
                    for (int i = end; i > start + 20; i--) {
                        char c = para.charAt(i);
                        if (c == ' ' || c == ',' || c == '.' || c == '!' || c == '?'
                                || c == '，' || c == '。' || c == '！' || c == '？'
                                || c == '；' || c == '、' || c == '：') {
                            cut = i + 1;
                            break;
                        }
                    }
                    if (cut > start) end = cut;
                }
                out.add(para.substring(start, end).trim());
                start = end;
            }
        }
        return out;
    }

    /** 取首行（错误信息里去掉冗长堆栈，气泡里只留一句） */
    private static String firstLine(String s) {
        if (s == null) return "";
        s = s.trim();
        int nl = s.indexOf('\n');
        if (nl > 0) s = s.substring(0, nl).trim();
        if (s.length() > 80) s = s.substring(0, 80) + "…";
        return s.isEmpty() ? "（无详情）" : s;
    }

    /** 自定义 API 模式：标准 OpenAI 兼容 /v1/chat/completions，返回气泡分段列表 */
    private List<String> sendViaCustomApi(ChatConfig config, String message) {
        HttpURLConnection conn = null;
        try {
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            String sysContent = (config.apiPrompt != null ? config.apiPrompt : "")
                    + "\n\n" + PetMemoryManager.getFormattedPromptContext(service)
                    + "\n\n当前宿主系统与任务实时进度：\n" + PetMemoryManager.getSystemProgressReport(service);
            system.put("content", sysContent);
            messages.put(system);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", message);
            messages.put(user);

            JSONObject body = new JSONObject();
            body.put("model", config.apiModel);
            body.put("messages", messages);
            if (config.temperature >= 0) {
                body.put("temperature", config.temperature);
            }
            if (config.maxTokens > 0) {
                body.put("max_tokens", config.maxTokens);
            }

            conn = (HttpURLConnection) new URL(config.apiUrl).openConnection();
            int timeout = (config.timeoutSeconds > 0 ? config.timeoutSeconds : 30) * 1000;
            conn.setConnectTimeout(timeout);
            conn.setReadTimeout(timeout);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Encoding", "identity");
            conn.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            byte[] payload = body.toString().getBytes("UTF-8");
            conn.getOutputStream().write(payload);
            conn.getOutputStream().close();

            int code = conn.getResponseCode();
            if (code != 200) {
                String errBody = firstLine(readErrorStream(conn));
                conn.disconnect();
                return Collections.singletonList("HTTP " + code + " · " + errBody);
            }

            String responseText = readStream(conn.getInputStream());
            conn.disconnect();
            JSONObject response = new JSONObject(responseText);
            String content = response.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .optString("content");
            if (content.isEmpty()) {
                return Collections.singletonList("唔...没听清，再说一次嘛");
            }
            return segment(content.trim()); // 长回复自动分段逐条显示
        } catch (Throwable t) {
            Log.w(TAG, "sendViaCustomApi failed", t);
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
            return Collections.singletonList("请求异常: "
                    + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    /**
     * pi-web 对话接口：
     * 1. 尝试从 /api/sessions 找到最近修改的活跃会话；
     * 2. POST 提交 prompt（无会话时创建）；
     * 3. 订阅 SSE 事件流收集 assistant 消息直至完成。
     */
    private List<String> sendViaPiWeb(ChatConfig config, String message, boolean forceNew) {
        int port = PetRegistry.getPiWebPort(service);
        HttpURLConnection conn = null;
        try {
            String sessionId = null;
            // 1. 获取会话列表，找最近修改的有效会话（若 forceNew 则跳过直接建新会话）
            if (!forceNew) {
                try {
                    URL sUrl = new URL("http://127.0.0.1:" + port + "/api/sessions");
                    conn = (HttpURLConnection) sUrl.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(4000);
                    conn.setRequestProperty("Accept", "application/json");
                    if (conn.getResponseCode() == 200) {
                        String body = readStream(conn.getInputStream());
                        JSONObject root = new JSONObject(body);
                        JSONArray arr = root.optJSONArray("sessions");
                        if (arr != null && arr.length() > 0) {
                            long bestMod = -1;
                            for (int i = 0; i < arr.length(); i++) {
                                JSONObject s = arr.optJSONObject(i);
                                if (s == null) continue;
                                String id = s.optString("id", "");
                                if (id.isEmpty()) continue;
                                int count = s.optInt("messageCount", 0);
                                if (count == 0) continue;
                                String mod = s.optString("modified", "");
                                long t = 0;
                                try {
                                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                                            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
                                    sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                                    java.util.Date d = sdf.parse(mod);
                                    if (d != null) t = d.getTime();
                                } catch (Throwable ignored) {}
                                if (t > bestMod) {
                                    bestMod = t;
                                    sessionId = id;
                                }
                            }
                        }
                    }
                } catch (Throwable t) {
                    Log.d(TAG, "pi-web list sessions failed: " + t.getMessage());
                } finally {
                    if (conn != null) { conn.disconnect(); conn = null; }
                }
            }

            // 2. 提交消息
            if (sessionId == null) {
                URL newUrl = new URL("http://127.0.0.1:" + port + "/api/agent/new");
                conn = (HttpURLConnection) newUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject req = new JSONObject();
                req.put("cwd", "/root");
                req.put("type", "prompt");
                String piWebMsg = message;
                if (PetMemoryManager.isProgressQuery(message)) {
                    piWebMsg = message + "\n[系统上下文: " + PetMemoryManager.getSystemProgressReport(service) + "]";
                }
                req.put("message", piWebMsg);
                conn.getOutputStream().write(req.toString().getBytes("UTF-8"));
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    String err = readErrorStream(conn);
                    return Collections.singletonList("pi-web 新建会话失败 HTTP " + code + " · " + firstLine(err));
                }
                String resp = readStream(conn.getInputStream());
                JSONObject respObj = new JSONObject(resp);
                sessionId = respObj.optString("sessionId", "");
                conn.disconnect();
                conn = null;
            } else {
                URL promptUrl = new URL("http://127.0.0.1:" + port + "/api/agent/" + sessionId);
                conn = (HttpURLConnection) promptUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(4000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject req = new JSONObject();
                req.put("type", "prompt");
                req.put("message", message);
                conn.getOutputStream().write(req.toString().getBytes("UTF-8"));
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    String err = readErrorStream(conn);
                    return Collections.singletonList("pi-web 提交消息失败 HTTP " + code + " · " + firstLine(err));
                }
                conn.disconnect();
                conn = null;
            }

            if (sessionId == null || sessionId.isEmpty()) {
                return Collections.singletonList("pi-web 未能获取到会话 ID");
            }

            // 3. 订阅 SSE 收集回复
            handler.post(() -> {
                com.xm486.pimet.ui.StatusCardView card = service.getStatusCard();
                if (card != null) card.startChatPhase("⚡ pi-web 正在生成…");
            });

            URL sseUrl = new URL("http://127.0.0.1:" + port + "/api/agent/" + sessionId + "/events");
            conn = (HttpURLConnection) sseUrl.openConnection();
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(70000);

            StringBuilder textDelta = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                long startMs = System.currentTimeMillis();
                while ((line = reader.readLine()) != null) {
                    if (System.currentTimeMillis() - startMs > 75000) break;
                    if (line.startsWith("data:")) {
                        String data = line.substring(5).trim();
                        if (data.isEmpty()) continue;
                        try {
                            JSONObject evt = new JSONObject(data);
                            String type = evt.optString("type", "");
                            if ("message_update".equals(type)) {
                                JSONObject aEvt = evt.optJSONObject("assistantMessageEvent");
                                if (aEvt != null) {
                                    String aType = aEvt.optString("type", "");
                                    if ("text_delta".equals(aType)) {
                                        String delta = aEvt.optString("delta", "");
                                        textDelta.append(delta);
                                    } else if ("thinking_delta".equals(aType)) {
                                        handler.post(() -> {
                                            com.xm486.pimet.ui.StatusCardView card = service.getStatusCard();
                                            if (card != null) card.startChatPhase("💭 pi-web 深度思考中…");
                                        });
                                    }
                                }
                            } else if ("tool_execution_update".equals(type)) {
                                String toolName = evt.optString("toolName", "工具");
                                handler.post(() -> {
                                    com.xm486.pimet.ui.StatusCardView card = service.getStatusCard();
                                    if (card != null) card.startChatPhase("🛠️ pi-web 执行中: " + toolName);
                                });
                            } else if ("agent_end".equals(type) || "session_shutdown".equals(type)) {
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                }
            }

            String full = textDelta.toString().trim();
            if (full.isEmpty()) {
                return Collections.singletonList("pi-web 执行完毕。");
            }
            return cleanWithNote(full);
        } catch (Throwable t) {
            Log.w(TAG, "sendViaPiWeb failed", t);
            return Collections.singletonList("pi-web 错误: " + t.getMessage());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    /**
     * ClawBench 对话接口：
     * 1. 登录换取 clawbench_session Cookie；
     * 2. 查询 overview 拿项目路径与会话；
     * 3. POST /api/ai/queue 入队；
     * 4. 轮询会话状态与 assistant 消息直至完成。
     */
    private List<String> sendViaClawBench(ChatConfig config, String message, boolean forceNew) {
        int port = PetRegistry.getClawBenchPort(service);
        String token = PetRegistry.getClawBenchToken(service);
        HttpURLConnection conn = null;
        try {
            String cookie = null;
            if (token != null && !token.isEmpty()) {
                URL loginUrl = new URL("http://127.0.0.1:" + port + "/login");
                conn = (HttpURLConnection) loginUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject lb = new JSONObject();
                lb.put("password", token);
                conn.getOutputStream().write(lb.toString().getBytes("UTF-8"));
                if (conn.getResponseCode() == 200) {
                    List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
                    if (setCookies != null) {
                        for (String sc : setCookies) {
                            if (sc.contains("clawbench_session=")) {
                                int idx = sc.indexOf("clawbench_session=");
                                int end = sc.indexOf(";", idx);
                                cookie = end > 0 ? sc.substring(idx, end) : sc.substring(idx);
                                break;
                            }
                        }
                    }
                }
                conn.disconnect();
                conn = null;
            }

            URL overviewUrl = new URL("http://127.0.0.1:" + port + "/api/ai/sessions/overview");
            conn = (HttpURLConnection) overviewUrl.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(5000);
            if (cookie != null) conn.setRequestProperty("Cookie", cookie);
            int overCode = conn.getResponseCode();
            if (overCode != 200) {
                return Collections.singletonList("ClawBench 鉴权失败 HTTP " + overCode + " · 检查密码");
            }
            String overJson = readStream(conn.getInputStream());
            conn.disconnect();
            conn = null;

            JSONObject overObj = new JSONObject(overJson);
            JSONArray projs = overObj.optJSONArray("projects");
            String projectPath = "/root";
            String sessionId = null;
            if (projs != null && projs.length() > 0) {
                JSONObject p0 = projs.optJSONObject(0);
                if (p0 != null) {
                    projectPath = p0.optString("name", "/root");
                    if (!forceNew) {
                        JSONArray sess = p0.optJSONArray("sessions");
                        if (sess != null && sess.length() > 0) {
                            JSONObject s0 = sess.optJSONObject(0);
                            if (s0 != null) sessionId = s0.optString("id", null);
                        }
                    }
                }
            }

            String cookieHeader = (cookie != null ? cookie + "; " : "") + "clawbench_project=" + projectPath;

            // 非强制新建时：若 overview 没有运行中会话，则拉取已有最近会话列表复用，避免产生大量孤儿会话
            if (!forceNew && sessionId == null) {
                try {
                    URL allSessUrl = new URL("http://127.0.0.1:" + port + "/api/ai/sessions");
                    conn = (HttpURLConnection) allSessUrl.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(4000);
                    conn.setRequestProperty("Cookie", cookieHeader);
                    if (conn.getResponseCode() == 200) {
                        String sListJson = readStream(conn.getInputStream());
                        JSONObject sListObj = new JSONObject(sListJson);
                        JSONArray arr = sListObj.optJSONArray("sessions");
                        if (arr != null && arr.length() > 0) {
                            JSONObject firstSess = arr.optJSONObject(0);
                            if (firstSess != null) {
                                sessionId = firstSess.optString("id", null);
                            }
                        }
                    }
                    conn.disconnect();
                    conn = null;
                } catch (Throwable ignored) {}
            }

            if (sessionId == null) {
                URL sessUrl = new URL("http://127.0.0.1:" + port + "/api/ai/sessions");
                conn = (HttpURLConnection) sessUrl.openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Cookie", cookieHeader);
                conn.setRequestProperty("Content-Type", "application/json");
                JSONObject b = new JSONObject();
                b.put("project_path", projectPath);
                conn.getOutputStream().write(b.toString().getBytes("UTF-8"));
                if (conn.getResponseCode() == 200) {
                    String res = readStream(conn.getInputStream());
                    JSONObject r = new JSONObject(res);
                    sessionId = r.optString("sessionId", null);
                }
                conn.disconnect();
                conn = null;
            }

            if (sessionId == null) {
                return Collections.singletonList("ClawBench 无法获取或创建会话");
            }

            URL queueUrl = new URL("http://127.0.0.1:" + port + "/api/ai/queue?session_id=" + sessionId);
            conn = (HttpURLConnection) queueUrl.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Cookie", cookieHeader);
            conn.setRequestProperty("Content-Type", "application/json");
            JSONObject qb = new JSONObject();
            qb.put("message", message);
            qb.put("queueId", "devpetm-" + System.currentTimeMillis());
            qb.put("filePaths", new JSONArray());
            qb.put("files", new JSONArray());
            conn.getOutputStream().write(qb.toString().getBytes("UTF-8"));
            int qc = conn.getResponseCode();
            if (qc < 200 || qc >= 300) {
                String err = readErrorStream(conn);
                return Collections.singletonList("ClawBench 发送失败 HTTP " + qc + " · " + firstLine(err));
            }
            conn.disconnect();
            conn = null;

            handler.post(() -> {
                com.xm486.pimet.ui.StatusCardView card = service.getStatusCard();
                if (card != null) card.startChatPhase("⚡ ClawBench 处理中…");
            });

            int attempts = 0;
            String assistantText = "";
            while (attempts < 50) {
                attempts++;
                try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
                URL pollUrl = new URL("http://127.0.0.1:" + port + "/api/ai/chat?session_id=" + sessionId + "&limit=10");
                conn = (HttpURLConnection) pollUrl.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(4000);
                conn.setRequestProperty("Cookie", cookieHeader);
                if (conn.getResponseCode() == 200) {
                    String sBody = readStream(conn.getInputStream());
                    JSONObject sObj = new JSONObject(sBody);
                    boolean isRunning = sObj.optBoolean("running", false);
                    JSONArray msgs = sObj.optJSONArray("messages");
                    if (msgs != null && msgs.length() > 0) {
                        JSONObject lastMsg = msgs.optJSONObject(msgs.length() - 1);
                        if (lastMsg != null && "assistant".equals(lastMsg.optString("role", ""))) {
                            String rawContent = lastMsg.optString("content", "");
                            if (rawContent.startsWith("{") && rawContent.contains("\"blocks\"")) {
                                try {
                                    JSONObject cObj = new JSONObject(rawContent);
                                    JSONArray blocks = cObj.optJSONArray("blocks");
                                    if (blocks != null) {
                                        StringBuilder textSb = new StringBuilder();
                                        StringBuilder toolSb = new StringBuilder();
                                        for (int bi = 0; bi < blocks.length(); bi++) {
                                            JSONObject blk = blocks.optJSONObject(bi);
                                            if (blk == null) continue;
                                            String type = blk.optString("type", "");
                                            if ("text".equals(type)) {
                                                textSb.append(blk.optString("text", ""));
                                            } else if ("tool_use".equals(type)) {
                                                String toolName = blk.optString("name", "工具");
                                                String summary = blk.optString("summary", "");
                                                if (!summary.isEmpty()) {
                                                    toolSb.append("[").append(toolName).append(": ").append(summary).append("] ");
                                                } else {
                                                    toolSb.append("[").append(toolName).append("] ");
                                                }
                                            }
                                        }
                                        if (textSb.length() > 0) {
                                            assistantText = textSb.toString();
                                        } else if (toolSb.length() > 0) {
                                            assistantText = "⚡ 工具执行: " + toolSb.toString().trim();
                                        }
                                    }
                                } catch (Throwable ignored) {
                                    assistantText = rawContent;
                                }
                            } else if (!rawContent.isEmpty()) {
                                assistantText = rawContent;
                            }
                        }
                    }
                    if (!isRunning && !assistantText.isEmpty()) {
                        conn.disconnect();
                        conn = null;
                        break;
                    }
                }
                conn.disconnect();
                conn = null;
            }

            if (assistantText.isEmpty()) {
                return Collections.singletonList("ClawBench 执行完成。");
            }
            return cleanWithNote(assistantText);
        } catch (Throwable t) {
            Log.w(TAG, "sendViaClawBench failed", t);
            return Collections.singletonList("ClawBench 错误: " + t.getMessage());
        } finally {
            if (conn != null) {
                try { conn.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }

    private String readStream(java.io.InputStream in) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String readErrorStream(HttpURLConnection conn) {
        java.io.InputStream in = null;
        try {
            in = conn.getErrorStream();
            if (in == null) in = conn.getInputStream();
            if (in == null) return "";
            return readStream(in);
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------- 连接测试（#test + 设置页共用） ----------------

    /**
     * 连接测试：POST 一个最小请求到聊天接口本身。
     *
     * 设计要点（参考 OpenAI 兼容 API 的验证惯例）：
     *   - 直接探测聊天端点，不再猜别的路径
     *   - 404 = 地址能连上但端点不对；400/401 = 端点对但参数/鉴权有问题
     *   - 这些都说明「能对话」，比傻等 200 更有用
     */
    public static class ConnectionTest implements Runnable {
        private final Context context;
        private final ChatConfig config;
        /** 结果回调（主页内联状态行用；#test 指令路径传 null，只弹 Toast） */
        public interface VerdictListener {
            void onVerdict(String verdict, boolean reachable);
        }
        private final VerdictListener listener;

        public ConnectionTest(Context context, ChatConfig config) {
            this(context, config, null);
        }

        public ConnectionTest(Context context, ChatConfig config, VerdictListener listener) {
            this.context = context;
            this.config = config;
            this.listener = listener;
        }

        @Override
        public void run() {
            String verdict;
            try {
                if (ChatConfig.MODE_PIWEB.equals(config.mode)) {
                    int p = PetRegistry.getPiWebPort(context);
                    HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + p + "/api/sessions").openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if (code == 200) {
                        verdict = "✅ pi-web 连接正常（端口 " + p + "）";
                    } else {
                        verdict = "⚠️ pi-web 返回 HTTP " + code;
                    }
                    if (listener == null) toast(verdict);
                    if (listener != null) listener.onVerdict(verdict, code == 200);
                    return;
                }
                if (ChatConfig.MODE_CLAWBENCH.equals(config.mode)) {
                    int p = PetRegistry.getClawBenchPort(context);
                    String token = PetRegistry.getClawBenchToken(context);
                    String cookie = null;
                    if (token != null && !token.isEmpty()) {
                        try {
                            HttpURLConnection lConn = (HttpURLConnection) new URL("http://127.0.0.1:" + p + "/login").openConnection();
                            lConn.setRequestMethod("POST");
                            lConn.setConnectTimeout(3000);
                            lConn.setReadTimeout(3000);
                            lConn.setDoOutput(true);
                            lConn.setRequestProperty("Content-Type", "application/json");
                            JSONObject lb = new JSONObject();
                            lb.put("password", token);
                            lConn.getOutputStream().write(lb.toString().getBytes("UTF-8"));
                            if (lConn.getResponseCode() == 200) {
                                List<String> setCookies = lConn.getHeaderFields().get("Set-Cookie");
                                if (setCookies != null) {
                                    for (String sc : setCookies) {
                                        if (sc.contains("clawbench_session=")) {
                                            int idx = sc.indexOf("clawbench_session=");
                                            int end = sc.indexOf(";", idx);
                                            cookie = end > 0 ? sc.substring(idx, end) : sc.substring(idx);
                                            break;
                                        }
                                    }
                                }
                            }
                            lConn.disconnect();
                        } catch (Throwable ignored) {}
                    }
                    HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + p + "/api/ai/sessions/overview").openConnection();
                    conn.setConnectTimeout(4000);
                    conn.setReadTimeout(4000);
                    if (cookie != null) conn.setRequestProperty("Cookie", cookie);
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    boolean reachable = (code == 200);
                    if (code == 200) {
                        verdict = "✅ ClawBench 认证及连接正常（端口 " + p + "）";
                    } else if (code == 401 || code == 403) {
                        verdict = "⚠️ ClawBench 在线但鉴权失败（HTTP " + code + " · 检查密码）";
                    } else {
                        verdict = "⚠️ ClawBench 返回 HTTP " + code;
                    }
                    if (listener == null) toast(verdict);
                    if (listener != null) listener.onVerdict(verdict, reachable);
                    return;
                }

                boolean isOperit = ChatConfig.MODE_OPERIT.equals(config.mode);
                String url = isOperit ? config.operitUrl : config.apiUrl;
                String auth = isOperit ? config.operitToken : config.apiKey;

                // 构造最小聊天请求
                JSONObject body = new JSONObject();
                if (isOperit) {
                    JSONObject testBody;
                    String tmpl = config.operitTemplate == null ? "" : config.operitTemplate.trim();
                    if (!tmpl.isEmpty()) {
                        try {
                            testBody = new JSONObject(tmpl.replace("{message}", "hi"));
                        } catch (Throwable ignored) {
                            testBody = new JSONObject();
                        }
                    } else {
                        testBody = new JSONObject();
                    }
                    if (testBody.length() == 0) {
                        testBody.put("message", "hi");
                        testBody.put("create_new_chat", false);
                        testBody.put("response_mode", "sync");
                    }
                    body = testBody;
                } else {
                    JSONArray messages = new JSONArray();
                    JSONObject system = new JSONObject();
                    system.put("role", "system");
                    system.put("content", "ping");
                    messages.put(system);
                    JSONObject user = new JSONObject();
                    user.put("role", "user");
                    user.put("content", "ping");
                    messages.put(user);
                    body.put("model", config.apiModel);
                    body.put("messages", messages);
                }

                HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Accept-Encoding", "identity");
                if (!TextUtils.isEmpty(auth)) {
                    conn.setRequestProperty("Authorization", "Bearer " + auth);
                }
                byte[] payload = body.toString().getBytes("UTF-8");
                conn.getOutputStream().write(payload);
                conn.getOutputStream().close();

                int code = conn.getResponseCode();
                String bodyText = readAnyStream(conn);
                conn.disconnect();
                verdict = judge(code, bodyText, isOperit);
            } catch (Throwable t) {
                Log.w("DevPetM.ChatBridge", "ConnectionTest failed", t);
                verdict = "❌ 连接失败：" + t.getClass().getSimpleName() + " - " + t.getMessage();
            }
            // 有回调（主页内联）时由调用方展示结果，避免 Toast + 状态行双重提示；#test 指令路径仍走 Toast
            if (listener == null) toast(verdict);
            if (listener != null) {
                // 2xx/4xx（能连上）都算 reachable；❌ 网络失败才算不可达
                listener.onVerdict(verdict, verdict.startsWith("✅") || verdict.startsWith("⚠️"));
            }
        }

        /** 把 HTTP 码翻译成人话 */
        private String judge(int code, String bodyText, boolean isOperit) {
            if (code == 200) return "✅ 连接成功 · " + config.modeLabel();
            if (code == 401 || code == 403)
                return "⚠️ 地址通，但鉴权失败（HTTP " + code + "）· 检查 Token/Key";
            if (code == 400) {
                // OpenAI 兼容接口对 ping 请求返回 400 多半是模型名问题
                if (bodyText != null && bodyText.contains("model"))
                    return "⚠️ 连接通，但模型名可能不对（HTTP 400）· " + config.apiModel;
                return "⚠️ 连接通，请求格式有问题（HTTP 400）";
            }
            if (code == 404) {
                if (isOperit)
                    return "⚠️ Operit 服务在线，但 external-chat 路径不对（HTTP 404）· 检查地址";
                return "⚠️ API 地址能连上，但 /chat/completions 路径不对（HTTP 404）· 检查地址";
            }
            return "⚠️ HTTP " + code + " · " + config.modeLabel();
        }

        private String readAnyStream(HttpURLConnection conn) {
            java.io.InputStream in = null;
            try {
                in = conn.getErrorStream();
                if (in == null) in = conn.getInputStream();
                if (in == null) return "";
                BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                return sb.toString();
            } catch (Throwable t) {
                return "";
            }
        }

        private void toast(final String msg) {
            new Handler(Looper.getMainLooper()).post(() ->
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show());
        }
    }
}