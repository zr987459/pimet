package com.xm486.pimet.translation;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import com.xm486.pimet.PiMetConfig;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 有序引擎链：免费通道 / Pi 的模型 / 自定义 API，可排序、可开关，按顺序逐个尝试；
 * 每个引擎（以及免费通道里的每条线路）带熔断：连续失败或 429 冷却 3 分钟起、指数退避，冷却期间直接跳到下一个。
 */
public final class TranslationEngines {

    public static final String FREE = "free";
    public static final String PI = "pi";
    public static final String CUSTOM = "custom";

    static final String PREF = "pimet_translation";
    private static final String KEY_CHAIN = "engine_chain";
    private static final String DEFAULT_CHAIN = "free:1,pi:1,custom:0";

    private TranslationEngines() {}

    // ------------------------------------------------------------------ 链配置

    /** 引擎链的一项 */
    public static final class Entry {
        public final String id;
        public boolean enabled;

        public Entry(String id, boolean enabled) {
            this.id = id;
            this.enabled = enabled;
        }
    }

    /** 一次翻译用的完整配置（设置页“测试”时用未保存的值） */
    public static final class Config {
        public final List<Entry> chain = new ArrayList<>();
        /** Pi 的模型 provider/model；空 = 已保存的选择 / Pi 默认 */
        public String piRef = "";
        public String customBaseUrl = "";
        public String customApiKey = "";
        public String customModel = "";

        public List<String> enabledIds() {
            List<String> out = new ArrayList<>();
            for (Entry e : chain) if (e.enabled) out.add(e.id);
            return out;
        }

        /** 第一个启用的引擎（决定批次大小 / 线程池） */
        public String first() {
            for (Entry e : chain) if (e.enabled) return e.id;
            return FREE;
        }
    }

