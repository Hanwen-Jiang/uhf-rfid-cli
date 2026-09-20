package com.hanwenjiang.uhfrfid;

import android.content.Context;
import android.content.SharedPreferences;

/** 云端配置：地址、API key、谁在用（operator）。 */
public final class Prefs {

    private static final String FILE = "uhf_rfid";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String base(Context c) {
        return sp(c).getString("cloud_base", CloudClient.DEFAULT_BASE);
    }

    public static void setBase(Context c, String v) {
        sp(c).edit().putString("cloud_base", v == null ? "" : v.trim()).apply();
    }

    public static String apiKey(Context c) {
        return sp(c).getString("cloud_key", "");
    }

    public static void setApiKey(Context c, String v) {
        sp(c).edit().putString("cloud_key", v == null ? "" : v.trim()).apply();
    }

    /** 谁在用这台手机 —— 几个人共用一套库时用来区分上传者。 */
    public static String operator(Context c) {
        return sp(c).getString("operator", "");
    }

    public static void setOperator(Context c, String v) {
        sp(c).edit().putString("operator", v == null ? "" : v.trim()).apply();
    }

    public static boolean isConfigured(Context c) {
        return !apiKey(c).isEmpty();
    }

    public static CloudClient client(Context c) {
        return new CloudClient(base(c), apiKey(c), operator(c));
    }
}
