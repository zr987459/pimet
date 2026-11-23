package com.xm486.pimet.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.util.Log;
import android.webkit.WebSettings;

import androidx.webkit.UserAgentMetadata;
import androidx.webkit.WebSettingsCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-Container 指纹与环境隔离配置与 UA / Client Hints 构建器。
 */
public final class ContainerEnv {

    private static final String TAG = "ContainerEnv";
    private static final SecureRandom RNG = new SecureRandom();
    private static final Pattern CHROME_VER_RE = Pattern.compile("Chrome/([\\d.]+)");
    private static final Pattern PKG_VER_RE = Pattern.compile("(\\d+)(?:\\.\\d+){1,3}");
    private static final Pattern TZ_CHARS_RE = Pattern.compile("^[A-Za-z][A-Za-z0-9_+.-]*(?:/[A-Za-z0-9_+.-]+)*$");
    private static final Map<String, String> VALID_TIMEZONES;
    static {
        Map<String, String> tzMap = new HashMap<>();
        for (String id : TimeZone.getAvailableIDs()) {
            if (id != null && !id.isEmpty()) {
                tzMap.put(id.toLowerCase(Locale.ROOT), id);
            }
        }
        VALID_TIMEZONES = Collections.unmodifiableMap(tzMap);
    }

    /** 校验并返回规范 IANA 时区 ID；空串合法（表示不覆写系统时区），非法返回 null */
    public static String normalizeTimezone(String raw) {
        if (raw == null) return "";
        String tz = raw.trim();
        if (tz.isEmpty()) return "";
        if (!TZ_CHARS_RE.matcher(tz).matches() || tz.contains("..")) return null;
        return VALID_TIMEZONES.get(tz.toLowerCase(Locale.ROOT));
    }

    public static final String PRESET_NATIVE = "native";
    public static final String PRESET_AUTO = "auto";
    public static final String PRESET_CA_EN = "ca-en";
    public static final String PRESET_US_EN = "us-en";
    public static final String PRESET_US_WEST = "us-west";
    public static final String PRESET_UK_EN = "uk-en";
    public static final String PRESET_AU_EN = "au-en";
    public static final String PRESET_JP_JA = "jp-ja";
    public static final String PRESET_CN_ZH = "cn-zh";
    public static final String PRESET_CUSTOM = "custom";

    public static final String WEBRTC_SANITIZE = "sanitize";
    public static final String WEBRTC_DISABLED = "disabled";
    public static final String WEBRTC_NATIVE = "native";

    public static final int MOBILE_VIEWPORT_W = 390;
    public static final int MOBILE_VIEWPORT_H = 844;
    public static final int DESKTOP_VIEWPORT_W = 1280;
    public static final int DESKTOP_VIEWPORT_H = 800;

    public static int viewportWidth(boolean desktop) {
        return desktop ? DESKTOP_VIEWPORT_W : MOBILE_VIEWPORT_W;
    }

    public static int viewportHeight(boolean desktop) {
        return desktop ? DESKTOP_VIEWPORT_H : MOBILE_VIEWPORT_H;
    }

    /** 真实设备规格 */
    public static final class DeviceSpec {
        public final String id;
        public final String model;
        public final String androidVer;
        public final int screenW;
        public final int screenH;
        public final double dpr;
        public final int hardwareConcurrency;
        public final int deviceMemory;
        public final String glVendor;
        public final String glRenderer;
        public final String platform;
        public final String chPlatform;
        public final String chPlatformVer;
        public final String chArch;
        public final int chBitness;
        public final boolean mobile;

        public DeviceSpec(String id, String model, String androidVer, int screenW, int screenH,
                          double dpr, int hardwareConcurrency, int deviceMemory,
                          String glVendor, String glRenderer, String platform,
                          String chPlatform, String chPlatformVer, String chArch, int chBitness,
                          boolean mobile) {
            this.id = id;
            this.model = model;
            this.androidVer = androidVer;
            this.screenW = screenW;
            this.screenH = screenH;
            this.dpr = dpr;
            this.hardwareConcurrency = hardwareConcurrency;
            this.deviceMemory = Math.min(8, Math.max(2, deviceMemory));
            this.glVendor = glVendor;
            this.glRenderer = glRenderer;
            this.platform = platform;
            this.chPlatform = chPlatform;
            this.chPlatformVer = chPlatformVer;
            this.chArch = chArch;
            this.chBitness = chBitness;
            this.mobile = mobile;
        }
    }

    /** 代理端点（不可变，用于前置上游 upstream 或基础端点表示） */
    public static final class ProxyEndpoint {
        public final String type;
        public final String host;
        public final int port;
        public final String username;
        public final String password;

        public ProxyEndpoint(String type, String host, int port, String username, String password) {
            this.type = normalizeProxyType(type);
            this.host = host == null ? "" : host.trim();
            this.port = port;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
        }

        public boolean isValid() {
            return !host.isEmpty() && port >= 1 && port <= 65535;
        }

        public boolean hasAuth() {
            return !username.isEmpty() || !password.isEmpty();
        }

        public String credentialHash() {
            return hasAuth() ? sha256Hex(username + "\u0000" + password) : "";
        }

        public String endpointDisplay() {
            if (!isValid()) return "";
            return type + "://" + formatProxyHost(host) + ":" + port;
        }

        String canonicalString() {
            return type + "|" + host.toLowerCase(Locale.ROOT) + "|" + port + "|" + credentialHash();
        }

