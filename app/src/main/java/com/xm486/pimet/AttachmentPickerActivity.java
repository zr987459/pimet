package com.xm486.pimet;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 悬浮窗 AI 对话附件/相册文件选取辅助透明 Activity。
 *
 * 职责一（保留）：AI 对话附件选取，回调 {@link #sCallback}（String tag, Uri uri）。
 * 职责二（新增）：WebView 的 <input type=file> 选择，回调 {@link #sWebCallback}（Uri[]）。
 *
 * 回调契约（严格遵守）：
 *   任何退出路径（取消 / 完成 / 异常 / onDestroy）必须且只能调用一次回调；
 *   失败或取消传 null；成功传非空 Uri[]（多选时包含所有选中）。
 */
public class AttachmentPickerActivity extends AppCompatActivity {

    // ---------------------------------------------------------------- AI 附件回调

    public interface Callback {
        void onAttachmentPicked(String attachmentTag, Uri uri);
    }

    public static Callback sCallback;

    // ---------------------------------------------------------------- WebView 文件回调

    public interface WebFileChooserCallback {
        void onReceiveFiles(@Nullable Uri[] uris);
    }

    /** WebView onShowFileChooser 的回调；由 FloatingApps 设置、本 Activity 执行并清空。 */
    public static volatile WebFileChooserCallback sWebCallback;

    /** 保证回调只执行一次。 */
    private final AtomicBoolean callbackFired = new AtomicBoolean(false);

    /** 本次启动是否为 WebView 文件选择（由 FloatingApps 通过 EXTRA 标记）。 */
    public static final String EXTRA_WEB_CHOOSER = "web_file_chooser";
    public static final String EXTRA_ACCEPT_TYPES = "accept_types";
    public static final String EXTRA_ALLOW_MULTIPLE = "allow_multiple";

    private boolean isWebMode = false;

    // ---------------------------------------------------------------- 启动入口

    /** 供 FloatingApps 构造 Intent 用。 */
    public static Intent newWebChooserIntent(@Nullable String acceptTypes, boolean allowMultiple) {
        Intent i = new Intent();
        i.putExtra(EXTRA_WEB_CHOOSER, true);
        i.putExtra(EXTRA_ACCEPT_TYPES, acceptTypes);
        i.putExtra(EXTRA_ALLOW_MULTIPLE, allowMultiple);
        return i;
    }

    // ---------------------------------------------------------------- 生命周期

    private final ActivityResultLauncher<Intent> pickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                try {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        if (isWebMode) {
                            Uri[] uris = collectWebUris(data);
                            fireWebCallback(uris != null && uris.length > 0 ? uris : null);
                        } else {
                            Uri uri = data.getData();
                            if (uri != null) {
                                handlePickedUri(uri);
                            }
                        }
                    } else {
                        // 用户取消
                        if (isWebMode) fireWebCallback(null);
                    }
                } finally {
                    finish();
                }
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // WebView 选择器约定：转场无动画
        overridePendingTransition(0, 0);

        Intent src = getIntent();
        isWebMode = src != null && src.getBooleanExtra(EXTRA_WEB_CHOOSER, false);

        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        if (isWebMode) {
            String raw = src != null ? src.getStringExtra(EXTRA_ACCEPT_TYPES) : null;
            boolean allowMultiple = src != null && src.getBooleanExtra(EXTRA_ALLOW_MULTIPLE, false);
            String[] mimeTypes = normalizeMimeTypes(raw);
            if (mimeTypes.length == 1) {
                intent.setType(mimeTypes[0]);
            } else if (mimeTypes.length > 1) {
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            } else {
                intent.setType("*/*");
            }
            if (allowMultiple) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
        } else {
            // 原有 AI 附件行为不变
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES,
                    new String[]{"image/*", "text/*", "application/*"});
        }

        startPicker(intent);
    }

    private void startPicker(Intent intent) {
        try {
            pickerLauncher.launch(Intent.createChooser(intent, "选择相册图片或文件"));
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Intent.ACTION_PICK);
            fallback.setType("image/*");
            try {
                pickerLauncher.launch(fallback);
            } catch (Throwable t) {
                Toast.makeText(this, "未能启动系统相册/文件选择器", Toast.LENGTH_SHORT).show();
                if (isWebMode) fireWebCallback(null);
                finish();
            }
        } catch (Throwable t) {
            if (isWebMode) fireWebCallback(null);
            finish();
        }
    }

    // ---------------------------------------------------------------- MIME 规范化

    /**
     * 将 WebView accept_types（如 "image/*,application/pdf,text/plain"）规范化为 MIME 数组。
     */
    private static String[] normalizeMimeTypes(@Nullable String raw) {
        if (raw == null) return new String[0];
        String s = raw.trim();
        if (s.isEmpty()) return new String[0];
        Set<String> set = new LinkedHashSet<>();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            // 非 MIME 的扩展名（".jpg"）也映射，避免 WebView 传入文件扩展
            if (t.startsWith(".")) {
                String m = extToMime(t);
                if (m != null) set.add(m);
                continue;
            }
            if (t.contains("/")) set.add(t);
        }
        return set.toArray(new String[0]);
    }

    @Nullable
    private static String extToMime(String ext) {
        switch (ext.toLowerCase()) {
            case ".jpg":
            case ".jpeg": return "image/jpeg";
            case ".png": return "image/png";
            case ".gif": return "image/gif";
            case ".webp": return "image/webp";
            case ".pdf": return "application/pdf";
            case ".txt": return "text/plain";
            case ".mp4": return "video/mp4";
            case ".mp3": return "audio/mpeg";
            default: return null;
        }
    }

    // ---------------------------------------------------------------- WebView Uri 收集

    /** 从 Intent 中收集所有 Uri（多选 & 单选）。 */
    @Nullable
    private static Uri[] collectWebUris(Intent data) {
        if (data == null) return null;
        List<Uri> out = new ArrayList<>();
        // 单选
        Uri single = data.getData();
        if (single != null) out.add(single);
        // 多选
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !out.contains(u)) out.add(u);
            }
        }
        return out.isEmpty() ? null : out.toArray(new Uri[0]);
    }

    // ---------------------------------------------------------------- 回调触发（幂等）

    private void fireWebCallback(@Nullable Uri[] uris) {
        if (!isWebMode) return;
        if (!callbackFired.compareAndSet(false, true)) return;
        WebFileChooserCallback cb = sWebCallback;
        sWebCallback = null;
        if (cb != null) {
            try {
                cb.onReceiveFiles(uris);
            } catch (Throwable ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- 原有附件逻辑（不变）

    private void handlePickedUri(Uri uri) {
        String fileName = "文件";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0) {
                    fileName = cursor.getString(nameIdx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        String localPath = null;
        try {
            File cacheDir = new File(getCacheDir(), "attachments");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File dest = new File(cacheDir, System.currentTimeMillis() + "_" + fileName);
            try (InputStream in = getContentResolver().openInputStream(uri);
                 FileOutputStream out = new FileOutputStream(dest)) {
                if (in != null) {
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) != -1) {
                        out.write(buf, 0, len);
                    }
                    localPath = dest.getAbsolutePath();
                }
            }
        } catch (Throwable ignored) {}

        String tag = localPath != null ? "[附件: " + localPath + "]" : "[文件: " + fileName + "]";
        Callback cb = sCallback;
        sCallback = null;
        if (cb != null) {
            try {
                cb.onAttachmentPicked(tag, uri);
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isWebMode) {
            // Activity 被系统回收/主动结束但回调未触发：兜底取消，且只触发一次
            fireWebCallback(null);
        }
    }
}
