package com.hanwenjiang.uhfrfid;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

/**
 * 配置项：云端地址 / key / 使用者 / 是否读完自动上传。
 *
 * <p>默认值是编译进来的（{@link BuildConfig}），所以装完就能用，不用先手工填。
 * 设置里改过的值优先；想回到编译时的默认值，把对应项清空即可。
 */
public final class Prefs {

    private static final String FILE = "uhf_rfid";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    private static String or(String value, String fallback) {
        return (value == null || value.trim().isEmpty()) ? fallback : value.trim();
    }

    // ------------------------------------------------------------------ 云端

    /** Worker 地址。 */
    public static String base(Context c) {
        return or(sp(c).getString("cloud_base", ""), BuildConfig.DEFAULT_CLOUD_BASE);
    }

    public static void setBase(Context c, String v) {
        sp(c).edit().putString("cloud_base", v == null ? "" : v.trim()).apply();
    }

    /** API key。没设置过就用编译进来的默认值。 */
    public static String apiKey(Context c) {
        return or(sp(c).getString("cloud_key", ""), BuildConfig.DEFAULT_API_KEY);
    }

    public static void setApiKey(Context c, String v) {
        sp(c).edit().putString("cloud_key", v == null ? "" : v.trim()).apply();
    }

    /**
     * 谁在用这台手机 —— 几个人共用一套库时用来区分上传者。
     *
     * <p>默认取手机自己的名字：优先系统里那个用户可见的设备名
     * （{@code Settings.Global.DEVICE_NAME}，就是「设置 → 关于手机 → 设备名称」），
     * 取不到就退回型号，例如 {@code V2364A}。
     */
    public static String operator(Context c) {
        String mine = sp(c).getString("operator", "");
        if (mine != null && !mine.trim().isEmpty()) return mine.trim();
        return deviceName(c);
    }

    public static void setOperator(Context c, String v) {
        sp(c).edit().putString("operator", v == null ? "" : v.trim()).apply();
    }

    /** 手机名：设备名 → 型号 → "Android"。 */
    public static String deviceName(Context c) {
        try {
            String n = Settings.Global.getString(c.getContentResolver(), "device_name");
            if (n != null && !n.trim().isEmpty()) return n.trim();
        } catch (Exception ignored) {
            // 有些 ROM 不给读，忽略
        }
        String model = Build.MODEL;
        return (model == null || model.trim().isEmpty()) ? "Android" : model.trim();
    }

    /** 使用者是不是用户显式设过的（用来在界面上区分"默认"和"自定义"）。 */
    public static boolean operatorIsCustom(Context c) {
        String v = sp(c).getString("operator", "");
        return v != null && !v.trim().isEmpty();
    }

    // ------------------------------------------------------------------ 行为

    /** 读完标签自动上传。默认开。 */
    public static boolean autoUpload(Context c) {
        return sp(c).getBoolean("auto_upload", true);
    }

    public static void setAutoUpload(Context c, boolean v) {
        sp(c).edit().putBoolean("auto_upload", v).apply();
    }

    /** 有没有可用的 key（编译进来或用户填的）。 */
    public static boolean isConfigured(Context c) {
        return !apiKey(c).isEmpty();
    }

    public static CloudClient client(Context c) {
        return new CloudClient(base(c), apiKey(c), operator(c));
    }
}
