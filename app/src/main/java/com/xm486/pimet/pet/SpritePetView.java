package com.xm486.pimet.pet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;

import com.xm486.pimet.monitor.OperitState;

import java.io.IOException;
import java.io.InputStream;
/**
 * 精灵图帧动画桌宠（移植自 oc-claw 的 SpritePet.tsx / codexPet.ts）。
 *
 * 角色素材：assets/pets/<角色目录>/spritesheet(.webp|.png)，
 * 1536x1872，8 列 x 9 行，每格 192x208。
 * 动画行布局（与 oc-claw codexPet.ts ANIMATION_ROWS 一致）：
 *   idle       row0 6帧 2fps   （慢速呼吸）
 *   run-right  row1 8帧 8fps   （向右跑：拖动/甩动时）
 *   run-left   row2 8帧 8fps   （向左跑）
 *   waving     row3 4帧 12fps  （挥手：点击一次性）
 *   jumping    row4 5帧 6fps   （跳跃：点击一次性）
 *   failed     row5 8帧 12fps  （出错）
 *   waiting    row6 6帧 6fps   （工具/等待，循环后停 600ms）
 *   running    row7 6帧 6fps   （工作/思考）
 *   review     row8 6帧 6fps   （审查/特殊状态）
 *
 * 支持 setPetDir() 动态切换角色；setMoveDirection() 拖动方向；
 * playOneShot() 一次性动作（waving/jumping）。
 */
public class SpritePetView extends View {

    private static final String TAG = "DevPetM.SpritePet";
    private static final String PETS_BASE = "pets";

    // ---- atlas 元信息（与 oc-claw codexPet.ts 一致） ----
    private static final int CELL_W = 192;
    private static final int CELL_H = 208;
    private static final int COLS = 8;
    private static final int ROWS = 9;

    // ---- 状态 → 动画行 ----
    private static class AnimRow {
        final int row;
        final int frames;
        final float fps;
        final int loopRestMs;

        AnimRow(int row, int frames, float fps, int loopRestMs) {
            this.row = row;
            this.frames = frames;
            this.fps = fps;
            this.loopRestMs = loopRestMs;
        }
    }

    private static final AnimRow ROW_IDLE      = new AnimRow(0, 6, 2f, 0);
    private static final AnimRow ROW_RUN_RIGHT = new AnimRow(1, 8, 8f, 0);
    private static final AnimRow ROW_RUN_LEFT  = new AnimRow(2, 8, 8f, 0);
    private static final AnimRow ROW_WAVING    = new AnimRow(3, 4, 12f, 0);
    private static final AnimRow ROW_JUMPING   = new AnimRow(4, 5, 6f, 0);
    private static final AnimRow ROW_FAILED    = new AnimRow(5, 8, 12f, 0);
    private static final AnimRow ROW_WAITING   = new AnimRow(6, 6, 6f, 600);
    private static final AnimRow ROW_RUNNING   = new AnimRow(7, 6, 6f, 0);
    private static final AnimRow ROW_REVIEW    = new AnimRow(8, 6, 6f, 0);

    private Bitmap atlas;
    private AnimRow currentRow = ROW_IDLE;
    private AnimRow baseRow = ROW_IDLE;     // 状态映射的基础行（一次性动作结束后恢复）
    private AnimRow oneShotRow = null;      // 非 null = 正在播放一次性动作
    private int frameIndex = 0;
    private long restUntil = 0;
    private OperitState currentState = OperitState.UNKNOWN;