    public static String displayName(String id) {
        if (PI.equals(id)) return "Pi 的模型";
        if (CUSTOM.equals(id)) return "自定义 API";
        return "免费通道";
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 读取引擎链；没有新设置时从旧的「引擎策略」迁移一次 */
    public static List<Entry> loadChain(Context context) {
        SharedPreferences p = prefs(context);
        String raw = p.getString(KEY_CHAIN, null);
        if (raw == null) {
            raw = migrate(context);
            p.edit().putString(KEY_CHAIN, raw).apply();
        }
        return parseChain(raw);
    }

    private static String migrate(Context context) {
        String old;
        String customKey;
        try {
            old = PiMetConfig.getTransEngine(context);
            customKey = PiMetConfig.getTransCustomApiKey(context);
        } catch (Throwable t) {
            return DEFAULT_CHAIN;
        }
        boolean hasKey = !TextUtils.isEmpty(customKey);
        if (PiMetConfig.TRANS_ENGINE_AI.equals(old)) return "pi:1,free:1,custom:0";
        if (PiMetConfig.TRANS_ENGINE_CUSTOM.equals(old)) return "custom:1,free:1,pi:1";
        if (PiMetConfig.TRANS_ENGINE_AUTO.equals(old) && hasKey) return "custom:1,free:1,pi:1";
        return DEFAULT_CHAIN;
    }

    static List<Entry> parseChain(String raw) {
        List<Entry> list = new ArrayList<>();
        boolean free = false, pi = false, custom = false;
        if (raw != null) {
            for (String part : raw.split(",")) {
                String[] kv = part.trim().split(":");
                if (kv.length == 0) continue;
                String id = kv[0].trim();
                boolean en = kv.length < 2 || !"0".equals(kv[1].trim());
                if (FREE.equals(id) && !free) { free = true; list.add(new Entry(FREE, en)); }
                else if (PI.equals(id) && !pi) { pi = true; list.add(new Entry(PI, en)); }
                else if (CUSTOM.equals(id) && !custom) { custom = true; list.add(new Entry(CUSTOM, en)); }
            }
        }
        if (!free) list.add(new Entry(FREE, true));
        if (!pi) list.add(new Entry(PI, true));
        if (!custom) list.add(new Entry(CUSTOM, false));
        return list;
    }

    public static void saveChain(Context context, List<Entry> chain) {
        StringBuilder sb = new StringBuilder();
        for (Entry e : chain) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.id).append(':').append(e.enabled ? '1' : '0');
        }
        prefs(context).edit().putString(KEY_CHAIN, sb.toString()).apply();
    }

    /** 当前保存的配置（只读 SharedPreferences，可在主线程调用） */
    public static Config current(Context context) {
        Config c = new Config();
        c.chain.addAll(loadChain(context));
        c.piRef = PiModelCatalog.savedRef(context);
        c.customBaseUrl = PiMetConfig.getTransCustomBaseUrl(context);
        c.customApiKey = PiMetConfig.getTransCustomApiKey(context);
        c.customModel = PiMetConfig.getTransCustomModel(context);
        return c;
    }

    // ------------------------------------------------------------------ 熔断

    private static final long BASE_COOLDOWN_MS = 3 * 60 * 1000L;
    private static final long MAX_COOLDOWN_MS = 30 * 60 * 1000L;
    private static final int FAILS_TO_OPEN = 2;

    private static final class Breaker {
        int fails;
        int level;
        long until;
        boolean rateLimited;
        String lastError = "";
        long lastOkAt;
    }

    private static final Map<String, Breaker> BREAKERS = new HashMap<>();

    private static Breaker breaker(String id) {
        Breaker b = BREAKERS.get(id);
        if (b == null) {
            b = new Breaker();
            BREAKERS.put(id, b);
        }
        return b;
    }

    /** 是否在冷却中（冷却期间跳过此引擎 / 线路） */
    public static boolean isCooling(String id) {
        synchronized (BREAKERS) {
            Breaker b = BREAKERS.get(id);
            return b != null && System.currentTimeMillis() < b.until;
        }
    }

    public static void recordSuccess(String id) {
        synchronized (BREAKERS) {
            Breaker b = breaker(id);
            b.fails = 0;
            b.level = 0;
            b.until = 0;
            b.rateLimited = false;
            b.lastOkAt = System.currentTimeMillis();
        }
    }

    public static void recordFailure(String id, Throwable t) {
        boolean limited = isRateLimited(t);
        synchronized (BREAKERS) {
            Breaker b = breaker(id);
            b.lastError = WebTranslationManager.describe(t);
            b.fails++;
            if (limited || b.fails >= FAILS_TO_OPEN) {
                long cd = Math.min(MAX_COOLDOWN_MS, BASE_COOLDOWN_MS << Math.min(b.level, 4));
                b.until = System.currentTimeMillis() + cd;
                b.level++;
                b.fails = 0;
                b.rateLimited = limited;
            }
        }
    }

    static boolean isRateLimited(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause()) {
            String m = x.getMessage();
            if (m == null) continue;
            String l = m.toLowerCase(Locale.ROOT);
            if (l.contains("http 429") || l.contains("too many requests") || l.contains("rate limit") || l.contains("限流")
                    || l.contains("quota") || l.contains("额度")) return true;
        }
        return false;
    }

    /** 给设置页看的一句状态：“限流中，2 分钟后重试” / “正常” … */
    public static String statusText(String id) {
        synchronized (BREAKERS) {
            Breaker b = BREAKERS.get(id);
            long now = System.currentTimeMillis();
            if (b == null) return "未使用";
            if (now < b.until) {
                long min = Math.max(1, (b.until - now + 59999) / 60000);
                return (b.rateLimited ? "限流中" : "连续失败") + "，" + min + " 分钟后重试";
            }
            if (b.fails > 0) return "刚失败过：" + b.lastError;
            if (b.lastOkAt > 0) return "正常";
            return "未使用";
        }
    }

    // ------------------------------------------------------------------ 执行

    /** 按链顺序翻译；全部失败时抛出包含每一步原因的异常。后台线程调用。 */
    public static List<String> translate(Context context, Config cfg, List<String> texts) throws Exception {
        List<String> ids = cfg.enabledIds();
        if (ids.isEmpty()) throw new Exception("引擎链里没有启用的引擎，请在翻译设置里至少打开一个");
        StringBuilder errs = new StringBuilder();
        List<String> skipped = new ArrayList<>();
        for (String id : ids) {
            if (isCooling(id)) {
                skipped.add(id);
                append(errs, displayName(id) + "：" + statusText(id));
                continue;
            }
            try {
                List<String> r = runEngine(context, cfg, id, texts);
                recordSuccess(id);
                return r;
            } catch (Throwable t) {
                recordFailure(id, t);
                append(errs, displayName(id) + "：" + WebTranslationManager.describe(t));
            }
        }
        // 能用的都失败或在冷却：自愈兜底，再硬试一次被跳过的冷却中引擎
        for (String id : skipped) {
            try {
                List<String> r = runEngine(context, cfg, id, texts);
                recordSuccess(id);
                return r;
            } catch (Throwable t) {
                recordFailure(id, t);
            }
        }
        throw new Exception(errs.toString());
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) sb.append("；");
        sb.append(s);
    }

    /** 跑单个引擎（不经过熔断判断） */
    static List<String> runEngine(Context context, Config cfg, String id, List<String> texts) throws Exception {
        if (FREE.equals(id)) {
            return WebTranslationManager.translateWithFreeChannel(texts);
        }
        if (PI.equals(id)) {
            PiModelCatalog.PiModel m = PiModelCatalog.find(context, cfg.piRef);
            if (m == null) throw new Exception("没读到 Pi 的模型配置（~/.pi/agent/models.json、settings.json）");
            try {
                return WebTranslationManager.translateWithPiModel(m, texts);
            } catch (Throwable t) {
                String hint = m.keyMissing ? "（" + m.keySource + "）" : "";
                throw new Exception(m.ref() + " " + WebTranslationManager.describe(t) + hint, t);
            }
        }
        if (CUSTOM.equals(id)) {
            if (TextUtils.isEmpty(cfg.customApiKey) && TextUtils.isEmpty(cfg.customBaseUrl)) {
                throw new Exception("未填写 Base URL / Key");
            }
            return WebTranslationManager.translateWithOpenAiCompatible(cfg.customBaseUrl, cfg.customApiKey, cfg.customModel, texts);
        }
        throw new Exception("未知引擎 " + id);
    }

    /** 当前模型名提示：以 -high 结尾的推理模型较慢 */
    static String slowModelHint(Context context, Config cfg, String id) {
        String model = null;
        if (PI.equals(id)) {
            PiModelCatalog.PiModel m = PiModelCatalog.find(context, cfg.piRef);
            model = m == null ? null : m.id;
        } else if (CUSTOM.equals(id)) {
            model = cfg.customModel;
        }
        return model != null && model.toLowerCase(Locale.ROOT).endsWith("-high")
                ? "（推理模型较慢，建议选 -low / flash 版本）" : "";
    }

    /** 测试结果的一行 */
    public static final class TestLine {
        public final String id;
        public final boolean ok;
        public final long latencyMs;
        public final String text;

        TestLine(String id, boolean ok, long latencyMs, String text) {
            this.id = id;
            this.ok = ok;
            this.latencyMs = latencyMs;
            this.text = text;
        }
    }

    /** 逐个测试链上每个启用的引擎（忽略冷却），后台线程调用 */
    public static List<TestLine> testChain(Context context, Config cfg) {
        List<TestLine> out = new ArrayList<>();
        List<String> sample = new ArrayList<>();
        sample.add("Hello, welcome to PiMet web translation!");
        for (String id : cfg.enabledIds()) {
            long start = System.currentTimeMillis();
            try {
                List<String> r = runEngine(context, cfg, id, sample);
                long ms = System.currentTimeMillis() - start;
                recordSuccess(id);
                String tr = r == null || r.isEmpty() ? "" : r.get(0);
                out.add(new TestLine(id, true, ms, tr + slowModelHint(context, cfg, id)));
            } catch (Throwable t) {
                long ms = System.currentTimeMillis() - start;
                recordFailure(id, t);
                out.add(new TestLine(id, false, ms, WebTranslationManager.describe(t) + slowModelHint(context, cfg, id)));
            }
        }
        return out;
    }
}
