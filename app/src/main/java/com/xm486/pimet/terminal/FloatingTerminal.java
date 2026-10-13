package com.xm486.pimet.terminal;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.termux.terminal.KeyHandler;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

/**
 * 悬浮终端窗口：真 PTY 终端（Termux 渲染引擎），多会话标签、扩展键（ESC/TAB/CTRL/ALT/方向键/翻页）、
 * 双指缩放字号、复制/粘贴、拖动移动、拖角缩放、最小化。会话来自 {@link TerminalHub}，与 Pi 的 terminal 工具共享。
 */
public final class FloatingTerminal implements TerminalHub.Listener {

    private static final String TAG = "PiMet.FloatTerm";
    private static final String PREFS = "pimet_floating_terminal";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static FloatingTerminal sInstance;

    private final Context ctx;
    private final WindowManager wm;
    private final WindowManager.LayoutParams lp;
    private final LinearLayout root;
    private final LinearLayout tabBar;
    private final TerminalView termView;
    private final LinearLayout keysBox;
    private final FrameLayout termFrame;
    private TextView ctrlBtn, altBtn;
    private boolean ctrlOn = false, altOn = false;
    private boolean minimized = false, maximized = false;
    private int savedW, savedH, savedX, savedY;
    private int textSizePx;
    private TerminalHub.Entry current;

    public static boolean isShowing() {
        return sInstance != null;
    }

    /** 显示悬浮终端（任意线程）。sessionId 为空时显示最近的会话，没有会话则新建一个。 */
    public static void show(Context context, String sessionId) {
        final Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(app)) {
                Toast.makeText(app, "需要悬浮窗权限才能打开悬浮终端", Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                if (sInstance == null) sInstance = new FloatingTerminal(app);
                sInstance.open(sessionId);
            } catch (Throwable t) {
                Log.e(TAG, "show failed", t);
                Toast.makeText(app, "打开终端失败: " + t.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    public static void hide() {
        MAIN.post(() -> {
            if (sInstance != null) sInstance.dismiss();
        });
    }

    public static void toggle(Context context) {
        MAIN.post(() -> {
            if (sInstance != null) sInstance.dismiss();
            else show(context, null);
        });
    }

    // ------------------------------------------------------------------ 构建

    @SuppressLint("ClickableViewAccessibility")
    private FloatingTerminal(Context app) {
        this.ctx = app;
        this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = app.getResources().getDisplayMetrics();
        SharedPreferences sp = prefs();
        textSizePx = sp.getInt("text_px", Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11, dm)));

        root = new LinearLayout(app);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#F0141418"));
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), Color.parseColor("#3A3F4B"));
        root.setBackground(bg);
        root.setClipToOutline(true);
        root.setPadding(dp(2), dp(2), dp(2), dp(2));