        public JSONObject toSummaryJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("type", type);
                o.put("host", host);
                o.put("port", port);
                o.put("hasAuth", hasAuth());
                o.put("summary", endpointDisplay());
            } catch (Exception ignored) {}
            return o;
        }

        public JSONObject toStorageJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("type", type);
                o.put("host", host);
                o.put("port", port);
                if (!username.isEmpty()) o.put("username", username);
                if (!password.isEmpty()) o.put("password", password);
            } catch (Exception ignored) {}
            return o;
        }
    }

    /** 容器代理配置（不可变） */
    public static final class ProxySpec {
        public final boolean enabled;
        public final String type;
        public final String host;
        public final int port;
        public final String username;
        public final String password;
        public final ProxyEndpoint upstream;

        public ProxySpec(boolean enabled, String type, String host, int port,
                         String username, String password, ProxyEndpoint upstream) {
            this.enabled = enabled;
            this.type = normalizeProxyType(type);
            this.host = host == null ? "" : host.trim();
            this.port = port;
            this.username = username == null ? "" : username;
            this.password = password == null ? "" : password;
            this.upstream = upstream;
        }

        public boolean hasAuth() {
            return !username.isEmpty() || !password.isEmpty();
        }

        public String credentialHash() {
            return hasAuth() ? sha256Hex(username + "\u0000" + password) : "";
        }

        public boolean isActive() {
            return enabled && !host.isEmpty() && port >= 1 && port <= 65535;
        }

        public String signature() {
            if (!isActive()) return "DIRECT";
            String canonical = type + "|" + host.toLowerCase(Locale.ROOT) + "|" + port + "|"
                    + credentialHash() + "|" + (upstream != null ? upstream.canonicalString() : "");
            return "PROXY:" + sha256Hex(canonical);
        }

        public String summary() {
            if (!isActive()) return "直连";
            String base = type + "://" + formatProxyHost(host) + ":" + port;
            if (upstream != null && upstream.isValid()) {
                return base + " via " + upstream.endpointDisplay();
            }
            return base;
        }

        public JSONObject toSummaryJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("enabled", enabled);
                o.put("type", type);
                o.put("host", host);
                o.put("port", port);
                o.put("hasAuth", hasAuth());
                o.put("summary", summary());
                o.put("signature", signature());
                if (upstream != null) {
                    o.put("upstream", upstream.toSummaryJson());
                }
            } catch (Exception ignored) {}
            return o;
        }

        public JSONObject toStorageJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("enabled", enabled);
                o.put("type", type);
                o.put("host", host);
                o.put("port", port);
                if (!username.isEmpty()) o.put("username", username);
                if (!password.isEmpty()) o.put("password", password);
                if (upstream != null) {
                    o.put("upstream", upstream.toStorageJson());
                }
            } catch (Exception ignored) {}
            return o;
        }
    }

    /** 容器环境配置（不可变） */
    public static final class Profile {
        public final String preset;
        public final String timezone;
        public final String locale;
        public final List<String> languages;
        public final String acceptLanguage;
        public final String device;
        public final boolean desktop;
        public final long seed;
        public final String webrtc;
        public final ProxySpec proxy;

        public Profile(String preset, String timezone, String locale, List<String> languages,
                       String acceptLanguage, String device, boolean desktop, long seed, String webrtc) {
            this(preset, timezone, locale, languages, acceptLanguage, device, desktop, seed, webrtc, null);
        }

        public Profile(String preset, String timezone, String locale, List<String> languages,
                       String acceptLanguage, String device, boolean desktop, long seed, String webrtc,
                       ProxySpec proxy) {
            this.preset = preset == null || preset.isEmpty() ? PRESET_NATIVE : preset;
            this.timezone = timezone == null ? "" : timezone;
            this.locale = locale == null ? "" : locale;
            this.languages = languages == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(languages));
            this.acceptLanguage = acceptLanguage == null ? "" : acceptLanguage;
            this.device = device == null ? "" : device;
            this.desktop = desktop;
            this.seed = seed;
            this.webrtc = webrtc == null || webrtc.isEmpty()
                    ? (PRESET_NATIVE.equals(this.preset) ? WEBRTC_NATIVE : WEBRTC_SANITIZE)
                    : webrtc;
            this.proxy = proxy;
        }

        public boolean isIsolated() {
            return !PRESET_NATIVE.equals(preset);
        }

        public boolean hasActiveProxy() {
            return proxy != null && proxy.isActive();
        }

        public String proxySignature() {
            return proxy != null ? proxy.signature() : "DIRECT";
        }
    }

    public static final Profile NATIVE = new Profile(
            PRESET_NATIVE, "", "", Collections.emptyList(), "", "", false, 0L, WEBRTC_NATIVE, null
    );

    public static final DeviceSpec[] MOBILE_DEVICES = {
            new DeviceSpec("pixel-8", "Pixel 8", "14", 412, 915, 2.625, 8, 8,
                    "Google Inc. (ARM)", "ANGLE (ARM, Mali-G715, OpenGL ES 3.2)", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("pixel-8-pro", "Pixel 8 Pro", "14", 448, 998, 3.0, 8, 8,
                    "Google Inc. (ARM)", "ANGLE (ARM, Mali-G715, OpenGL ES 3.2)", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("pixel-7a", "Pixel 7a", "14", 412, 915, 2.625, 8, 8,
                    "Google Inc. (ARM)", "ANGLE (ARM, Mali-G710, OpenGL ES 3.2)", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("sm-s921b", "SM-S921B", "14", 360, 780, 3.0, 8, 8,
                    "Qualcomm", "Adreno (TM) 750", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("sm-s918b", "SM-S918B", "14", 384, 824, 3.75, 8, 8,
                    "Qualcomm", "Adreno (TM) 740", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("sm-a546b", "SM-A546B", "14", 384, 854, 2.8125, 8, 6,
                    "ARM", "Mali-G68", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("sm-a356e", "SM-A356E", "14", 384, 854, 2.8125, 8, 6,
                    "ARM", "Mali-G68", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("cph2581", "CPH2581", "14", 360, 804, 3.0, 8, 8,
                    "Qualcomm", "Adreno (TM) 750", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("cph2449", "CPH2449", "13", 360, 804, 3.0, 8, 8,
                    "Qualcomm", "Adreno (TM) 740", "Linux armv8l",
                    "Android", "13.0.0", "arm", 64, true),
            new DeviceSpec("23127pn0cg", "23127PN0CG", "14", 400, 889, 3.0, 8, 8,
                    "Qualcomm", "Adreno (TM) 750", "Linux armv8l",
                    "Android", "14.0.0", "arm", 64, true),
            new DeviceSpec("moto-edge-40", "motorola edge 40", "13", 412, 915, 2.625, 8, 8,
                    "ARM", "Mali-G77 MC9", "Linux armv8l",
                    "Android", "13.0.0", "arm", 64, true),
            new DeviceSpec("moto-g84", "moto g84 5G", "13", 412, 915, 2.625, 8, 8,
                    "Qualcomm", "Adreno (TM) 619", "Linux armv8l",
                    "Android", "13.0.0", "arm", 64, true)
    };

    public static final DeviceSpec WIN_CHROME = new DeviceSpec(
            "win-chrome", "", "", 1920, 1080, 1.0, 8, 8,
            "Google Inc. (NVIDIA)", "ANGLE (NVIDIA, NVIDIA GeForce RTX 3060 Direct3D11 vs_5_0 ps_5_0, D3D11)", "Win32",
            "Windows", "15.0.0", "x86", 64, false
    );

    public static final DeviceSpec MAC_CHROME = new DeviceSpec(
            "mac-chrome", "", "", 1440, 900, 2.0, 8, 8,
            "Google Inc. (Apple)", "ANGLE (Apple, ANGLE Metal Renderer: Apple M2, Unspecified Version)", "MacIntel",
            "macOS", "14.5.0", "arm", 64, false
    );

    private static final class PresetDef {
        final String id;
        final String emoji;
        final String label;
        final String timezone;
        final String locale;
        final List<String> languages;
        final String acceptLanguage;

        PresetDef(String id, String emoji, String label, String timezone, String locale,
                  List<String> languages, String acceptLanguage) {
            this.id = id;
            this.emoji = emoji;
            this.label = label;
            this.timezone = timezone;
            this.locale = locale;
            this.languages = Collections.unmodifiableList(languages);
            this.acceptLanguage = acceptLanguage;
        }
    }

    private static final Map<String, PresetDef> PRESETS = new LinkedHashMap<>();
    static {
        addPreset(new PresetDef(PRESET_CA_EN, "🇨🇦", "加拿大 (Toronto)", "America/Toronto", "en-CA",
                Arrays.asList("en-CA", "en-US", "en"), "en-CA,en-US;q=0.9,en;q=0.8"));
        addPreset(new PresetDef(PRESET_US_EN, "🇺🇸", "美国东部 (New York)", "America/New_York", "en-US",
                Arrays.asList("en-US", "en"), "en-US,en;q=0.9"));
        addPreset(new PresetDef(PRESET_US_WEST, "🇺🇸", "美国西部 (Los Angeles)", "America/Los_Angeles", "en-US",
                Arrays.asList("en-US", "en"), "en-US,en;q=0.9"));
        addPreset(new PresetDef(PRESET_UK_EN, "🇬🇧", "英国 (London)", "Europe/London", "en-GB",
                Arrays.asList("en-GB", "en-US", "en"), "en-GB,en-US;q=0.9,en;q=0.8"));
        addPreset(new PresetDef(PRESET_AU_EN, "🇦🇺", "澳大利亚 (Sydney)", "Australia/Sydney", "en-AU",
                Arrays.asList("en-AU", "en-GB", "en"), "en-AU,en-GB;q=0.9,en;q=0.8"));
        addPreset(new PresetDef(PRESET_JP_JA, "🇯🇵", "日本 (Tokyo)", "Asia/Tokyo", "ja-JP",
                Arrays.asList("ja-JP", "ja", "en-US"), "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7"));
        addPreset(new PresetDef(PRESET_CN_ZH, "🇨🇳", "中国 (Shanghai)", "Asia/Shanghai", "zh-CN",
                Arrays.asList("zh-CN", "zh", "en-US"), "zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7"));
        addPreset(new PresetDef(PRESET_AUTO, "🛡", "隔离设备 (保留系统语言/时区)", "", "",
                Collections.emptyList(), ""));
        addPreset(new PresetDef(PRESET_NATIVE, "🔓", "原生不伪装", "", "",
                Collections.emptyList(), ""));
    }

    private static void addPreset(PresetDef d) {
        PRESETS.put(d.id, d);
    }

    private static volatile String sChromeFullVer;
    private static volatile String sChromeMajorVer;
    private static volatile String sGreaseBrand;
    private static volatile String sGreaseMajor;
    private static volatile String sGreaseFull;

    private ContainerEnv() {}

    // ------------------------------------------------------------------ 预设与构造

    public static List<String> presetIds() {
        return new ArrayList<>(PRESETS.keySet());
    }

    public static String presetDisplayLabel(String preset) {
        PresetDef d = PRESETS.get(preset);
        if (d == null) return "🛡 自定义环境";
        return d.emoji + " " + d.label;
    }

    public static Profile createAuto() {
        return new Profile(
                PRESET_AUTO, "", "", Collections.emptyList(), "",
                randomMobileDevice().id, false, nextSeed(), WEBRTC_SANITIZE
        );
    }

    public static Profile createPreset(String presetId) {
        if (PRESET_NATIVE.equals(presetId)) return NATIVE;
        PresetDef d = PRESETS.get(presetId);
        if (d == null) throw new IllegalArgumentException("未知环境预设: " + presetId);
        return new Profile(
                d.id, d.timezone, d.locale, d.languages, d.acceptLanguage,
                randomMobileDevice().id, false, nextSeed(), WEBRTC_SANITIZE
        );
    }

    public static Profile randomize(Profile base) {
        ProxySpec proxy = base != null ? base.proxy : null;
        Profile b = (base == null || !base.isIsolated()) ? createAuto() : base;
        return new Profile(
                b.preset, b.timezone, b.locale, b.languages, b.acceptLanguage,
                randomMobileDevice().id, b.desktop, nextSeed(), b.webrtc, proxy
        );
    }

    public static DeviceSpec randomMobileDevice() {
        return MOBILE_DEVICES[RNG.nextInt(MOBILE_DEVICES.length)];
    }

    public static long nextSeed() {
        long s = RNG.nextInt() & 0x7fffffffL;
        return s == 0L ? 1337L : s;
    }

    public static DeviceSpec findDevice(String idOrModel) {
        if (idOrModel == null || idOrModel.trim().isEmpty()) return null;
        String q = idOrModel.trim();
        if (WIN_CHROME.id.equalsIgnoreCase(q)) return WIN_CHROME;
        if (MAC_CHROME.id.equalsIgnoreCase(q)) return MAC_CHROME;
        for (DeviceSpec d : MOBILE_DEVICES) {
            if (d.id.equalsIgnoreCase(q) || d.model.equalsIgnoreCase(q)) return d;
        }
        return null;
    }

    public static DeviceSpec resolveEffectiveDevice(Profile p, boolean effectiveDesktop) {
        if (p == null || !p.isIsolated()) return null;
        if (effectiveDesktop || p.desktop) {
            if (MAC_CHROME.id.equalsIgnoreCase(p.device)) return MAC_CHROME;
            if (WIN_CHROME.id.equalsIgnoreCase(p.device)) return WIN_CHROME;
            return (Math.abs(p.seed) & 1L) == 0L ? WIN_CHROME : MAC_CHROME;
        }
        DeviceSpec d = findDevice(p.device);
        if (d != null && d.mobile) return d;
        int idx = (int) (Math.abs(p.seed) % MOBILE_DEVICES.length);
        return MOBILE_DEVICES[idx];
    }

    private static String normalizeProxyType(String raw) {
        String t = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || "http".equals(t)) return "http";
        if ("socks5".equals(t) || "socks5h".equals(t)) return "socks5";
        throw new IllegalArgumentException("不支持的代理类型「" + sanitizeProxyInput(raw) + "」，仅支持 http 或 socks5");
    }

    static String sanitizeProxyInput(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        int schemeIdx = s.indexOf("://");
        String prefix = "";
        String rest = s;
        if (schemeIdx >= 0) {
            prefix = s.substring(0, schemeIdx + 3);
            rest = s.substring(schemeIdx + 3);
        }
        int atIdx = rest.lastIndexOf('@');
        if (atIdx >= 0) {
            return prefix + "***@" + rest.substring(atIdx + 1);
        }
        return s;
    }

    private static String safeProxyObjectSummary(JSONObject o) {
        if (o == null) return "";
        String type = sanitizeProxyInput(o.optString("type", "http"));
        String host = o.isNull("host") ? "" : sanitizeProxyInput(o.optString("host", "").trim());
        int port = o.optInt("port", 0);
        return "type=" + type + ", host=" + host + ", port=" + port;
    }

    private static String formatProxyHost(String host) {
        if (host == null) return "";
        if (host.contains(":") && !host.startsWith("[")) return "[" + host + "]";
        return host;
    }

    private static String sha256Hex(String input) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 容错百分号解码：保留字面 '+' 与非法 '%'，仅将合法 %XX UTF-8 序列还原 */
    private static String percentDecodeLenient(String raw) {
        if (raw == null || raw.indexOf('%') < 0) return raw == null ? "" : raw;
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[bytes.length];
        int o = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int hi = Character.digit((char) (bytes[i + 1] & 0xFF), 16);
                int lo = Character.digit((char) (bytes[i + 2] & 0xFF), 16);
                if (hi >= 0 && lo >= 0) {
                    out[o++] = (byte) ((hi << 4) | lo);
                    i += 2;
                    continue;
                }
            }
            out[o++] = bytes[i];
        }
        return new String(out, 0, o, StandardCharsets.UTF_8);
    }

    private static ProxyEndpoint parseProxyString(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty() || "direct".equalsIgnoreCase(s) || "none".equalsIgnoreCase(s)) return null;
        String safeRaw = sanitizeProxyInput(s);
        String type = "http";
        String rest = s;
        int schemeIdx = s.indexOf("://");
        if (schemeIdx >= 0) {
            type = normalizeProxyType(s.substring(0, schemeIdx));
            rest = s.substring(schemeIdx + 3);
        }
        if (rest.isEmpty()) throw new IllegalArgumentException("代理地址不能为空: " + safeRaw);

        String user = "";
        String pass = "";
        String hostPort = rest;
        int atIdx = rest.lastIndexOf('@');
        if (atIdx >= 0) {
            String userInfo = rest.substring(0, atIdx);
            hostPort = rest.substring(atIdx + 1);
            int colonIdx = userInfo.indexOf(':');
            if (colonIdx >= 0) {
                user = percentDecodeLenient(userInfo.substring(0, colonIdx));
                pass = percentDecodeLenient(userInfo.substring(colonIdx + 1));
            } else {
                user = percentDecodeLenient(userInfo);
            }
        }
        while (hostPort.endsWith("/")) {
            hostPort = hostPort.substring(0, hostPort.length() - 1);
        }
        int slashIdx = hostPort.indexOf('/');
        if (slashIdx >= 0) {
            throw new IllegalArgumentException("代理地址不能包含路径: " + safeRaw);
        }
        if (hostPort.isEmpty()) throw new IllegalArgumentException("代理缺少主机与端口: " + safeRaw);

        String host;
        int port;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close <= 1) throw new IllegalArgumentException("无效 IPv6 代理地址: " + safeRaw);
            host = hostPort.substring(1, close).trim();
            String tail = hostPort.substring(close + 1).trim();
            if (!tail.startsWith(":") || tail.length() <= 1) {
                throw new IllegalArgumentException("代理缺少端口: " + safeRaw);
            }
            port = parseProxyPort(tail.substring(1).trim(), safeRaw);
        } else {
            int firstColon = hostPort.indexOf(':');
            int lastColon = hostPort.lastIndexOf(':');
            if (firstColon != lastColon) {
                throw new IllegalArgumentException("IPv6 代理主机请用方括号括起，如 [::1]:1080: " + safeRaw);
            }
            if (lastColon <= 0 || lastColon == hostPort.length() - 1) {
                throw new IllegalArgumentException("代理格式应为 host:port: " + safeRaw);
            }
            host = hostPort.substring(0, lastColon).trim();
            port = parseProxyPort(hostPort.substring(lastColon + 1).trim(), safeRaw);
        }
        validateProxyHost(host, safeRaw);
        return new ProxyEndpoint(type, host, port, user, pass);
    }

    private static int parseProxyPort(String s, String safeRaw) {
        int port;
        try {
            port = Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无效代理端口: " + sanitizeProxyInput(safeRaw));
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("代理端口超出范围 (1..65535): " + port);
        }
        return port;
    }

    private static void validateProxyHost(String host, String safeRaw) {
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("代理主机不能为空: " + sanitizeProxyInput(safeRaw));
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (c <= ' ' || c == '/' || c == '\\' || c == '@') {
                throw new IllegalArgumentException("代理主机包含非法字符: " + sanitizeProxyInput(host));
            }
        }
    }

    public static ProxyEndpoint parseProxyEndpoint(Object spec) {
        if (spec == null || spec == JSONObject.NULL) return null;
        if (spec instanceof String) {
            return parseProxyString((String) spec);
        }
        if (!(spec instanceof JSONObject)) {
            throw new IllegalArgumentException("upstream 必须是代理字符串或 JSON 对象");
        }
        JSONObject o = (JSONObject) spec;
        String type = normalizeProxyType(o.optString("type", "http"));
        String host = o.isNull("host") ? "" : o.optString("host", "").trim();
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1).trim();
        }
        validateProxyHost(host, safeProxyObjectSummary(o));
        int port = o.optInt("port", 0);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("upstream 端口必须在 1..65535 之间");
        }
        String user = o.has("username") && !o.isNull("username") ? o.optString("username", "") : "";
        String pass = o.has("password") && !o.isNull("password") ? o.optString("password", "") : "";
        return new ProxyEndpoint(type, host, port, user, pass);
    }

    public static ProxySpec parseProxySpec(Object spec) {
        if (spec == null || spec == JSONObject.NULL) return null;
        if (spec instanceof String) {
            ProxyEndpoint ep = parseProxyString((String) spec);
            if (ep == null) return null;
            return new ProxySpec(true, ep.type, ep.host, ep.port, ep.username, ep.password, null);
        }
        if (!(spec instanceof JSONObject)) {
            throw new IllegalArgumentException("proxy 必须是代理字符串、null 或 JSON 对象");
        }
        JSONObject o = (JSONObject) spec;
        boolean enabled = !o.has("enabled") || o.optBoolean("enabled", true);
        String type = normalizeProxyType(o.optString("type", "http"));
        String host = o.isNull("host") ? "" : o.optString("host", "").trim();
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            host = host.substring(1, host.length() - 1).trim();
        }
        int port = o.optInt("port", 0);
        if (enabled) {
            validateProxyHost(host, safeProxyObjectSummary(o));
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("代理端口必须在 1..65535 之间");
            }
        } else {
            if (host.isEmpty() && port == 0 && !o.has("upstream")) {
                return null;
            }
            if (!host.isEmpty()) validateProxyHost(host, safeProxyObjectSummary(o));
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("代理端口必须在 1..65535 之间");
            }
        }
        String user = o.has("username") && !o.isNull("username") ? o.optString("username", "") : "";
        String pass = o.has("password") && !o.isNull("password") ? o.optString("password", "") : "";
        ProxyEndpoint upstream = o.has("upstream") && !o.isNull("upstream")
                ? parseProxyEndpoint(o.opt("upstream"))
                : null;
        return new ProxySpec(enabled, type, host, port, user, pass, upstream);
    }

    /** 解析外部输入（预设名字符串 或 JSONObject） */
    public static Profile fromSpec(Object spec, Profile base) {
        if (spec == null || spec == JSONObject.NULL) {
            return base != null ? base : createAuto();
        }
        if (spec instanceof String) {
            String ps = ((String) spec).trim().toLowerCase(Locale.ROOT);
            if (ps.isEmpty()) return base != null ? base : createAuto();
            ProxySpec keepProxy = base != null ? base.proxy : null;
            if (PRESET_NATIVE.equals(ps)) {
                return keepProxy == null ? NATIVE : new Profile(PRESET_NATIVE, "", "", Collections.emptyList(), "", "", false, 0L, WEBRTC_NATIVE, keepProxy);
            }
            PresetDef def = PRESETS.get(ps);
            if (def == null) {
                throw new IllegalArgumentException("未知环境预设「" + spec + "」，可选：" + String.join(", ", PRESETS.keySet()));
            }
            String dev = (base != null && !base.device.isEmpty()) ? base.device : randomMobileDevice().id;
            long seed = (base != null && base.seed != 0L) ? base.seed : nextSeed();
            boolean desktop = base != null && base.desktop;
            String webrtc = (base != null && base.isIsolated()) ? base.webrtc : WEBRTC_SANITIZE;
            return new Profile(def.id, def.timezone, def.locale, def.languages, def.acceptLanguage, dev, desktop, seed, webrtc, keepProxy);
        }
        if (!(spec instanceof JSONObject)) {
            throw new IllegalArgumentException("profile 必须是预设名字符串或 JSON 对象");
        }
        JSONObject o = (JSONObject) spec;
        ProxySpec proxy = o.has("proxy") ? parseProxySpec(o.opt("proxy")) : (base != null ? base.proxy : null);
        String presetReq = o.has("preset") && !o.isNull("preset") ? o.optString("preset", "").trim().toLowerCase(Locale.ROOT) : "";
        if (PRESET_NATIVE.equals(presetReq) && !hasAnyOverrideField(o)) {
            return proxy == null ? NATIVE : new Profile(PRESET_NATIVE, "", "", Collections.emptyList(), "", "", false, 0L, WEBRTC_NATIVE, proxy);
        }
        if (PRESET_NATIVE.equals(presetReq) && !hasFingerprintOverrideField(o)) {
            return proxy == null ? NATIVE : new Profile(PRESET_NATIVE, "", "", Collections.emptyList(), "", "", false, 0L, WEBRTC_NATIVE, proxy);
        }
        if (presetReq.isEmpty() && !hasFingerprintOverrideField(o) && base != null && PRESET_NATIVE.equals(base.preset)) {
            return proxy == null ? NATIVE : new Profile(PRESET_NATIVE, "", "", Collections.emptyList(), "", "", false, 0L, WEBRTC_NATIVE, proxy);
        }
        Profile seedBase;
        if (!presetReq.isEmpty()) {
            if (PRESET_NATIVE.equals(presetReq)) {
                seedBase = NATIVE;
            } else if (PRESET_CUSTOM.equals(presetReq)) {
                seedBase = (base != null && base.isIsolated()) ? base : createAuto();
            } else {
                PresetDef def = PRESETS.get(presetReq);
                if (def == null) {
                    throw new IllegalArgumentException("未知环境预设「" + presetReq + "」，可选：" + String.join(", ", PRESETS.keySet()));
                }
                String dev = (base != null && !base.device.isEmpty()) ? base.device : randomMobileDevice().id;
                long seed = (base != null && base.seed != 0L) ? base.seed : nextSeed();
                boolean desktop = base != null && base.desktop;
                String webrtc = (base != null && base.isIsolated()) ? base.webrtc : WEBRTC_SANITIZE;
                seedBase = new Profile(def.id, def.timezone, def.locale, def.languages, def.acceptLanguage, dev, desktop, seed, webrtc, proxy);
            }
        } else if (base != null && base.isIsolated()) {
            seedBase = base;
        } else {
            seedBase = createAuto();
        }

        String tz;
        if (o.has("timezone")) {
            String rawTz = o.isNull("timezone") ? "" : o.optString("timezone", "").trim();
            String normTz = normalizeTimezone(rawTz);
            if (normTz == null) {
                throw new IllegalArgumentException("无效时区「" + rawTz + "」，请使用合法 IANA 时区（如 America/Toronto）");
            }
            tz = normTz;
        } else {
            tz = seedBase.timezone;
        }
        String loc = o.has("locale") && !o.isNull("locale") ? o.optString("locale", "").trim() : seedBase.locale;
        List<String> langs = seedBase.languages;
        if (o.has("languages")) {
            langs = parseLanguages(o.opt("languages"));
        } else if (o.has("locale") && !loc.isEmpty() && !loc.equals(seedBase.locale)) {
            langs = deriveLanguages(loc);
        }
        String acceptLang = seedBase.acceptLanguage;
        if (o.has("acceptLanguage")) {
            acceptLang = o.optString("acceptLanguage", "").trim();
        } else if ((o.has("locale") || o.has("languages")) && !langs.isEmpty()) {
            acceptLang = buildAcceptLanguage(langs);
        } else if (o.has("locale") && loc.isEmpty()) {
            acceptLang = "";
        }

        String dev = o.has("device") ? o.optString("device", "").trim() : seedBase.device;
        if (!dev.isEmpty()) {
            DeviceSpec ds = findDevice(dev);
            if (ds == null) throw new IllegalArgumentException("未知设备型号「" + dev + "」");
            dev = ds.id;
        } else {
            dev = randomMobileDevice().id;
        }

        boolean desktop = o.has("desktop") ? o.optBoolean("desktop", false) : seedBase.desktop;
        long seed = o.has("seed") ? o.optLong("seed", 0L) : seedBase.seed;
        if (seed == 0L) seed = nextSeed();

        String webrtc = o.has("webrtc") ? o.optString("webrtc", WEBRTC_SANITIZE).trim().toLowerCase(Locale.ROOT) : seedBase.webrtc;
        if (!WEBRTC_SANITIZE.equals(webrtc) && !WEBRTC_DISABLED.equals(webrtc) && !WEBRTC_NATIVE.equals(webrtc)) {
            throw new IllegalArgumentException("webrtc 只能是 sanitize / disabled / native");
        }

        String finalPreset = !presetReq.isEmpty() ? presetReq : seedBase.preset;
        if (PRESET_NATIVE.equals(finalPreset) && hasFingerprintOverrideField(o)) {
            finalPreset = PRESET_CUSTOM;
        }
        return new Profile(finalPreset, tz, loc, langs, acceptLang, dev, desktop, seed, webrtc, proxy);
    }

    private static boolean hasFingerprintOverrideField(JSONObject o) {
        return o.has("timezone") || o.has("locale") || o.has("languages")
                || o.has("acceptLanguage") || o.has("device") || o.has("desktop")
                || o.has("seed") || o.has("webrtc");
    }

    private static boolean hasAnyOverrideField(JSONObject o) {
        return hasFingerprintOverrideField(o) || o.has("proxy");
    }

    private static List<String> parseLanguages(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof JSONArray) {
            JSONArray a = (JSONArray) raw;
            for (int i = 0; i < a.length(); i++) {
                String s = a.optString(i, "").trim();
                if (!s.isEmpty() && !out.contains(s)) out.add(s);
            }
        } else if (raw instanceof String) {
            for (String s : ((String) raw).split("[,\\s]+")) {
                String t = s.trim();
                if (!t.isEmpty() && !out.contains(t)) out.add(t);
            }
        }
        return out;
    }

    private static List<String> deriveLanguages(String locale) {
        List<String> out = new ArrayList<>();
        if (locale == null || locale.isEmpty()) return out;
        out.add(locale);
        int dash = locale.indexOf('-');
        if (dash > 0) {
            String base = locale.substring(0, dash);
            if (!out.contains(base)) out.add(base);
        }
        if (!"en".equalsIgnoreCase(locale) && !locale.toLowerCase(Locale.ROOT).startsWith("en-")) {
            out.add("en-US");
        }
        return out;
    }

    private static String buildAcceptLanguage(List<String> langs) {
        if (langs == null || langs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < langs.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(langs.get(i));
            if (i > 0) {
                double q = Math.max(0.5, 1.0 - i * 0.1);
                sb.append(String.format(Locale.US, ";q=%.1f", q));
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ JSON 序列化

    public static JSONObject toJson(Profile p) {
        Profile prof = p == null ? NATIVE : p;
        JSONObject o = new JSONObject();
        try {
            o.put("preset", prof.preset);
            if (prof.isIsolated()) {
                o.put("timezone", prof.timezone);
                o.put("locale", prof.locale);
                o.put("languages", new JSONArray(prof.languages));
                o.put("acceptLanguage", prof.acceptLanguage);
                o.put("device", prof.device);
                o.put("desktop", prof.desktop);
                o.put("seed", prof.seed);
                o.put("webrtc", prof.webrtc);
            }
            if (prof.proxy != null) {
                o.put("proxy", prof.proxy.toStorageJson());
            }
        } catch (Exception ignored) {}
        return o;
    }

    public static JSONObject toSummaryJson(Profile p) {
        Profile prof = p == null ? NATIVE : p;
        JSONObject o = new JSONObject();
        try {
            o.put("preset", prof.preset);
            if (prof.isIsolated()) {
                o.put("timezone", prof.timezone);
                o.put("locale", prof.locale);
                o.put("languages", new JSONArray(prof.languages));
                o.put("acceptLanguage", prof.acceptLanguage);
                o.put("device", prof.device);
                o.put("desktop", prof.desktop);
                o.put("seed", prof.seed);
                o.put("webrtc", prof.webrtc);
            }
            o.put("isolated", prof.isIsolated());
            o.put("badge", badgeEmoji(prof));
            o.put("summary", summary(prof));
            o.put("proxy", prof.proxy != null ? prof.proxy.toSummaryJson() : JSONObject.NULL);
            o.put("proxySignature", prof.proxySignature());
        } catch (Exception ignored) {}
        return o;
    }

    public static Profile fromJson(JSONObject o) {
        if (o == null) return NATIVE;
        ProxySpec proxy = null;
        if (o.has("proxy") && !o.isNull("proxy")) {
            try {
                proxy = parseProxySpec(o.opt("proxy"));
            } catch (Throwable ignored) {}
        }
        String preset = o.optString("preset", PRESET_NATIVE).trim().toLowerCase(Locale.ROOT);
        if (preset.isEmpty() || PRESET_NATIVE.equals(preset)) {
            if (proxy == null) return NATIVE;
            return new Profile(PRESET_NATIVE, "", "", Collections.emptyList(), "", "", false, 0L, WEBRTC_NATIVE, proxy);
        }
        PresetDef def = PRESETS.get(preset);
        String tz;
        if (o.has("timezone") && !o.isNull("timezone")) {
            String normTz = normalizeTimezone(o.optString("timezone", ""));
            tz = normTz != null ? normTz : (def != null ? def.timezone : "");
        } else {
            tz = def != null ? def.timezone : "";
        }
        String loc = o.has("locale") && !o.isNull("locale") ? o.optString("locale", "") : (def != null ? def.locale : "");
        List<String> langs = o.has("languages") ? parseLanguages(o.opt("languages")) : (def != null ? def.languages : Collections.emptyList());
        String al = o.has("acceptLanguage") ? o.optString("acceptLanguage", "") : (def != null ? def.acceptLanguage : "");
        String dev = o.optString("device", "");
        if (dev.isEmpty() || findDevice(dev) == null) dev = randomMobileDevice().id;
        boolean desktop = o.optBoolean("desktop", false);
        long seed = o.optLong("seed", 0L);
        if (seed == 0L) seed = nextSeed();
        String webrtc = o.optString("webrtc", WEBRTC_SANITIZE);
        if (!WEBRTC_SANITIZE.equals(webrtc) && !WEBRTC_DISABLED.equals(webrtc) && !WEBRTC_NATIVE.equals(webrtc)) {
            webrtc = WEBRTC_SANITIZE;
        }
        return new Profile(preset, tz, loc, langs, al, dev, desktop, seed, webrtc, proxy);
    }

    // ------------------------------------------------------------------ 摘要 / UI

    public static String badgeEmoji(Profile p) {
        if (p == null || !p.isIsolated()) return "🔓";
        PresetDef d = PRESETS.get(p.preset);
        if (d != null) return d.emoji;
        if (p.timezone.startsWith("America/Toronto")) return "🇨🇦";
        if (p.timezone.startsWith("America/")) return "🇺🇸";
        if (p.timezone.startsWith("Europe/London")) return "🇬🇧";
        if (p.timezone.startsWith("Australia/")) return "🇦🇺";
        if (p.timezone.startsWith("Asia/Tokyo")) return "🇯🇵";
        if (p.timezone.startsWith("Asia/Shanghai")) return "🇨🇳";
        return "🛡";
    }

    public static String summary(Profile p) {
        if (p == null || !p.isIsolated()) return "🔓 原生";
        String emoji = badgeEmoji(p);
        String region;
        if (!p.timezone.isEmpty()) {
            int slash = p.timezone.lastIndexOf('/');
            region = (slash >= 0 ? p.timezone.substring(slash + 1) : p.timezone).replace('_', ' ');
        } else {
            region = "隔离";
        }
        DeviceSpec dev = resolveEffectiveDevice(p, p.desktop);
        String devName;
        if (dev == null) devName = "WebView";
        else if (!dev.mobile) devName = MAC_CHROME.id.equals(dev.id) ? "macOS" : "Windows";
        else devName = dev.model;
        return emoji + " " + region + " · " + devName;
    }

    public static Map<String, String> extraHeaders(Profile p) {
        if (p == null || !p.isIsolated() || p.acceptLanguage == null || p.acceptLanguage.isEmpty()) {
            return null;
        }
        return Collections.singletonMap("Accept-Language", p.acceptLanguage);
    }

    // ------------------------------------------------------------------ Chromium 版本 & GREASE Brand 嗅探

    public static String[] resolveChromeVersion(Context ctx) {
        String f = sChromeFullVer;
        String m = sChromeMajorVer;
        if (f != null && m != null) return new String[]{f, m};

        String full = null;
        if (ctx != null) {
            try {
                PackageInfo pkg = WebViewCompat.getCurrentWebViewPackage(ctx);
                if (pkg != null && pkg.versionName != null) {
                    Matcher pm = PKG_VER_RE.matcher(pkg.versionName.trim());
                    if (pm.find()) {
                        full = pm.group(0);
                    }
                }
            } catch (Throwable ignored) {}
            if (full == null) {
                try {
                    String defUa = WebSettings.getDefaultUserAgent(ctx);
                    if (defUa != null) {
                        Matcher um = CHROME_VER_RE.matcher(defUa);
                        if (um.find()) full = um.group(1);
                    }
                } catch (Throwable ignored) {}
            }
        }
        if (full == null || full.isEmpty()) full = "131.0.0.0";
        int dot = full.indexOf('.');
        String major = dot > 0 ? full.substring(0, dot) : full;
        sChromeFullVer = full;
        sChromeMajorVer = major;
        return new String[]{full, major};
    }

    @SuppressLint("RequiresFeature")
    public static String[] resolveGreaseBrand(WebSettings ws) {
        String gb = sGreaseBrand, gm = sGreaseMajor, gf = sGreaseFull;
        if (gb != null && gm != null && gf != null) return new String[]{gb, gm, gf};

        String brand = "Not(A:Brand";
        String major = "99";
        String full = "99.0.0.0";
        if (ws != null) {
            try {
                if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                    UserAgentMetadata meta = WebSettingsCompat.getUserAgentMetadata(ws);
                    if (meta != null && meta.getBrandVersionList() != null) {
                        for (UserAgentMetadata.BrandVersion bv : meta.getBrandVersionList()) {
                            if (bv == null || bv.getBrand() == null) continue;
                            String b = bv.getBrand();
                            if (!"Android WebView".equalsIgnoreCase(b)
                                    && !"Chromium".equalsIgnoreCase(b)
                                    && !"Google Chrome".equalsIgnoreCase(b)) {
                                brand = b;
                                major = bv.getMajorVersion() != null && !bv.getMajorVersion().isEmpty() ? bv.getMajorVersion() : "99";
                                full = bv.getFullVersion() != null && !bv.getFullVersion().isEmpty() ? bv.getFullVersion() : (major + ".0.0.0");
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        sGreaseBrand = brand;
        sGreaseMajor = major;
        sGreaseFull = full;
        return new String[]{brand, major, full};
    }

    // ------------------------------------------------------------------ UA & Client Hints 构造与写入

    public static String buildEffectiveUa(Context ctx, Profile p, boolean effectiveDesktop, String overrideUa) {
        if (overrideUa != null && !overrideUa.trim().isEmpty()) {
            return overrideUa.trim();
        }
        String[] ver = resolveChromeVersion(ctx);
        String fullVer = ver[0];
        DeviceSpec dev = resolveEffectiveDevice(p, effectiveDesktop);
        if (dev != null && !dev.mobile) {
            if (MAC_CHROME.id.equals(dev.id)) {
                return "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + fullVer + " Safari/537.36";
            }
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + fullVer + " Safari/537.36";
        }
        String osVer = dev != null ? dev.androidVer : "14";
        String model = dev != null ? dev.model : "Pixel 8";
        return "Mozilla/5.0 (Linux; Android " + osVer + "; " + model + ") AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + fullVer + " Mobile Safari/537.36";
    }

    @SuppressLint("RequiresFeature")
    public static void applyToWebSettings(Context ctx, WebSettings ws, Profile p, boolean effectiveDesktop, String overrideUa) {
        if (ws == null || p == null || !p.isIsolated()) return;
        try {
            // 先从原生 WebSettings 嗅探 GREASE Brand，再覆盖 UA 与 Metadata
            String[] grease = resolveGreaseBrand(ws);
            String[] ver = resolveChromeVersion(ctx);
            String fullVer = ver[0];
            String majorVer = ver[1];
            String effectiveUa = buildEffectiveUa(ctx, p, effectiveDesktop, overrideUa);

            ws.setUseWideViewPort(true);
            ws.setLoadWithOverviewMode(true);
            ws.setUserAgentString(effectiveUa);

            if (WebViewFeature.isFeatureSupported(WebViewFeature.USER_AGENT_METADATA)) {
                DeviceSpec dev = resolveEffectiveDevice(p, effectiveDesktop);
                boolean mobile = dev == null || dev.mobile;
                String chPlatform = dev != null ? dev.chPlatform : "Android";
                String chPlatformVer = dev != null ? dev.chPlatformVer : "14.0.0";
                String chArch = dev != null ? dev.chArch : "arm";
                int chBitness = dev != null ? dev.chBitness : 64;
                String model = dev != null ? dev.model : "Pixel 8";

                if (overrideUa != null && !overrideUa.trim().isEmpty()) {
                    String low = overrideUa.toLowerCase(Locale.ROOT);
                    if (low.contains("windows")) {
                        mobile = false; chPlatform = "Windows"; chPlatformVer = "15.0.0"; chArch = "x86"; model = "";
                    } else if (low.contains("macintosh") || low.contains("mac os x")) {
                        mobile = false; chPlatform = "macOS"; chPlatformVer = "14.5.0"; chArch = "arm"; model = "";
                    } else if (low.contains("x11; linux") || low.contains("x86_64")) {
                        mobile = false; chPlatform = "Linux"; chPlatformVer = "6.5.0"; chArch = "x86"; model = "";
                    } else if (low.contains("android")) {
                        mobile = true; chPlatform = "Android";
                    }
                }

                List<UserAgentMetadata.BrandVersion> brands = Arrays.asList(
                        new UserAgentMetadata.BrandVersion.Builder()
                                .setBrand(grease[0]).setMajorVersion(grease[1]).setFullVersion(grease[2]).build(),
                        new UserAgentMetadata.BrandVersion.Builder()
                                .setBrand("Chromium").setMajorVersion(majorVer).setFullVersion(fullVer).build(),
                        new UserAgentMetadata.BrandVersion.Builder()
                                .setBrand("Google Chrome").setMajorVersion(majorVer).setFullVersion(fullVer).build()
                );

                UserAgentMetadata meta = new UserAgentMetadata.Builder()
                        .setBrandVersionList(brands)
                        .setFullVersion(fullVer)
                        .setPlatform(chPlatform)
                        .setPlatformVersion(chPlatformVer)
                        .setArchitecture(chArch)
                        .setBitness(chBitness)
                        .setModel(model)
                        .setMobile(mobile)
                        .setWow64(false)
                        .build();
                WebSettingsCompat.setUserAgentMetadata(ws, meta);
            }

            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(ws, Collections.emptySet());
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyToWebSettings failed", t);
        }
    }
}
