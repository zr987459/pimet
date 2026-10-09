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
 *
 * 内置角色在 assets/pets/（只读）；用户导入的角色在
 * getExternalFilesDir()/pets/（可写，支持从 petdex.dev 下载后导入）。
 */
public class PetRegistry {

    private static final String TAG = "DevPetM.PetRegistry";
    private static final String PETS_DIR = "pets";
    private static final String PREF_NAME = "pimet_config";
    private static final String KEY_PET_DIR = "pet_dir";
    public static final String DEFAULT_PET_DIR = "doro.codex-pet";
    private static final String KEY_DELETED_BUILTIN = "deleted_builtin_pets";

    public static class PetInfo {
        public String dir;          // 角色目录名，如 doro.codex-pet
        public String id;           // pet.json id，如 doro
        public String displayName;  // 显示名，如 Doro
        public String description;  // 描述
        public String spriteFile;   // 精灵图文件名，如 spritesheet.webp
        public boolean external;    // true=用户导入（外部目录），false=内置（assets）
    }

    /** 外部（用户导入）角色根目录：<外部私有目录>/pets */
    public static File getExternalPetsDir(Context context) {
        File dir = new File(context.getExternalFilesDir(null), PETS_DIR);
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    /** 枚举全部角色：内置（assets，排除用户删除的）+ 外部导入，按目录名排序 */
    public static List<PetInfo> loadPets(Context context) {
        List<PetInfo> result = new ArrayList<>();
        java.util.Set<String> deletedBuiltins = getDeletedBuiltinPets(context);
        // 内置
        try {
            String[] dirs = context.getAssets().list(PETS_DIR);
            if (dirs != null) {
                Arrays.sort(dirs);
                for (String dir : dirs) {
                    if (deletedBuiltins.contains(dir)) continue;
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
                    PetInfo info = readPetInfoExternal(context, f);
                    if (info != null) result.add(info);
                }
            }
        }
        return result;
    }

    /** 获取用户删除的内置角色集合 */
    public static java.util.Set<String> getDeletedBuiltinPets(Context context) {
        return getPrefs(context).getStringSet(KEY_DELETED_BUILTIN, new java.util.HashSet<>());
    }

    /** 删除角色：外部角色彻底物理删除目录；内置角色记入已删除列表（均可被用户删除） */
    public static boolean deletePet(Context context, PetInfo info) {
        if (info == null || info.dir == null) return false;
        if (info.external) {
            File dir = new File(getExternalPetsDir(context), info.dir);
            return deleteRecursively(dir);
        } else {
            java.util.Set<String> deleted = new java.util.HashSet<>(getDeletedBuiltinPets(context));
            deleted.add(info.dir);
            getPrefs(context).edit().putStringSet(KEY_DELETED_BUILTIN, deleted).apply();
            return true;
        }
    }

    /** 恢复所有被删除的内置角色 */
    public static void restoreBuiltinPets(Context context) {
        getPrefs(context).edit().remove(KEY_DELETED_BUILTIN).apply();
    }

    private static boolean deleteRecursively(File fileOrDirectory) {
        if (fileOrDirectory != null && fileOrDirectory.exists()) {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        deleteRecursively(child);
                    }
                }
            }
            return fileOrDirectory.delete();
        }
        return false;
    }

    /** 读取内置角色元信息；失败返回 null */
    private static PetInfo readPetInfo(Context context, String dir) {
        try (InputStream in = context.getAssets().open(PETS_DIR + "/" + dir + "/pet.json")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            String json = new String(buf, 0, n > 0 ? n : 0, "UTF-8");
            JSONObject obj = new JSONObject(json);
            PetInfo info = new PetInfo();
            info.dir = dir;
            info.id = obj.optString("id", dir);
            info.displayName = obj.optString("displayName", dir);
            info.description = obj.optString("description", "");
            info.spriteFile = obj.optString("spritesheetPath", "spritesheet.webp");
            info.external = false;
            // 兼容：找不到声明的文件时回退
            if (!assetExists(context, PETS_DIR + "/" + dir + "/" + info.spriteFile)) {
                info.spriteFile = assetExists(context, PETS_DIR + "/" + dir + "/spritesheet.webp")
                        ? "spritesheet.webp" : "spritesheet.png";
            }
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "readPetInfo failed: " + dir, t);
            return null;
        }
    }

    /** 读取外部（用户导入）角色元信息；失败返回 null */
    private static PetInfo readPetInfoExternal(Context context, File dir) {
        try {
            File petJson = new File(dir, "pet.json");
            String json;
            if (petJson.exists()) {
                json = new String(java.nio.file.Files.readAllBytes(petJson.toPath()), "UTF-8");
            } else {
                // 无 pet.json：默认用目录名
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
            // 清理非法字符
            name = name.replaceAll("[^a-zA-Z0-9._\\-]", "_");

            File target = new File(getExternalPetsDir(context), name);
            // 已存在则加时间戳避免覆盖
            if (target.exists()) {
                target = new File(getExternalPetsDir(context),
                        name + "_" + System.currentTimeMillis());
            }
            if (!target.mkdirs()) {
                Log.e(TAG, "importPet: mkdir failed " + target);
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
                if (copyDocumentToFile(context, child, new File(target, fileName))) {
                    if (fileName.endsWith(".webp") || fileName.endsWith(".png")) {
                        copiedSprite = true;
                    }
                }
            }
            if (!copiedSprite) {
                Log.e(TAG, "importPet: no spritesheet found");
                // 删除空目录
                deleteRecursive(target);
                return false;
            }
            // 没有 pet.json 就生成一个默认的
            if (!new File(target, "pet.json").exists()) {
                writeDefaultPetJson(target, name);
            }
            Log.i(TAG, "imported pet -> " + target.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "importPet failed", t);
            return false;
        }
    }

    private static boolean copyDocumentToFile(Context context, DocumentFile src, File dst) {
        try (InputStream in = context.getContentResolver().openInputStream(src.getUri());
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) return false;
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "copy failed: " + dst.getName(), t);
            return false;
        }
    }

    private static void writeDefaultPetJson(File dir, String name) {
        try (FileOutputStream out = new FileOutputStream(new File(dir, "pet.json"))) {
            String json = "{\"id\":\"" + name + "\",\"displayName\":\"" + name
                    + "\",\"description\":\"导入角色\",\"spritesheetPath\":\"spritesheet.webp\"}";
            out.write(json.getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "writeDefaultPetJson failed", t);
        }
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] fs = f.listFiles();
            if (fs != null) for (File c : fs) deleteRecursive(c);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean assetExists(Context context, String path) {
        try {
            context.getAssets().open(path).close();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 当前选中角色持久化 ----------------

    public static final String KEY_PET_DIR_PREFIX = "pet_dir_mode_";

    public static String getPetDir(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return sp.getString(KEY_PET_DIR, DEFAULT_PET_DIR);
    }

    /** 获取指定 AI 模式绑定的专属角色形象；若未绑定则返回全局角色 */
    public static String getPetDirForMode(Context context, String mode) {
        if (mode == null || mode.isEmpty()) return getPetDir(context);
        SharedPreferences sp = getPrefs(context);
        String dir = sp.getString(KEY_PET_DIR_PREFIX + mode, null);
        if (dir != null && !dir.isEmpty()) {
            return dir;
        }
        return getPetDir(context);
    }

    /** 为指定 AI 模式绑定专属角色形象 */
    public static void setPetDirForMode(Context context, String mode, String dir) {
        if (mode != null && !mode.isEmpty() && dir != null && !dir.isEmpty()) {
            getPrefs(context).edit().putString(KEY_PET_DIR_PREFIX + mode, dir).apply();
            Log.i(TAG, "bound pet for mode [" + mode + "] to: " + dir);
        }
    }

    public static void setPetDir(Context context, String dir) {
        SharedPreferences sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        sp.edit().putString(KEY_PET_DIR, dir).apply();
        Log.i(TAG, "pet switched to: " + dir);
    }

    public static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    // ---------------- 拖动物理参数（可配置） ----------------

    public static final String KEY_FLING_THRESHOLD = "physics_fling_threshold"; // 触发飞行的最小速度 px/s
    public static final String KEY_BOUNCE = "physics_bounce";                   // 边界反弹能量保留 %（0~100）
    public static final String KEY_FRICTION = "physics_friction";               // 空气摩擦衰减 %（0~100，每帧）
    public static final String KEY_STOP_SPEED = "physics_stop_speed";           // 停稳速度阈值 px/s
    public static final String KEY_FLING_DURATION = "physics_fling_duration";   // 最长飞行时长 ms
    public static final String KEY_PET_SIZE = "pet_size";                       // 宠物大小（宽度 dp）
    public static final String KEY_BUBBLE_WIDTH = "bubble_width";             // 气泡最大宽度 dp
    public static final String KEY_CARD_WIDTH = "card_width";                   // 状态卡宽度 dp
    public static final String KEY_CARD_SCALE = "card_scale";                   // 状态卡整体缩放 60-100%
    public static final String KEY_MENU_WIDTH = "menu_width";                   // 长按菜单宽度 dp
    public static final String KEY_RK_PORT = "rikkahub_port";                   // RikkaHub Web API 端口
    public static final String KEY_CB_PORT = "clawbench_port";                  // ClawBench Web 服务端口（/api/ai/sessions/overview）
    public static final String KEY_CB_TOKEN = "clawbench_token";                // ClawBench 登录密码（cookie clawbench_session）
    public static final String KEY_CLAWBENCH_PORT = KEY_CB_PORT;
    public static final String KEY_CLAWBENCH_TOKEN = KEY_CB_TOKEN;
    public static final String KEY_OPERIT_PORT = "operit_port";                 // Operit Web API 端口
    public static final String KEY_PIWEB_PORT = "piweb_port";                  // pi-web 内嵌工作台端口
    public static final String KEY_MONITOR_TARGET = "monitor_target";           // 监控目标（单选）
    public static final String KEY_POS_X = "overlay_pos_x";                   // 悬浮窗位置 X（px）
    public static final String KEY_POS_Y = "overlay_pos_y";                   // 悬浮窗位置 Y（px）
    public static final String TARGET_OPERIT = "operit";                        // 默认：只监控 Operit
    public static final String TARGET_RIKKA = "rikkahub";                       // 监控 RikkaHub
    public static final String TARGET_CLAWBENCH = "clawbench";                 // 监控 ClawBench（github.com/clawbench-dev/clawbench）
    public static final String TARGET_PIWEB = "piweb";                         // 监控 pi-web（@agegr/pi-web，SSE 事件流）
    public static final String TARGET_CUSTOM_API = "custom_api";                   // 自定义 API 对话

    public static final int DEFAULT_FLING_THRESHOLD = 800;
    public static final int DEFAULT_BOUNCE = 55;
    public static final int DEFAULT_FRICTION = 90;
    public static final int DEFAULT_STOP_SPEED = 40;
    public static final int DEFAULT_FLING_DURATION = 6000;
    public static final int DEFAULT_PET_SIZE = 56;
    public static final int DEFAULT_BUBBLE_WIDTH = 220;
    public static final int DEFAULT_CARD_WIDTH = 200;
    public static final int DEFAULT_CARD_SCALE = 100;
    public static final int DEFAULT_MENU_WIDTH = 160;
    public static final int DEFAULT_RK_PORT = 8080;
    public static final int DEFAULT_CB_PORT = 20000;                            // ClawBench 默认端口
    public static final String DEFAULT_CB_TOKEN = "";
    public static final int DEFAULT_OPERIT_PORT = 8094;
    public static final int DEFAULT_PIWEB_PORT = 30141;

    public static int getIntPref(Context context, String key, int def) {
        return getPrefs(context).getInt(key, def);
    }

    /** Operit Web API 端口（主页可改，默认 8094） */
    public static int getOperitPort(Context context) {
        return getIntPref(context, KEY_OPERIT_PORT, DEFAULT_OPERIT_PORT);
    }

    /** ClawBench Web 服务端口（主页可改，默认 20000） */
    public static int getClawbenchPort(Context context) {
        return getIntPref(context, KEY_CB_PORT, DEFAULT_CB_PORT);
    }
    public static int getClawBenchPort(Context context) {
        return getClawbenchPort(context);
    }

    /** ClawBench 登录密码（cookie clawbench_session 认证） */
    public static String getClawbenchToken(Context context) {
        return getPrefs(context).getString(KEY_CB_TOKEN, DEFAULT_CB_TOKEN);
    }
    public static void setClawbenchToken(Context context, String token) {
        getPrefs(context).edit().putString(KEY_CB_TOKEN, token).apply();
    }
    public static String getClawBenchToken(Context context) {
        return getClawbenchToken(context);
    }

    /** pi-web 工作台端口（默认 30141） */
    public static int getPiWebPort(Context context) {
        return com.xm486.pimet.PiMetConfig.getWebPort(context);
    }

    public static void setPiWebPort(Context context, int port) {
        setIntPref(context, KEY_PIWEB_PORT, port);
        com.xm486.pimet.PiMetConfig.setWebPort(context, port);
    }

    public static void setOperitPort(Context context, int port) {
        setIntPref(context, KEY_OPERIT_PORT, port);
    }

    public static void setClawbenchPort(Context context, int port) {
        setIntPref(context, KEY_CB_PORT, port);
    }

    public static int getRikkaPort(Context context) {
        return getIntPref(context, KEY_RK_PORT, 8095);
    }

    public static void setRikkaPort(Context context, int port) {
        setIntPref(context, KEY_RK_PORT, port);
    }

    /** 是否已保存过某个整型设置（用于判断是否有历史值可回填） */
    public static boolean hasIntPref(Context context, String key) {
        return getPrefs(context).contains(key);
    }

    public static void setIntPref(Context context, String key, int value) {
        getPrefs(context).edit().putInt(key, value).apply();
    }

    public static String getStringPref(Context context, String key, String def) {
        return getPrefs(context).getString(key, def);
    }

    public static void setStringPref(Context context, String key, String value) {
        getPrefs(context).edit().putString(key, value).apply();
    }

    public static final String KEY_PET_ENABLED = "pet_enabled";

    /** 是否启用桌宠（默认 true） */
    public static boolean isPetEnabled(Context context) {
        return getPrefs(context).getBoolean(KEY_PET_ENABLED, true);
    }

    /** 切换桌宠启用状态 */
    public static void setPetEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(KEY_PET_ENABLED, enabled).apply();
    }

}
