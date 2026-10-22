package com.xm486.pimet;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 悬浮窗 AI 对话附件 / WebView 文件选取辅助透明 Activity
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
                try {
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        Intent data = result.getData();
                        if (isWebMode) {
                            Uri[] uris = parseUris(data);
                            fireWebCallback(uris);
                        } else {
                            Uri uri = data.getData();
                            if (uri != null) {
                                handleAiAttachment(uri);
                            } else {
                                sCallback = null;
                            }
                        }
                    } else {
                        if (isWebMode) fireWebCallback(null);
                        else sCallback = null;
                    }
                } finally {
                    finish();
                }
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

        if (savedInstanceState != null) return;

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
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "text/*", "application/*"});
        }

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
                else sCallback = null;
                finish();
            }
        } catch (Throwable t) {
            if (isWebMode) fireWebCallback(null);
            else sCallback = null;
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
                return new String[]{"*/*"};
            }
        }
        return set.toArray(new String[0]);
    }

    @Nullable
    private static Uri[] parseUris(Intent data) {
        if (data == null) return null;
        if (data.getClipData() != null) {
            ClipData clip = data.getClipData();
            int count = clip.getItemCount();
            if (count > 0) {
                List<Uri> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    Uri u = clip.getItemAt(i).getUri();
                    if (u != null && !list.contains(u)) list.add(u);
                }
                return list.isEmpty() ? null : list.toArray(new Uri[0]);
            }
        }
        if (data.getData() != null) {
            return new Uri[]{data.getData()};
        }
        return null;
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

    private void handleAiAttachment(Uri uri) {
        String tag = "[附件: " + uri.toString() + "]";
        Callback cb = sCallback;
        sCallback = null;
        if (cb != null) {
            try {
                cb.onAttachmentPicked(tag, uri);
            } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isWebMode) {
            if (isChangingConfigurations()) {
                sWebCallback = myWebCb;
                myWebCb = null;
            } else if (isFinishing()) {
                fireWebCallback(null);
            }
        } else {
            sCallback = null;
        }
    }
}
