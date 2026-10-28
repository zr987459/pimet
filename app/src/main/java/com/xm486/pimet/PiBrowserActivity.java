package com.xm486.pimet;

import android.os.Bundle;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.xm486.pimet.ui.FloatingApps;
import com.xm486.pimet.ui.ThemeHelper;

/**
 * Pi 浏览器窗口：全屏、独立任务（最近任务里是单独一张卡片），可以分屏 / 小窗。
 * 界面和标签都在 {@link FloatingApps} 单例里，这里只负责承载：Activity 被销毁或退到后台时标签都保留，Pi 也能继续在后台操作。
 */
public class PiBrowserActivity extends AppCompatActivity {

    private FrameLayout container;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeHelper.applyTheme(this);
        super.onCreate(savedInstanceState);
        container = new FrameLayout(this);
        setContentView(container);
        FloatingApps.attachTo(this, container);
    }

    @Override
    protected void onStart() {
        super.onStart();
        FloatingApps.hostVisible(this, true);
    }

    @Override
    protected void onStop() {
        FloatingApps.hostVisible(this, false);
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        FloatingApps.detachFrom(this);
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (FloatingApps.handleBack(this)) return;
        // 像普通浏览器一样：没有可以后退的了就退到后台，标签都保留
        moveTaskToBack(true);
    }
}
