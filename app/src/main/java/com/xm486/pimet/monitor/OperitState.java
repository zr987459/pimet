package com.xm486.pimet.monitor;

import java.util.List;

/**
 * Operit 桌宠工作状态枚举。
 * 纯 Java 模型，不依赖 Android API，便于单元测试。
 */
public enum OperitState {
    /** 未知（尚未收到任何事件） */
    UNKNOWN("未知"),
    /** 空闲：Operit 没有在处理任何请求 */
    IDLE("空闲"),
    /** 工作中：收到用户消息，开始处理 */
    WORKING("工作中"),
    /** 思考中：LLM API 请求已发出，正在生成 */
    THINKING("思考中"),
    /** 回复中：流式输出进行中 */
    RESPONDING("回复中"),
    /** 工具执行中：AI 调用了工具（shell/文件/搜索等） */
    TOOL_RUNNING("工具执行中"),
    /** 等待中：AI 等待工具结果（超时降级判定） */
    WAITING("等待中"),
    /** 出错：API 请求失败 */
    ERROR("出错了");

    private final String label;

    OperitState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    /** 桌宠 Emoji（按状态切换） */
    public String getEmoji() {
        switch (this) {
            case UNKNOWN:     return "❔";
            case IDLE:        return "😴";
            case WORKING:     return "💪";
            case THINKING:    return "🤔";
            case RESPONDING:  return "💬";
            case TOOL_RUNNING:return "🔧";
            case WAITING:     return "⏳";
            case ERROR:       return "😱";
            default:          return "❔";
        }
    }

    /** 状态快照：一次性传给 UI 展示 */
    public static class Snapshot {
        public OperitState state;
        public String agentName = "Operit";   // 当前监控的 agent（Operit / RikkaHub）
        public String model;          // 当前模型，如 deepseek-v4-flash-free
        public String provider;       // provider，如 DEEPSEEK
        public long inputTokens;      // 累计输入 token
        public long outputTokens;     // 累计输出 token
        public String lastTool;       // 最近工具调用摘要
        public long lastActiveTime;   // 最近一次活跃事件时间戳
        public boolean operitRunning; // Operit 进程是否存在
        public List<String> recentEvents; // 最近事件（用于状态卡）
        /** ClawBench 专用：当前 running 会话 id 列表（WS 订阅用） */
        public List<String> sessionIds;

        public Snapshot() {
            state = OperitState.UNKNOWN;
            model = "";
            provider = "";
            lastTool = "";
            recentEvents = new java.util.ArrayList<>();
        }

        @Override
        public String toString() {
            return "Snapshot{" + state.getLabel() +
                    ", model=" + model +
                    ", in=" + inputTokens + " out=" + outputTokens +
                    ", lastTool=" + lastTool +
                    ", operitRunning=" + operitRunning + "}";
        }
    }
}
