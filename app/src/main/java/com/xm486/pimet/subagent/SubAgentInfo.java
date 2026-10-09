package com.xm486.pimet.subagent;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Pi / Pi-Web 标准子代理配置数据实体
 * 严格对齐 ~/.pi/agent/agents/<id>.md 规范与 Web 界面选项
 */
public class SubAgentInfo implements Serializable {
    public String id = "";                     // 文件名（无后缀，如 plan-copy）
    public String filePath = "";               // 实际存储文件绝对路径
    public String scope = "global";            // global (全局) 或 project (项目)
    public String name = "";                   // 显示名称
    public String description = "";            // 描述
    public String systemPrompt = "";           // 正文系统指令 (System Prompt)
    public List<String> tools = new ArrayList<>(); // 工具列表 (read, bash, edit, write, grep, find, ls...)
    public boolean loadSkills = true;          // 加载技能 (skills: true)
    public boolean loadExtensions = true;      // 加载扩展 (extensions: true)
    public String model = "";                  // 指定模型 (空表示跟随父会话)
    public String thinking = "";               // 思考级别 (off, minimal, low, medium, high, xhigh, max 或空)
    public int maxTurns = 10;                  // 最大轮次 (默认 10)
    public boolean inheritContext = true;      // 继承父会话上下文 (inheritContext: true)
    public boolean background = false;         // 默认在后台运行 (background: false)

    public SubAgentInfo() {}

    public SubAgentInfo copy() {
        SubAgentInfo c = new SubAgentInfo();
        c.id = this.id + "-copy";
        c.filePath = "";
        c.scope = this.scope;
        c.name = this.name + " (副本)";
        c.description = this.description;
        c.systemPrompt = this.systemPrompt;
        c.tools = new ArrayList<>(this.tools);
        c.loadSkills = this.loadSkills;
        c.loadExtensions = this.loadExtensions;
        c.model = this.model;
        c.thinking = this.thinking;
        c.maxTurns = this.maxTurns;
        c.inheritContext = this.inheritContext;
        c.background = this.background;
        return c;
    }
}
