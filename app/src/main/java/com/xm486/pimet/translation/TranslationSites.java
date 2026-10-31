package com.xm486.pimet.translation;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 站点规则：对某网站「总是翻译 / 从不翻译」（按主机名，子域名继承上级规则） */
public final class TranslationSites {

    public static final int RULE_NONE = 0;
    public static final int RULE_ALWAYS = 1;
    public static final int RULE_NEVER = 2;

    private static final String KEY_ALWAYS = "site_always";
    private static final String KEY_NEVER = "site_never";

    private TranslationSites() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(TranslationEngines.PREF, Context.MODE_PRIVATE);
    }

    /** url → 小写主机名（去掉 www.）；不是 http(s) 返回 null */
    public static String hostOf(String url) {
        if (TextUtils.isEmpty(url)) return null;
        try {
            Uri u = Uri.parse(url);
            String scheme = u.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
            String h = u.getHost();
            if (TextUtils.isEmpty(h)) return null;
            h = h.toLowerCase(Locale.ROOT);
            if (h.startsWith("www.")) h = h.substring(4);
            return h;
        } catch (Throwable t) {
            return null;
        }
    }

    public static int ruleFor(Context context, String host) {
        if (TextUtils.isEmpty(host)) return RULE_NONE;
        Set<String> never = prefs(context).getStringSet(KEY_NEVER, new HashSet<>());
        Set<String> always = prefs(context).getStringSet(KEY_ALWAYS, new HashSet<>());
        String h = host;
        while (true) {
            if (never.contains(h)) return RULE_NEVER;
            if (always.contains(h)) return RULE_ALWAYS;
            int dot = h.indexOf('.');
            if (dot < 0 || h.indexOf('.', dot + 1) < 0) return RULE_NONE;
            h = h.substring(dot + 1);
        }
    }

    /** 只看这个主机名本身的规则（设置页用） */
    public static int exactRule(Context context, String host) {
        if (TextUtils.isEmpty(host)) return RULE_NONE;
        if (prefs(context).getStringSet(KEY_NEVER, new HashSet<>()).contains(host)) return RULE_NEVER;
        if (prefs(context).getStringSet(KEY_ALWAYS, new HashSet<>()).contains(host)) return RULE_ALWAYS;
        return RULE_NONE;
    }

    public static void setRule(Context context, String host, int rule) {
        if (TextUtils.isEmpty(host)) return;
        Set<String> always = new HashSet<>(prefs(context).getStringSet(KEY_ALWAYS, new HashSet<>()));
        Set<String> never = new HashSet<>(prefs(context).getStringSet(KEY_NEVER, new HashSet<>()));
        always.remove(host);
        never.remove(host);
        if (rule == RULE_ALWAYS) always.add(host);
        else if (rule == RULE_NEVER) never.add(host);
        prefs(context).edit().putStringSet(KEY_ALWAYS, always).putStringSet(KEY_NEVER, never).apply();
    }

    public static int ruleCount(Context context) {
        return prefs(context).getStringSet(KEY_ALWAYS, new HashSet<>()).size()
                + prefs(context).getStringSet(KEY_NEVER, new HashSet<>()).size();
    }

    public static void clearAll(Context context) {
        prefs(context).edit().remove(KEY_ALWAYS).remove(KEY_NEVER).apply();
    }
}
