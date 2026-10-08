package com.xm486.pimet.ui;

import android.content.Context;

import com.xm486.pimet.R;
import com.xm486.pimet.monitor.OperitState;

/**
 * 状态 → 语义色 的唯一映射来源。
 *
 * 主界面预览徽章和悬浮窗状态卡都从这里取色，避免两处硬编码色值不一致；
 * 色值本身定义在 values/colors.xml 与 values-night/colors.xml，随主题自动切换。
 */
public final class StateStyle {

    private StateStyle() {}

    /** 状态对应的颜色资源 id（用于 App 内界面，随浅色/深色主题切换） */
    public static int colorRes(OperitState state) {
        switch (state) {
            case IDLE:         return R.color.state_idle;
            case WORKING:      return R.color.state_working;
            case THINKING:     return R.color.state_thinking;
            case RESPONDING:   return R.color.state_responding;
            case TOOL_RUNNING: return R.color.state_tool;
            case WAITING:      return R.color.state_waiting;
            case ERROR:        return R.color.state_error;
            case UNKNOWN:
            default:           return R.color.state_unknown;
        }
    }

    /**
     * 悬浮窗状态卡专用色。
     *
     * 状态卡背景恒为深色（叠在任意应用之上），不能复用 {@link #colorRes}：
     * 那组色值在浅色模式下是深色系，放到深色卡上对比度只有约 2.6:1，不可读。
     */
    public static int overlayColorRes(OperitState state) {
        switch (state) {
            case IDLE:         return R.color.overlay_state_idle;
            case WORKING:      return R.color.overlay_state_working;
            case THINKING:     return R.color.overlay_state_thinking;
            case RESPONDING:   return R.color.overlay_state_responding;
            case TOOL_RUNNING: return R.color.overlay_state_tool;
            case WAITING:      return R.color.overlay_state_waiting;
            case ERROR:        return R.color.overlay_state_error;
            case UNKNOWN:
            default:           return R.color.overlay_state_unknown;
        }
    }

    /** 解析出的实际颜色值（App 内界面，跟随浅色/深色主题） */
    public static int color(Context context, OperitState state) {
        return context.getResources().getColor(colorRes(state), context.getTheme());
    }

    /** 解析出的实际颜色值（悬浮窗状态卡，恒为亮色系） */
    public static int overlayColor(Context context, OperitState state) {
        return context.getResources().getColor(overlayColorRes(state), context.getTheme());
    }
}