package com.xm486.pimet;

import android.content.ActivityNotFoundException;
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

/**
 * 悬浮窗 AI 对话附件/相册文件选取辅助透明 Activity。
 */
public class AttachmentPickerActivity extends AppCompatActivity {

    public interface Callback {
        void onAttachmentPicked(String attachmentTag, Uri uri);
    }

    public static Callback sCallback;

    private final ActivityResultLauncher<Intent> pickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Intent data = result.getData();
                    Uri uri = data.getData();
                    if (uri != null) {
                        handlePickedUri(uri);
                    }
                }
                sCallback = null;
                finish();
            }
    );

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 打开系统文件/相册选择器
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "text/*", "application/*"});

        try {
            pickerLauncher.launch(Intent.createChooser(intent, "选择相册图片或文件"));
        } catch (ActivityNotFoundException e) {
            Intent fallback = new Intent(Intent.ACTION_PICK);
            fallback.setType("image/*");
            try {
                pickerLauncher.launch(fallback);
            } catch (Throwable t) {
                Toast.makeText(this, "未能启动系统相册/文件选择器", Toast.LENGTH_SHORT).show();
                finish();
            }
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
                    fileName = cursor.getString(nameIdx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) cursor.close();
        }

        // 保存一份副本到 app 私有缓存目录，方便获取真实路径
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
        if (sCallback != null) {
            sCallback.onAttachmentPicked(tag, uri);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
