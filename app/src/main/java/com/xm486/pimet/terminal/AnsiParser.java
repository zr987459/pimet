package com.xm486.pimet.terminal;

import android.graphics.Typeface;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ANSI 转义码解析器：将 Linux shell 的终端颜色、样式及 \r 回车控制码转换为 Android 富文本。
 */
public class AnsiParser {

    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[([0-9;]*)m");
    private static final Pattern ESCAPE_STRIPPER = Pattern.compile("\u001B\\[[0-9;]*[A-Za-z]|\u001B\\][^\u0007]*\u0007");

    // 默认黑底配色（参考 GitHub Dark / Termux 经典调色盘）
    public static final int COLOR_DEFAULT = 0xFFC9D1D9;
    public static final int COLOR_BLACK   = 0xFF484F58;
    public static final int COLOR_RED     = 0xFFF85149;
    public static final int COLOR_GREEN   = 0xFF7EE787;
    public static final int COLOR_YELLOW  = 0xFFE3B341;
    public static final int COLOR_BLUE    = 0xFF58A6FF;
    public static final int COLOR_MAGENTA = 0xFFBC8CFF;
    public static final int COLOR_CYAN    = 0xFF79C0FF;
    public static final int COLOR_WHITE   = 0xFFF0F6FC;
    public static final int COLOR_GRAY    = 0xFF8B949E;

    private int currentColor = COLOR_DEFAULT;
    private boolean isBold = false;

    /**
     * 将单段文本解析并追加到目标 SpannableStringBuilder
     */
    public void appendAnsiText(SpannableStringBuilder builder, String text) {
        if (text == null || text.isEmpty()) return;

        // 处理动态刷新单行的情况（例如 curl -# 输出中的 \r）
        if (text.contains("\r") && !text.contains("\r\n")) {
            int lastNewline = -1;
            for (int i = builder.length() - 1; i >= 0; i--) {
                if (builder.charAt(i) == '\n') {
                    lastNewline = i;
                    break;
                }
            }
            if (lastNewline >= 0) {
                builder.delete(lastNewline + 1, builder.length());
            } else {
                builder.clear();
            }
            text = text.replace("\r", "");
        }

        Matcher matcher = ANSI_PATTERN.matcher(text);
        int lastPos = 0;

        while (matcher.find()) {
            int start = matcher.start();
            if (start > lastPos) {
                String sub = text.substring(lastPos, start);
                // 过滤可能残留的光标控制码
                sub = ESCAPE_STRIPPER.matcher(sub).replaceAll("");
                appendSpan(builder, sub, currentColor, isBold);
            }

            String codes = matcher.group(1);
            applyCodes(codes);
            lastPos = matcher.end();
        }

        if (lastPos < text.length()) {
            String sub = text.substring(lastPos);
            sub = ESCAPE_STRIPPER.matcher(sub).replaceAll("");
            appendSpan(builder, sub, currentColor, isBold);
        }
    }

    private void appendSpan(SpannableStringBuilder builder, String text, int color, boolean bold) {
        if (text.isEmpty()) return;
        int start = builder.length();
        builder.append(text);
        int end = builder.length();

        builder.setSpan(new ForegroundColorSpan(color), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            builder.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private void applyCodes(String codes) {
        if (codes == null || codes.isEmpty() || "0".equals(codes)) {
            currentColor = COLOR_DEFAULT;
            isBold = false;
            return;
        }

        String[] parts = codes.split(";");
        for (String part : parts) {
            if (part.isEmpty()) continue;
            try {
                int code = Integer.parseInt(part);
                switch (code) {
                    case 0:
                        currentColor = COLOR_DEFAULT;
                        isBold = false;
                        break;
                    case 1:
                        isBold = true;
                        break;
                    case 21:
                    case 22:
                        isBold = false;
                        break;
                    case 30: currentColor = COLOR_BLACK; break;
                    case 31: currentColor = COLOR_RED; break;
                    case 32: currentColor = COLOR_GREEN; break;
                    case 33: currentColor = COLOR_YELLOW; break;
                    case 34: currentColor = COLOR_BLUE; break;
                    case 35: currentColor = COLOR_MAGENTA; break;
                    case 36: currentColor = COLOR_CYAN; break;
                    case 37: currentColor = COLOR_WHITE; break;
                    case 39: currentColor = COLOR_DEFAULT; break;
                    case 90: currentColor = COLOR_GRAY; break;
                    case 91: currentColor = COLOR_RED; break;
                    case 92: currentColor = COLOR_GREEN; break;
                    case 93: currentColor = COLOR_YELLOW; break;
                    case 94: currentColor = COLOR_BLUE; break;
                    case 95: currentColor = COLOR_MAGENTA; break;
                    case 96: currentColor = COLOR_CYAN; break;
                    case 97: currentColor = COLOR_WHITE; break;
                    default:
                        break;
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    public void reset() {
        currentColor = COLOR_DEFAULT;
        isBold = false;
    }
}
