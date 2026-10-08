package com.xm486.pimet.pet;

import java.util.ArrayList;
import java.util.List;

/**
 * Pi-Web Agent 实时工作状态枚举与快照数据结构
 */
public enum PetAgentState {
    UNKNOWN("未知"),
    IDLE("空闲"),
    WORKING("工作中"),
    THINKING("思考中"),
    RESPONDING("回复中"),
    TOOL_RUNNING("工具执行中"),
    WAITING("等待中"),
    ERROR("出错了");

    private final String label;

    PetAgentState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

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

    /**
     * 实时监控状态快照
     */
    public static class Snapshot {
        public PetAgentState state = PetAgentState.UNKNOWN;
        public String agentName = "pi-web";
        public String model = "";
        public String provider = "";
        public long inputTokens = 0;
        public long outputTokens = 0;
        public String lastTool = "";
        public long lastActiveTime = 0;
        public boolean isAlive = false;
        public List<String> recentEvents = new ArrayList<>();

        public Snapshot() {}

        @Override
        public String toString() {
            return "Snapshot{" + state.getLabel() +
                    ", model=" + model +
                    ", lastTool=" + lastTool +
                    ", isAlive=" + isAlive + "}";
        }
    }
}
