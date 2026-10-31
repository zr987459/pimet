package com.xm486.pimet.translation;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.xm486.pimet.proot.ProotManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 读取 Pi 已配置的模型（容器内 /root/.pi/agent 的 models.json / settings.json / auth.json 与 /root/.bashrc），
 * 供网页翻译「Pi 的模型」引擎使用。所有读文件的方法都会碰磁盘，只能在后台线程调用。
 */
public final class PiModelCatalog {

    public static final String API_ANTHROPIC = "anthropic-messages";

    private static final String PREF = "pimet_translation";
    private static final String KEY_PROVIDER = "pi_model_provider";
    private static final String KEY_MODEL = "pi_model_id";

    private static final Pattern ENV_NAME = Pattern.compile("^[A-Z_][A-Z0-9_]*$");
    private static final Pattern EXPORT_LINE = Pattern.compile("^\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$");

    /** 一个可选的 Pi 模型（provider + model） */
    public static final class PiModel {
        public String provider;
        public String id;
        public String name;
        public String api;
        public String baseUrl;
        public String apiKey;
        /** apiKey 来源说明 / 未找到时的提示 */
        public String keySource;
        public boolean keyMissing;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public boolean isDefault;

        public String ref() {
            return provider + "/" + id;
        }

        public boolean isAnthropic() {
            return API_ANTHROPIC.equals(api);
        }

        public String label() {
            String n = TextUtils.isEmpty(name) || name.equals(id) ? id : name + " (" + id + ")";
            return provider + " / " + n + (isDefault ? " · Pi 默认" : "");
        }

        public String detail() {
            return (TextUtils.isEmpty(api) ? "openai-completions" : api) + " · " + baseUrl
                    + "\nKey：" + (keyMissing ? "⚠ " : "") + keySource;
        }
    }

    private static List<PiModel> sCache;
    private static long sCacheAt;
    private static long sCacheStamp;

    private PiModelCatalog() {}

    static File agentDir(Context context) {
        return new File(ProotManager.getRootfsDir(context), "root/.pi/agent");
    }

    /** 读取全部模型（带 5 秒缓存，文件有改动立即失效）。后台线程调用。 */
    public static synchronized List<PiModel> load(Context context) {
        File agent = agentDir(context);
        File root = agent.getParentFile() != null ? agent.getParentFile().getParentFile() : null;
        File models = new File(agent, "models.json");
        File settings = new File(agent, "settings.json");
        File auth = new File(agent, "auth.json");
        File bashrc = root == null ? null : new File(root, ".bashrc");
        long stamp = models.lastModified() * 31 + settings.lastModified() * 17 + auth.lastModified() * 7
                + (bashrc == null ? 0 : bashrc.lastModified());
        long now = System.currentTimeMillis();
        if (sCache != null && stamp == sCacheStamp && now - sCacheAt < 5000L) return sCache;
        List<PiModel> out;
        try {
            out = Collections.unmodifiableList(read(models, settings, auth, root));
        } catch (Throwable t) {
            out = Collections.emptyList();
        }
        sCache = out;
        sCacheAt = now;
        sCacheStamp = stamp;
        return out;
    }