        // ---- 标题栏：拖动柄 · 会话标签 · 操作按钮
        LinearLayout bar = new LinearLayout(app);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(4), 0, dp(2), 0);

        TextView grip = barBtn("⠿", "拖动");
        grip.setOnTouchListener(new DragMover());
        bar.addView(grip, new LinearLayout.LayoutParams(dp(28), dp(34)));

        HorizontalScrollView tabScroll = new HorizontalScrollView(app);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabBar = new LinearLayout(app);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabBar.setGravity(Gravity.CENTER_VERTICAL);
        tabScroll.addView(tabBar);
        bar.addView(tabScroll, new LinearLayout.LayoutParams(0, dp(34), 1f));

        addBar(bar, "+", "新建会话", v -> newSession());
        addBar(bar, "A−", "缩小字号", v -> changeFont(false));
        addBar(bar, "A+", "放大字号", v -> changeFont(true));
        addBar(bar, "⎘", "复制屏幕", v -> copyScreen());
        addBar(bar, "⤢", "最大化", v -> toggleMaximize());
        addBar(bar, "—", "最小化", v -> toggleMinimize());
        addBar(bar, "✕", "隐藏（会话后台保留）", v -> dismiss());
        root.addView(bar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)));

        // ---- 终端
        termFrame = new FrameLayout(app);
        termView = new TerminalView(app, null);
        termView.setTerminalViewClient(new ViewClient());
        termView.setTextSize(textSizePx);
        termView.setTypeface(Typeface.MONOSPACE);
        termView.setFocusable(true);
        termView.setFocusableInTouchMode(true);
        termView.setKeepScreenOn(false);
        termView.setBackgroundColor(Color.BLACK);
        termFrame.addView(termView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(termFrame, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---- 扩展键
        keysBox = new LinearLayout(app);
        keysBox.setOrientation(LinearLayout.VERTICAL);
        keysBox.addView(keyRow(new String[]{"ESC", "/", "-", "|", "HOME", "↑", "END", "PGUP", "⎀"}));
        keysBox.addView(keyRow(new String[]{"TAB", "CTRL", "ALT", "⌨", "←", "↓", "→", "PGDN", "◢"}));
        root.addView(keysBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                sp.getInt("w", Math.round(dm.widthPixels * 0.96f)),
                sp.getInt("h", Math.round(dm.heightPixels * 0.46f)),
                type,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = sp.getInt("x", Math.round(dm.widthPixels * 0.02f));
        lp.y = sp.getInt("y", Math.round(dm.heightPixels * 0.06f));
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
        clampToScreen();

        // 点到窗口外：让出焦点，下层应用可正常输入
        root.setOnTouchListener((v, ev) -> {
            if (ev.getAction() == MotionEvent.ACTION_OUTSIDE) setFocusable(false);
            return false;
        });

        wm.addView(root, lp);
        TerminalHub.addListener(this);
    }

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void savePos() {
        if (maximized || minimized) return;
        prefs().edit().putInt("x", lp.x).putInt("y", lp.y).putInt("w", lp.width).putInt("h", lp.height).apply();
    }

    private int dp(float v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.getResources().getDisplayMetrics()));
    }

    private TextView barBtn(String text, String desc) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setContentDescription(desc);
        t.setTextColor(Color.parseColor("#C9D1D9"));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void addBar(LinearLayout bar, String text, String desc, View.OnClickListener l) {
        TextView t = barBtn(text, desc);
        t.setOnClickListener(l);
        bar.addView(t, new LinearLayout.LayoutParams(dp(text.length() > 1 ? 32 : 30), dp(34)));
    }

    @SuppressLint("ClickableViewAccessibility")
    private LinearLayout keyRow(String[] keys) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String k : keys) {
            TextView t = new TextView(ctx);
            t.setText(k);
            t.setTextColor(Color.parseColor("#E6EDF3"));
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, k.length() > 3 ? 10 : 12);
            t.setGravity(Gravity.CENTER);
            t.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            GradientDrawable b = new GradientDrawable();
            b.setColor(Color.parseColor("#22272E"));
            b.setCornerRadius(dp(5));
            t.setBackground(b);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(32), 1f);
            p.setMargins(dp(1.5f), dp(1.5f), dp(1.5f), dp(1.5f));
            row.addView(t, p);
            if ("CTRL".equals(k)) ctrlBtn = t;
            if ("ALT".equals(k)) altBtn = t;
            if ("◢".equals(k)) {
                t.setOnTouchListener(new DragResizer());
                t.setContentDescription("拖动调整大小");
            } else {
                t.setOnClickListener(v -> onExtraKey(k));
                if ("⎀".equals(k)) t.setContentDescription("粘贴");
                if ("⌨".equals(k)) t.setContentDescription("显示/隐藏键盘");
                if (isRepeatable(k)) t.setOnLongClickListener(v -> {
                    startRepeat(k, v);
                    return true;
                });
            }
        }
        return row;
    }

    private static boolean isRepeatable(String k) {
        return "↑".equals(k) || "↓".equals(k) || "←".equals(k) || "→".equals(k) || "PGUP".equals(k) || "PGDN".equals(k);
    }

    private void startRepeat(String k, View v) {
        final Runnable r = new Runnable() {
            @Override
            public void run() {
                if (!v.isPressed()) return;
                onExtraKey(k);
                MAIN.postDelayed(this, 60);
            }
        };
        MAIN.post(r);
    }

    // ------------------------------------------------------------------ 会话

    private void open(String sessionId) {
        if (root.getParent() == null) {
            wm.addView(root, lp);
            TerminalHub.addListener(this);
        }
        TerminalHub.Entry e = TerminalHub.get(sessionId);
        if (e == null && TerminalHub.entries().isEmpty()) {
            newSession();
            return;
        }
        if (e == null) e = TerminalHub.entries().get(TerminalHub.entries().size() - 1);
        attach(e);
        if (minimized) toggleMinimize();
        focusTerminal(true);
    }

    private void newSession() {
        new Thread(() -> {
            try {
                TerminalHub.Entry e = TerminalHub.create(ctx, null, "/root", null, false);
                MAIN.post(() -> {
                    attach(e);
                    focusTerminal(true);
                });
            } catch (Throwable t) {
                Log.e(TAG, "create session failed", t);
                MAIN.post(() -> Toast.makeText(ctx, "启动终端失败（容器是否已安装？）: " + t.getMessage(), Toast.LENGTH_LONG).show());
            }
        }, "pimet-term-new").start();
    }

    private void attach(TerminalHub.Entry e) {
        if (e == null) return;
        current = e;
        termView.attachSession(e.session);
        termView.setTextSize(textSizePx); // 触发按窗口尺寸重排
        termView.onScreenUpdated();
        rebuildTabs();
    }

    private void rebuildTabs() {
        tabBar.removeAllViews();
        for (TerminalHub.Entry e : TerminalHub.entries()) {
            boolean active = e == current;
            LinearLayout chip = new LinearLayout(ctx);
            chip.setOrientation(LinearLayout.HORIZONTAL);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(8), 0, dp(active ? 2 : 8), 0);
            GradientDrawable b = new GradientDrawable();
            b.setColor(Color.parseColor(active ? "#2F81F7" : "#262C36"));
            b.setCornerRadius(dp(10));
            chip.setBackground(b);
            TextView name = new TextView(ctx);
            String label = e.name + (e.byAi ? " 🤖" : "") + (e.finished ? " (已退出)" : "");
            name.setText(label);
            name.setTextColor(Color.WHITE);
            name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            name.setSingleLine(true);
            chip.addView(name);
            if (active) {
                TextView x = new TextView(ctx);
                x.setText(" ×");
                x.setTextColor(Color.WHITE);
                x.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
                x.setPadding(dp(4), 0, dp(6), 0);
                x.setContentDescription("关闭此会话");
                x.setOnClickListener(v -> closeCurrent());
                chip.addView(x);
            }
            chip.setOnClickListener(v -> attach(e));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(24));
            p.setMargins(dp(2), 0, dp(2), 0);
            tabBar.addView(chip, p);
        }
    }

    private void closeCurrent() {
        TerminalHub.Entry e = current;
        if (e == null) return;
        TerminalHub.close(e);
        current = null;
        MAIN.postDelayed(() -> {
            if (TerminalHub.entries().isEmpty()) {
                dismiss();
            } else {
                attach(TerminalHub.entries().get(TerminalHub.entries().size() - 1));
            }
        }, 80);
    }

    // ------------------------------------------------------------------ 窗口行为

    private void setFocusable(boolean focusable) {
        int f = lp.flags;
        if (focusable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        if (f != lp.flags) update();
    }

    private void focusTerminal(boolean showIme) {
        setFocusable(true);
        termView.requestFocus();
        if (showIme) {
            MAIN.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(termView, InputMethodManager.SHOW_IMPLICIT);
            }, 120);
        }
    }

    private void toggleIme() {
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm == null) return;
        setFocusable(true);
        termView.requestFocus();
        imm.toggleSoftInput(InputMethodManager.SHOW_IMPLICIT, 0);
    }

    private void update() {
        try {
            if (root.getParent() != null) wm.updateViewLayout(root, lp);
        } catch (Throwable t) {
            Log.w(TAG, "update failed", t);
        }
    }

    private void clampToScreen() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        lp.width = Math.max(dp(220), Math.min(lp.width, dm.widthPixels));
        if (!minimized) lp.height = Math.max(dp(160), Math.min(lp.height, dm.heightPixels));
        lp.x = Math.max(-lp.width + dp(60), Math.min(lp.x, dm.widthPixels - dp(60)));
        lp.y = Math.max(0, Math.min(lp.y, dm.heightPixels - dp(40)));
    }

    private void toggleMinimize() {
        minimized = !minimized;
        termFrame.setVisibility(minimized ? View.GONE : View.VISIBLE);
        keysBox.setVisibility(minimized ? View.GONE : View.VISIBLE);
        if (minimized) {
            savedH = lp.height;
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            hideIme();
            setFocusable(false);
        } else {
            lp.height = savedH > 0 ? savedH : prefs().getInt("h", dp(320));
        }
        update();
    }

    private void toggleMaximize() {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        if (minimized) toggleMinimize();
        if (!maximized) {
            savedW = lp.width;
            savedH = lp.height;
            savedX = lp.x;
            savedY = lp.y;
            lp.x = 0;
            lp.y = 0;
            lp.width = dm.widthPixels;
            lp.height = dm.heightPixels;
            maximized = true;
        } else {
            maximized = false;
            lp.width = savedW;
            lp.height = savedH;
            lp.x = savedX;
            lp.y = savedY;
        }
        update();
    }

    private void hideIme() {
        InputMethodManager imm = (InputMethodManager) ctx.getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(termView.getWindowToken(), 0);
    }

    private void dismiss() {
        hideIme();
        TerminalHub.removeListener(this);
        try {
            if (root.getParent() != null) wm.removeView(root);
        } catch (Throwable ignored) {
        }
        if (sInstance == this) sInstance = null;
    }

    private void changeFont(boolean bigger) {
        DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int min = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 6, dm));
        int max = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 28, dm));
        textSizePx = Math.max(min, Math.min(max, textSizePx + (bigger ? 2 : -2)));
        termView.setTextSize(textSizePx);
        prefs().edit().putInt("text_px", textSizePx).apply();
    }

    private void copyScreen() {
        if (current == null) return;
        try {
            String t = TerminalHub.screenText(current);
            TerminalHub.copyToClipboard(t);
            Toast.makeText(ctx, "已复制当前屏幕文本", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Toast.makeText(ctx, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ 扩展键

    private void setCtrl(boolean on) {
        ctrlOn = on;
        if (ctrlBtn != null) tint(ctrlBtn, on);
    }

    private void setAlt(boolean on) {
        altOn = on;
        if (altBtn != null) tint(altBtn, on);
    }

    private void tint(TextView t, boolean on) {
        GradientDrawable b = new GradientDrawable();
        b.setColor(Color.parseColor(on ? "#2F81F7" : "#22272E"));
        b.setCornerRadius(dp(5));
        t.setBackground(b);
    }

    private void onExtraKey(String k) {
        if (current == null || termView.getCurrentSession() == null) return;
        int mod = (ctrlOn ? KeyHandler.KEYMOD_CTRL : 0) | (altOn ? KeyHandler.KEYMOD_ALT : 0);
        int code = -1;
        switch (k) {
            case "CTRL":
                setCtrl(!ctrlOn);
                return;
            case "ALT":
                setAlt(!altOn);
                return;
            case "⌨":
                toggleIme();
                return;
            case "⎀":
                TerminalHub.paste(termView.getCurrentSession());
                return;
            case "ESC": code = KeyEvent.KEYCODE_ESCAPE; break;
            case "TAB": code = KeyEvent.KEYCODE_TAB; break;
            case "HOME": code = KeyEvent.KEYCODE_MOVE_HOME; break;
            case "END": code = KeyEvent.KEYCODE_MOVE_END; break;
            case "PGUP": code = KeyEvent.KEYCODE_PAGE_UP; break;
            case "PGDN": code = KeyEvent.KEYCODE_PAGE_DOWN; break;
            case "↑": code = KeyEvent.KEYCODE_DPAD_UP; break;
            case "↓": code = KeyEvent.KEYCODE_DPAD_DOWN; break;
            case "←": code = KeyEvent.KEYCODE_DPAD_LEFT; break;
            case "→": code = KeyEvent.KEYCODE_DPAD_RIGHT; break;
            default:
                break;
        }
        if (code >= 0) {
            termView.handleKeyCode(code, mod);
        } else if (!TextUtils.isEmpty(k)) {
            termView.inputCodePoint(k.codePointAt(0), ctrlOn, altOn);
        }
        setCtrl(false);
        setAlt(false);
    }

    // ------------------------------------------------------------------ Hub 回调

    @Override
    public void onTextChanged(TerminalHub.Entry e) {
        if (e == current) termView.onScreenUpdated();
    }

    @Override
    public void onSessionsChanged() {
        MAIN.post(() -> {
            if (current != null && !TerminalHub.entries().contains(current)) {
                current = null;
                if (!TerminalHub.entries().isEmpty()) {
                    attach(TerminalHub.entries().get(TerminalHub.entries().size() - 1));
                    return;
                }
            }
            rebuildTabs();
        });
    }

    @Override
    public void onBell(TerminalHub.Entry e) {
    }

    // ------------------------------------------------------------------ 拖动 / 缩放

    private final class DragMover implements View.OnTouchListener {
        private float sx, sy;
        private int ox, oy;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    sx = ev.getRawX();
                    sy = ev.getRawY();
                    ox = lp.x;
                    oy = lp.y;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.x = ox + Math.round(ev.getRawX() - sx);
                    lp.y = oy + Math.round(ev.getRawY() - sy);
                    clampToScreen();
                    update();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (Math.abs(ev.getRawX() - sx) < dp(4) && Math.abs(ev.getRawY() - sy) < dp(4)) {
                        toggleMinimize();
                    }
                    savePos();
                    return true;
                default:
                    return false;
            }
        }
    }

    private final class DragResizer implements View.OnTouchListener {
        private float sx, sy;
        private int ow, oh;

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (maximized) maximized = false;
                    sx = ev.getRawX();
                    sy = ev.getRawY();
                    ow = lp.width;
                    oh = lp.height;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    lp.width = ow + Math.round(ev.getRawX() - sx);
                    lp.height = oh + Math.round(ev.getRawY() - sy);
                    clampToScreen();
                    update();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    savePos();
                    return true;
                default:
                    return false;
            }
        }
    }

    // ------------------------------------------------------------------ TerminalView 回调

    private final class ViewClient implements TerminalViewClient {
        @Override
        public float onScale(float scale) {
            if (scale < 0.9f || scale > 1.1f) {
                changeFont(scale > 1f);
                return 1.0f;
            }
            return scale;
        }

        @Override
        public void onSingleTapUp(MotionEvent e) {
            focusTerminal(true);
        }

        @Override
        public boolean shouldBackButtonBeMappedToEscape() {
            return false;
        }

        @Override
        public boolean shouldEnforceCharBasedInput() {
            return true;
        }

        @Override
        public boolean shouldUseCtrlSpaceWorkaround() {
            return false;
        }

        @Override
        public boolean isTerminalViewSelected() {
            return true;
        }

        @Override
        public void copyModeChanged(boolean copyMode) {
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // 返回键：收起键盘并让出焦点，不关闭终端
                hideIme();
                setFocusable(false);
                return true;
            }
            if (session != null && !session.isRunning() && keyCode == KeyEvent.KEYCODE_ENTER) {
                closeCurrent();
                return true;
            }
            return false;
        }

        @Override
        public boolean onKeyUp(int keyCode, KeyEvent e) {
            return keyCode == KeyEvent.KEYCODE_BACK;
        }

        @Override
        public boolean onLongPress(MotionEvent event) {
            // 悬浮窗里系统文本选择菜单不可用：长按直接粘贴剪贴板
            TerminalHub.paste(termView.getCurrentSession());
            Toast.makeText(ctx, "已粘贴", Toast.LENGTH_SHORT).show();
            return true;
        }

        @Override
        public boolean readControlKey() {
            return ctrlOn;
        }

        @Override
        public boolean readAltKey() {
            return altOn;
        }

        @Override
        public boolean readShiftKey() {
            return false;
        }

        @Override
        public boolean readFnKey() {
            return false;
        }

        @Override
        public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) {
            // 软键盘输入一个字符后，粘滞的 CTRL/ALT 自动松开（实际组合由 TerminalView 读取 readControlKey 完成）
            if (ctrlOn || altOn) MAIN.post(() -> {
                setCtrl(false);
                setAlt(false);
            });
            return false;
        }

        @Override
        public void onEmulatorSet() {
            termView.setTerminalCursorBlinkerRate(0);
        }

        @Override
        public void logError(String tag, String message) {
            Log.e(tag, message);
        }

        @Override
        public void logWarn(String tag, String message) {
            Log.w(tag, message);
        }

        @Override
        public void logInfo(String tag, String message) {
        }

        @Override
        public void logDebug(String tag, String message) {
        }

        @Override
        public void logVerbose(String tag, String message) {
        }

        @Override
        public void logStackTraceWithMessage(String tag, String message, Exception e) {
            Log.w(tag, message, e);
        }

        @Override
        public void logStackTrace(String tag, Exception e) {
            Log.w(tag, e);
        }
    }
}