    // 渲染复用对象，消除 onDraw 每一帧的对象分配与 GC 抖动
    private final Rect srcRect = new Rect();
    private final Rect dstRect = new Rect();
    private final Paint drawPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private boolean isTickerRunning = false;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (atlas == null) return;
            long now = System.currentTimeMillis();
            if (restUntil > 0) {
                if (now < restUntil) {
                    handler.postDelayed(this, 40);
                    return;
                }
                restUntil = 0;
                frameIndex = 0;
            } else if (oneShotRow != null) {
                // 一次性动作：播完最后一帧后恢复基础行
                frameIndex++;
                if (frameIndex >= oneShotRow.frames) {
                    oneShotRow = null;
                    currentRow = baseRow;
                    frameIndex = 0;
                }
            } else {
                frameIndex = (frameIndex + 1) % currentRow.frames;
                if (frameIndex == 0 && currentRow.loopRestMs > 0) {
                    restUntil = now + currentRow.loopRestMs;
                }
            }
            invalidate();
            AnimRow row = oneShotRow != null ? oneShotRow : currentRow;
            long delay = Math.max(40, Math.round(1000f / row.fps));
            handler.postDelayed(this, delay);
        }
    };

    public SpritePetView(Context context) {
        super(context);
        init(context);
    }

    public SpritePetView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context context) {
        loadAtlas(context, PetRegistry.getPetDir(context));
        startTicker();
    }

    /** 动态切换角色（悬浮窗不重建） */
    public void setPetDir(String petDir) {
        if (atlas != null) {
            atlas.recycle();
            atlas = null;
        }
        loadAtlas(getContext(), petDir);
        invalidate();
    }

    /** 停止动画并释放贴图（弹窗等临时视图关闭时调用，避免 ticker 持有大 bitmap） */
    public void stopAnimation() {
        stopTicker();
        if (atlas != null) {
            atlas.recycle();
            atlas = null;
        }
    }

    private void loadAtlas(Context context, String petDir) {
        // 优先外部导入目录（用户从 petdex 下载导入的）
        java.io.File extDir = PetRegistry.getExternalPetsDir(context);
        java.io.File extWebp = new java.io.File(new java.io.File(extDir, petDir), "spritesheet.webp");
        java.io.File extPng = new java.io.File(new java.io.File(extDir, petDir), "spritesheet.png");
        try {
            if (extWebp.exists()) {
                atlas = BitmapFactory.decodeFile(extWebp.getAbsolutePath());
            } else if (extPng.exists()) {
                atlas = BitmapFactory.decodeFile(extPng.getAbsolutePath());
            }
            if (atlas != null) {
                Log.i(TAG, "pet loaded (external): " + petDir);
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "external load failed: " + petDir, t);
        }
        // 回退内置 assets
        String webp = PETS_BASE + "/" + petDir + "/spritesheet.webp";
        String png = PETS_BASE + "/" + petDir + "/spritesheet.png";
        try (InputStream in = tryOpen(context, webp, png)) {
            if (in == null) {
                Log.e(TAG, "no spritesheet found for pet: " + petDir);
                return;
            }
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if (bmp == null) {
                Log.e(TAG, "decode spritesheet failed: " + petDir);
                return;
            }
            atlas = bmp;
            Log.i(TAG, "pet loaded (assets): " + petDir);
        } catch (IOException e) {
            Log.e(TAG, "load spritesheet failed: " + petDir, e);
        }
    }

    /** 依次尝试打开 webp / png */
    private InputStream tryOpen(Context context, String first, String second) throws IOException {
        try {
            return context.getAssets().open(first);
        } catch (IOException e) {
            return context.getAssets().open(second);
        }
    }

    /** 根据 Operit 状态切换动画 */
    public void updateState(OperitState state) {
        if (state == currentState) return;
        currentState = state;
        AnimRow row = mapRow(state);
        if (row == baseRow) return;
        baseRow = row;
        // 拖动/一次性动作播放中不打断（结束后自动落到新状态）
        if (oneShotRow == null) {
            currentRow = row;
        }
        frameIndex = 0;
        restUntil = 0;
        invalidate();
    }

    /** 拖动方向：dir>0 向右跑、dir<0 向左跑、dir==0 恢复状态动画 */
    public void setMoveDirection(int dir) {
        if (dir > 0) {
            switchTo(ROW_RUN_RIGHT);
        } else if (dir < 0) {
            switchTo(ROW_RUN_LEFT);
        } else {
            switchTo(baseRow);
        }
    }

    /** 一次性动作（如点击挥手/跳跃），播完恢复基础行 */
    public void playOneShot(String action) {
        AnimRow row;
        if ("jumping".equals(action)) {
            row = ROW_JUMPING;
        } else if ("failed".equals(action) || "error".equals(action) || "sad".equals(action)) {
            row = ROW_FAILED;
        } else {
            row = ROW_WAVING;
        }
        oneShotRow = row;
        currentRow = row;
        frameIndex = 0;
        restUntil = 0;
        invalidate();
    }

    private void switchTo(AnimRow row) {
        if (oneShotRow != null) return; // 一次性动作期间不打断
        if (row == currentRow) return;
        currentRow = row;
        frameIndex = 0;
        restUntil = 0;
        invalidate();
    }

    private AnimRow mapRow(OperitState state) {
        switch (state) {
            case WORKING:
            case THINKING:
            case RESPONDING:
            case TOOL_RUNNING:   // 工具执行=正在干活，用工作动画
                return ROW_RUNNING;
            case WAITING:        // 等待审批/外部响应，才是等待动画
                return ROW_WAITING;
            case ERROR:
                return ROW_FAILED;
            case IDLE:
            case UNKNOWN:
            default:
                return ROW_IDLE;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (atlas == null) return;

        int col = frameIndex % COLS;
        int row = currentRow.row;
        int srcX = col * CELL_W;
        int srcY = row * CELL_H;

        // 等比缩放到 View 尺寸（保持 192:208 比例）
        int vw = getWidth();
        int vh = getHeight();
        if (vw == 0 || vh == 0) return;

        // 以宽度为基准，等比计算高度（保持角色比例居中）
        float scale = (float) vw / CELL_W;
        int dstH = Math.round(CELL_H * scale);
        if (dstH > vh) {
            scale = (float) vh / CELL_H;
            vw = Math.round(CELL_W * scale);
            dstH = vh;
        }
        int dstX = (getWidth() - vw) / 2;
        int dstY = (getHeight() - dstH) / 2;

        srcRect.set(srcX, srcY, srcX + CELL_W, srcY + CELL_H);
        dstRect.set(dstX, dstY, dstX + vw, dstY + dstH);
        canvas.drawBitmap(atlas, srcRect, dstRect, drawPaint);
    }

    private void startTicker() {
        if (!isTickerRunning && atlas != null && getVisibility() == VISIBLE) {
            isTickerRunning = true;
            handler.removeCallbacks(ticker);
            handler.post(ticker);
        }
    }

    private void stopTicker() {
        isTickerRunning = false;
        handler.removeCallbacks(ticker);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        startTicker();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopTicker();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            startTicker();
        } else {
            stopTicker();
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        if (visibility == VISIBLE) {
            startTicker();
        } else {
            stopTicker();
        }
    }
}