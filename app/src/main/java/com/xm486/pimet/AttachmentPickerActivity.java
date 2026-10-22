package com.xm486.pimet;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

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
 */
public class AttachmentPickerActivity extends AppCompatActivity {

    public interface Callback {
        void onAttachmentPicked(String attachmentTag, Uri uri);
    }

    public static Callback sCallback;

    public interface WebFileChooserCallback {
        void onReceiveFiles(@Nullable Uri[] uris);
    }

    public static volatile WebFileChooserCallback sWebCallback;

    private WebFileChooserCallback myWebCb;
    private final AtomicBoolean callbackFired = new AtomicBoolean(false);

    public static final String EXTRA_WEB_CHOOSER = "web_file_chooser";
    public static final String EXTRA_ACCEPT_TYPES = "accept_types";
    public static final String EXTRA_ALLOW_MULTIPLE = "allow_multiple";

    private boolean isWebMode = false;

    public static Intent newWebChooserIntent(@Nullable String acceptTypes, boolean allowMultiple) {
        Intent i = new Intent();
        i.putExtra(EXTRA_WEB_CHOOSER, true);
        i.putExtra(EXTRA_ACCEPT_TYPES, acceptTypes);
        i.putExtra(EXTRA_ALLOW_MULTIPLE, allowMultiple);
        return i;
    }

    private final ActivityResultLauncher<Intent> pickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    if (isWebMode) {
                        Uri[] raw = collectWebUris(data);
                        if (raw != null && raw.length > 0) {
                            // 异步后台流式拷贝，防止大文件阻塞主线程导致 ANR
                            new Thread(() -> {
                                Uri[] grantable = toGrantableUris(raw);
                                runOnUiThread(() -> {
                                    fireWebCallback(grantable);
                                    finish();
                                });
                            }, "WebPick-Transfer").start();
                            return;
                        } else {
                            fireWebCallback(null);
                        }
                    } else {
                        Uri uri = data.getData();
                        if (uri != null) {
                            handlePickedUri(uri);
                        } else {
                            sCallback = null;
                        }
                    }
                } else {
                    if (isWebMode) {
                        fireWebCallback(null);
                    } else {
                        sCallback = null;
                    }
                }
                finish();
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        overridePendingTransition(0, 0);

        Intent src = getIntent();
        isWebMode = src != null && src.getBooleanExtra(EXTRA_WEB_CHOOSER, false);

        if (isWebMode) {
            myWebCb = sWebCallback;
            sWebCallback = null;
            if (myWebCb == null) {
                finish();
                return;
            }
        }

        // 配置变化重建时，由旧实例在 onDestroy 交接给新实例，避免重复拉起选择器
        if (savedInstanceState != null) {
            return;
        }

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

    private static String[] normalizeMimeTypes(@Nullable String raw) {
        if (raw == null) return new String[0];
        String s = raw.trim();
        if (s.isEmpty()) return new String[0];
        Set<String> set = new LinkedHashSet<>();
        MimeTypeMap mtm = MimeTypeMap.getSingleton();
        for (String part : s.split(",")) {
            String t = part.trim();
            if (t.isEmpty()) continue;
            if (t.contains("/")) {
                set.add(t);
                continue;
            }
            String ext = t.startsWith(".") ? t.substring(1) : t;
            String mime = mtm.getMimeTypeFromExtension(ext.toLowerCase());
            if (mime != null && !mime.isEmpty()) {
                set.add(mime);
            } else {
                // 只要有一项无法精确识别映射，退回通用兜底，避免限制死用户选文件
                return new String[]{"*/*"};
            }
        }
        return set.toArray(new String[0]);
    }

    @Nullable
    private static Uri[] collectWebUris(Intent data) {
        if (data == null) return null;
        List<Uri> out = new ArrayList<>();
        Uri single = data.getData();
        if (single != null) out.add(single);
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) {
                Uri u = clip.getItemAt(i).getUri();
                if (u != null && !out.contains(u)) out.add(u);
            }
        }
        return out.isEmpty() ? null : out.toArray(new Uri[0]);
    }

    private void fireWebCallback(@Nullable Uri[] uris) {
        if (!isWebMode) return;
        if (!callbackFired.compareAndSet(false, true)) return;
        WebFileChooserCallback cb = myWebCb;
        myWebCb = null;
        if (cb != null) {
            try {
                cb.onReceiveFiles(uris);
            } catch (Throwable ignored) {}
        }
    }

    private void handlePickedUri(Uri uri) {
        String fileName = "文件";
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIdx >= 0) {
                    String n = cursor.getString(nameIdx);
                    if (n != null && !n.isEmpty()) fileName = n;
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        fileName = fileName.replace('/', '_').replace('\\', '_');

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
            } catch (Throwable ignored) {}
        }
    }

    @Nullable
    private Uri[] toGrantableUris(@Nullable Uri[] uris) {
        if (uris == null || uris.length == 0) return null;
        File dir = new File(getCacheDir(), "web_picks");
        if (!dir.exists()) dir.mkdirs();

        // 自动清理 24 小时前的旧文件，防磁盘无限制膨胀
        try {
            long now = System.currentTimeMillis();
            File[] oldFiles = dir.listFiles();
            if (oldFiles != null) {
                for (File f : oldFiles) {
                    if (now - f.lastModified() > 24 * 3600 * 1000L) {
                        f.delete();
                    }
                }
            }
        } catch (Throwable ignored) {}

        List<Uri> grants = new ArrayList<>(uris.length);
        for (Uri src : uris) {
            if (src == null) continue;
            String name = queryDisplayName(src);
            if (name == null || name.isEmpty()) name = "pick";
            name = name.replace('/', '_').replace('\\', '_');
            File dest = new File(dir, System.currentTimeMillis() + "_" + name);
            if (!copyToFile(src, dest)) continue;
            try {
                grants.add(FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", dest));
            } catch (Throwable ignored) {}
        }
        return grants.isEmpty() ? null : grants.toArray(new Uri[0]);
    }

    @Nullable
    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    private boolean copyToFile(Uri src, File dest) {
        try (InputStream in = getContentResolver().openInputStream(src);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) return false;
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) != -1) out.write(buf, 0, len);
            return true;
        } catch (Throwable ignored) {
            try { dest.delete(); } catch (Throwable ignored2) {}
            return false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!isWebMode) return;
        // 配置变化（分屏、深色模式、语言切换）时将回调平滑交接给重建实例
        if (isChangingConfigurations()) {
            sWebCallback = myWebCb;
            myWebCb = null;
        } else if (isFinishing()) {
            fireWebCallback(null);
        }
    }
}