    private static List<PiModel> read(File modelsFile, File settingsFile, File authFile, File rootHome) {
        JSONObject settings = readJson(settingsFile);
        JSONObject auth = readJson(authFile);
        Map<String, String> env = new HashMap<>();
        if (rootHome != null) {
            // 后读的覆盖先读的：/etc/environment < .profile < .bash_profile < .bashrc
            File rootfs = rootHome.getParentFile();
            if (rootfs != null) readExports(new File(rootfs, "etc/environment"), env);
            readExports(new File(rootHome, ".profile"), env);
            readExports(new File(rootHome, ".bash_profile"), env);
            readExports(new File(rootHome, ".bashrc"), env);
        }
        String defProvider = settings == null ? "" : settings.optString("defaultProvider", "");
        String defModel = settings == null ? "" : settings.optString("defaultModel", "");

        List<PiModel> list = new ArrayList<>();
        JSONObject root = readJson(modelsFile);
        JSONObject providers = root == null ? null : root.optJSONObject("providers");
        boolean defaultSeen = false;
        if (providers != null) {
            Iterator<String> it = providers.keys();
            while (it.hasNext()) {
                String pName = it.next();
                JSONObject p = providers.optJSONObject(pName);
                if (p == null) continue;
                String baseUrl = p.optString("baseUrl", "").trim();
                String api = p.optString("api", "").trim();
                String[] key = resolveKey(pName, p.optString("apiKey", ""), auth, env);
                Map<String, String> headers = new LinkedHashMap<>();
                JSONObject h = p.optJSONObject("headers");
                if (h != null) {
                    Iterator<String> hk = h.keys();
                    while (hk.hasNext()) {
                        String k = hk.next();
                        String v = h.optString(k, "");
                        String rv = lookupVar(v, auth, env);
                        headers.put(k, rv != null ? rv : v);
                    }
                }
                if (TextUtils.isEmpty(baseUrl)) baseUrl = builtinBase(pName);
                if (TextUtils.isEmpty(api)) api = builtinApi(pName);
                JSONArray ms = p.optJSONArray("models");
                List<String[]> ids = new ArrayList<>();
                if (ms != null) {
                    for (int i = 0; i < ms.length(); i++) {
                        JSONObject m = ms.optJSONObject(i);
                        if (m == null || TextUtils.isEmpty(m.optString("id", ""))) continue;
                        ids.add(new String[]{m.optString("id"), m.optString("name", ""), m.optString("api", "")});
                    }
                }
                // 只覆盖了内置 provider 的 baseUrl、没列模型：至少把默认模型放进来
                if (ids.isEmpty() && pName.equals(defProvider) && !TextUtils.isEmpty(defModel)) {
                    ids.add(new String[]{defModel, "", ""});
                }
                if (TextUtils.isEmpty(baseUrl)) continue;
                for (String[] m : ids) {
                    PiModel pm = new PiModel();
                    pm.provider = pName;
                    pm.id = m[0];
                    pm.name = m[1];
                    pm.api = TextUtils.isEmpty(m[2]) ? api : m[2];
                    pm.baseUrl = baseUrl;
                    pm.apiKey = key[0];
                    pm.keySource = key[1];
                    pm.keyMissing = TextUtils.isEmpty(key[0]) || "1".equals(key[2]);
                    pm.headers.putAll(headers);
                    pm.isDefault = pName.equals(defProvider) && m[0].equals(defModel);
                    if (pm.isDefault) defaultSeen = true;
                    list.add(pm);
                }
            }
        }
        // 默认模型是 Pi 内置 provider（models.json 里没有）：用内置地址 + auth.json 的 key
        if (!defaultSeen && !TextUtils.isEmpty(defProvider) && !TextUtils.isEmpty(defModel)
                && !TextUtils.isEmpty(builtinBase(defProvider))) {
            String[] key = resolveKey(defProvider, "", auth, env);
            if (!TextUtils.isEmpty(key[0])) {
                PiModel pm = new PiModel();
                pm.provider = defProvider;
                pm.id = defModel;
                pm.name = "";
                pm.api = builtinApi(defProvider);
                pm.baseUrl = builtinBase(defProvider);
                pm.apiKey = key[0];
                pm.keySource = key[1];
                pm.keyMissing = false;
                pm.isDefault = true;
                list.add(0, pm);
            }
        }
        // 默认模型排第一
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).isDefault && i > 0) {
                list.add(0, list.remove(i));
                break;
            }
        }
        return list;
    }

    private static String builtinBase(String provider) {
        if ("anthropic".equals(provider)) return "https://api.anthropic.com";
        if ("openai".equals(provider)) return "https://api.openai.com/v1";
        if ("deepseek".equals(provider)) return "https://api.deepseek.com/v1";
        if ("openrouter".equals(provider)) return "https://openrouter.ai/api/v1";
        return "";
    }

    private static String builtinApi(String provider) {
        return "anthropic".equals(provider) ? API_ANTHROPIC : "";
    }

    /** @return {值, 来源说明, 未解析标记("1"=像变量名但没找到值)} */
    private static String[] resolveKey(String provider, String raw, JSONObject auth, Map<String, String> env) {
        String v = raw == null ? "" : raw.trim();
        if (v.startsWith("!")) {
            String fromAuth = authKey(auth, provider);
            if (!TextUtils.isEmpty(fromAuth)) return new String[]{fromAuth, "auth.json 的 " + provider, ""};
            return new String[]{"", "apiKey 是命令（" + v + "），翻译不支持执行命令取 Key", "1"};
        }
        if (!TextUtils.isEmpty(v)) {
            if (ENV_NAME.matcher(v).matches()) {
                String rv = lookupVar(v, auth, env);
                if (!TextUtils.isEmpty(rv)) return new String[]{rv, "变量 " + v, ""};
                String fromAuth = authKey(auth, provider);
                if (!TextUtils.isEmpty(fromAuth)) return new String[]{fromAuth, "auth.json 的 " + provider, ""};
                return new String[]{v, "apiKey「" + v + "」像环境变量名，但 auth.json / .bashrc 里没找到它的值", "1"};
            }
            return new String[]{v, "models.json 里的 apiKey", ""};
        }
        String fromAuth = authKey(auth, provider);
        if (!TextUtils.isEmpty(fromAuth)) return new String[]{fromAuth, "auth.json 的 " + provider, ""};
        return new String[]{"", "没有配置 apiKey", "1"};
    }

    /** 变量名 → 值：auth.json 同名条目、/root/.bashrc 等的 export；不是变量名或找不到返回 null */
    private static String lookupVar(String name, JSONObject auth, Map<String, String> env) {
        if (name == null || !ENV_NAME.matcher(name.trim()).matches()) return null;
        String n = name.trim();
        String a = authKey(auth, n);
        if (!TextUtils.isEmpty(a)) return a;
        String e = env.get(n);
        if (!TextUtils.isEmpty(e)) return e;
        return null;
    }

    private static String authKey(JSONObject auth, String name) {
        if (auth == null || name == null || !auth.has(name)) return null;
        Object o = auth.opt(name);
        if (o instanceof String) return (String) o;
        if (o instanceof JSONObject) {
            JSONObject j = (JSONObject) o;
            String k = j.optString("key", "");
            if (TextUtils.isEmpty(k)) k = j.optString("apiKey", "");
            if (TextUtils.isEmpty(k)) k = j.optString("access", "");
            return TextUtils.isEmpty(k) ? null : k;
        }
        return null;
    }

    private static void readExports(File f, Map<String, String> env) {
        String s = readText(f);
        if (s == null) return;
        for (String line : s.split("\n")) {
            Matcher m = EXPORT_LINE.matcher(line);
            if (!m.matches()) continue;
            String v = m.group(2).trim();
            if (v.length() >= 2 && ((v.startsWith("\"") && v.endsWith("\"")) || (v.startsWith("'") && v.endsWith("'")))) {
                v = v.substring(1, v.length() - 1);
            } else {
                int hash = v.indexOf(" #");
                if (hash >= 0) v = v.substring(0, hash).trim();
            }
            env.put(m.group(1), v);
        }
    }

    private static JSONObject readJson(File f) {
        String s = readText(f);
        if (TextUtils.isEmpty(s)) return null;
        try {
            return new JSONObject(s);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String readText(File f) {
        if (f == null || !f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 选择（存在翻译自己的 SharedPreferences 里）

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static void saveSelection(Context context, String provider, String modelId) {
        prefs(context).edit().putString(KEY_PROVIDER, provider == null ? "" : provider)
                .putString(KEY_MODEL, modelId == null ? "" : modelId).apply();
    }

    public static String savedRef(Context context) {
        String p = prefs(context).getString(KEY_PROVIDER, "");
        String m = prefs(context).getString(KEY_MODEL, "");
        return TextUtils.isEmpty(p) || TextUtils.isEmpty(m) ? "" : p + "/" + m;
    }

    /** 按 ref（provider/model）找；ref 为空或已不存在时取 Pi 默认模型，再不行取第一个。后台线程调用。 */
    public static PiModel find(Context context, String ref) {
        List<PiModel> list = load(context);
        if (list.isEmpty()) return null;
        if (!TextUtils.isEmpty(ref)) {
            for (PiModel m : list) if (ref.equals(m.ref())) return m;
        }
        for (PiModel m : list) if (m.isDefault) return m;
        return list.get(0);
    }

    /** 当前保存的选择（没选过就是 Pi 默认模型）。后台线程调用。 */
    public static PiModel selected(Context context) {
        return find(context, savedRef(context));
    }
}
