package com.xm486.pimet.pet;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 桌宠角色注册表：枚举 assets/pets/ + 外部导入目录下的角色并读取 pet.json 元信息。
 *
 * 每个角色目录包含：
 *   - pet.json        { id, displayName, description, spritesheetPath }
 *   - spritesheet    （.webp 或 .png，1536x1872，8列x9行精灵图）
 */
public class PetRegistry {

    private static final String TAG = "PiMet.PetRegistry";
    public static final String PETS_DIR = "pets";
    public static final String PREF_NAME = "pimet_config";
    public static final String KEY_PET_DIR = "pet_dir";
    public static final String KEY_PET_ENABLED = "pet_enabled";
    public static final String DEFAULT_PET_DIR = "doro.codex-pet";

    public static class PetInfo {
        public String dir;          // 角色目录名，如 doro.codex-pet
        public String id;           // pet.json id，如 doro
        public String displayName;  // 显示名，如 Doro
        public String description;  // 描述
        public String spriteFile;   // 精灵图文件名，如 spritesheet.webp
        public boolean external;    // true=用户导入（外部目录），false=内置（assets）
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /** 外部（用户导入）角色根目录：<外部私有目录>/pets */
    public static File getExternalPetsDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), PETS_DIR);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** 枚举全部角色：内置（assets）+ 外部导入，按目录名排序 */
    public static List<PetInfo> loadPets(Context context) {
        List<PetInfo> result = new ArrayList<>();
        // 内置 assets
        try {
            String[] dirs = context.getAssets().list(PETS_DIR);
            if (dirs != null) {
                Arrays.sort(dirs);
                for (String dir : dirs) {
                    PetInfo info = readPetInfo(context, dir);
                    if (info != null) result.add(info);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "loadPets(assets) failed", t);
        }

        // 外部导入
        File extDir = getExternalPetsDir(context);
        File[] files = extDir.listFiles();
        if (files != null) {
            Arrays.sort(files);
            for (File f : files) {
                if (f.isDirectory()) {
                    PetInfo info = readPetInfoExternal(f);
                    if (info != null) result.add(info);
                }
            }
        }

        // 若均未读取成功，添加默认占位
        if (result.isEmpty()) {
            PetInfo def = new PetInfo();
            def.dir = DEFAULT_PET_DIR;
            def.id = "doro";
            def.displayName = "Doro";
            def.description = "PiMet 可爱桌宠伙伴";
            def.spriteFile = "spritesheet.webp";
            def.external = false;
            result.add(def);
        }
        return result;
    }

    /** 读取 assets/<PETS_DIR>/<dir>/pet.json 元信息 */
    private static PetInfo readPetInfo(Context context, String dir) {
        try {
            String path = PETS_DIR + "/" + dir + "/pet.json";
            String json;
            try (InputStream in = context.getAssets().open(path)) {
                byte[] buf = new byte[in.available()];
                int read = in.read(buf);
                if (read <= 0) return null;
                json = new String(buf, "UTF-8");
            }
            JSONObject obj = new JSONObject(json);
            PetInfo info = new PetInfo();
            info.dir = dir;
            info.id = obj.optString("id", dir);
            info.displayName = obj.optString("displayName", dir);
            info.description = obj.optString("description", "");
            info.spriteFile = obj.optString("spritesheetPath", "spritesheet.webp");
            info.external = false;
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "readPetInfo failed: " + dir, t);
            return null;
        }
    }

    /** 读取外部（用户导入）角色元信息 */
    private static PetInfo readPetInfoExternal(File dir) {
        try {
            File petJson = new File(dir, "pet.json");
            String json;
            if (petJson.exists()) {
                json = new String(java.nio.file.Files.readAllBytes(petJson.toPath()), "UTF-8");
            } else {
                json = "{\"displayName\":\"" + dir.getName() + "\"}";
            }
            JSONObject obj = new JSONObject(json);
            PetInfo info = new PetInfo();
            info.dir = dir.getName();
            info.id = obj.optString("id", dir.getName());
            info.displayName = obj.optString("displayName", dir.getName());
            info.description = obj.optString("description", "");
            String sf = obj.optString("spritesheetPath", "spritesheet.webp");
            if (!new File(dir, sf).exists()) {
                sf = new File(dir, "spritesheet.webp").exists() ? "spritesheet.webp" : "spritesheet.png";
            }
            info.spriteFile = sf;
            info.external = true;
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "readPetInfoExternal failed: " + dir.getName(), t);
            return null;
        }
    }

    /** 导入角色：把用户选的文件夹（含 spritesheet + pet.json）复制到外部角色目录 */
    public static boolean importPet(Context context, Uri treeUri) {
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
            if (root == null || !root.isDirectory()) {
                Log.e(TAG, "importPet: invalid tree uri");
                return false;
            }
            String name = root.getName();
            if (name == null || name.isEmpty()) name = "pet_" + System.currentTimeMillis();
            name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");

            File target = new File(getExternalPetsDir(context), name);
            if (target.exists()) {
                target = new File(getExternalPetsDir(context), name + "_" + System.currentTimeMillis());
            }
            if (!target.mkdirs()) {
                return false;
            }

            DocumentFile[] children = root.listFiles();
            boolean copiedSprite = false;
            for (DocumentFile child : children) {
                if (child.isDirectory()) continue;
                String fileName = child.getName();
                if (fileName == null) continue;
                if (!fileName.equals("pet.json")
                        && !fileName.endsWith(".webp")
                        && !fileName.endsWith(".png")
                        && !fileName.endsWith(".jpg")) {
                    continue;
                }
                File dest = new File(target, fileName);
                try (InputStream in = context.getContentResolver().openInputStream(child.getUri());
                     OutputStream out = new FileOutputStream(dest)) {
                    if (in == null) continue;
                    byte[] buf = new byte[8192];
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        out.write(buf, 0, len);
                    }
                    if (fileName.contains("spritesheet")) copiedSprite = true;
                }
            }
            return copiedSprite;
        } catch (Throwable t) {
            Log.e(TAG, "importPet failed", t);
            return false;
        }
    }

    /** 读取当前选中的角色目录名（默认 doro.codex-pet） */
    public static String getPetDir(Context context) {
        return getPrefs(context).getString(KEY_PET_DIR, DEFAULT_PET_DIR);
    }

    /** 写入选中的角色目录名 */
    public static void setPetDir(Context context, String dir) {
        getPrefs(context).edit().putString(KEY_PET_DIR, dir).apply();
    }

    /** 是否启用桌宠（默认 true） */
    public static boolean isPetEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_PET_ENABLED, true);
    }

    /** 切换桌宠启用状态 */
    public static void setPetEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_PET_ENABLED, enabled).apply();
    }
}
