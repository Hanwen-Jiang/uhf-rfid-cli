package com.hanwenjiang.uhfrfid;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 云端标签库的客户端（Cloudflare Worker + D1）。
 *
 * <p>用标准库的 {@link HttpURLConnection}，不引第三方 HTTP 库——这个接口很简单，
 * 少一个依赖少一份麻烦。
 *
 * <p><b>一定要带 User-Agent</b>：Cloudflare 会把默认的 Java/urllib UA 直接 403。
 */
public class CloudClient {

    /** 默认的 Worker 地址，可以在设置里改。 */
    public static final String DEFAULT_BASE = "https://rfid-tags.root-60a.workers.dev";

    private final String base;
    private final String apiKey;
    private final String operator;

    public CloudClient(String base, String apiKey, String operator) {
        String b = (base == null || base.trim().isEmpty()) ? DEFAULT_BASE : base.trim();
        this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.operator = operator == null ? "" : operator.trim();
    }

    /** 云端的一条标签记录。 */
    public static final class Tag {
        public long id;
        public String epc;
        public String tid;
        public String name;
        public String status;
        public String anomalyNote;
        public String operator;
        public int reads;
        public String suffix;
        public String createdAt;
    }

    /** 上传结果。 */
    public static final class UploadResult {
        public Tag tag;
        public boolean created;
        public String status;          // ok / anomaly
        public boolean complete;       // 服务端的校验结论
        public String verdictDetail;
    }

    // ------------------------------------------------------------------ 底层

    private JSONObject request(String method, String path, JSONObject body)
            throws IOException {
        if (apiKey.isEmpty()) {
            throw new IOException("还没有设置云端 API key（点右上角「设置」填）");
        }
        HttpURLConnection c = (HttpURLConnection) new URL(base + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "uhf-rfid-android/1.0");
            c.setRequestProperty("Authorization", "Bearer " + apiKey);
            c.setRequestProperty("Accept", "application/json");
            if (body != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] raw = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(raw);
                }
            }
            int code = c.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? c.getInputStream() : c.getErrorStream();
            String text = readAll(in);
            if (code == 401) throw new IOException("云端拒绝：API key 不对");
            if (code < 200 || code >= 300) {
                throw new IOException("云端返回 " + code + "：" + text);
            }
            try {
                return text.isEmpty() ? new JSONObject() : new JSONObject(text);
            } catch (org.json.JSONException e) {
                // JSONException 是受检异常，这里统一转成 IOException，
                // 上层只需要处理一种异常
                throw new IOException("云端返回的不是合法 JSON：" + text, e);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        in.close();
        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    }

    private static Tag toTag(JSONObject o) {
        Tag t = new Tag();
        t.id = o.optLong("id");
        t.epc = o.optString("epc", null);
        t.tid = o.optString("tid", null);
        t.name = o.isNull("name") ? null : o.optString("name", null);
        t.status = o.optString("status", "ok");
        t.anomalyNote = o.isNull("anomalyNote") ? null : o.optString("anomalyNote", null);
        t.operator = o.isNull("operator") ? null : o.optString("operator", null);
        t.reads = o.optInt("reads", 1);
        t.suffix = o.optString("suffix", "");
        t.createdAt = o.optString("createdAt", "");
        return t;
    }

    // ------------------------------------------------------------------ 接口

    /** 存活探测，同时能确认 key 对不对。 */
    public String health() throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(base + "/health").openConnection();
        try {
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "uhf-rfid-android/1.0");
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    public List<Tag> list(boolean onlyUnnamed) throws IOException {
        JSONObject o = request("GET", "/api/tags" + (onlyUnnamed ? "?unnamed=1" : ""), null);
        JSONArray arr = o.optJSONArray("tags");
        List<Tag> out = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) out.add(toTag(arr.optJSONObject(i)));
        }
        return out;
    }

    /**
     * 上传一颗标签。
     *
     * @param anomalyNote 破例上传时说明原因（连续两次读数相同但不合格），正常时传 null
     */
    public UploadResult upload(String epc, String tid, String anomalyNote) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("epc", epc);
            body.put("tid", tid);
            if (anomalyNote != null && !anomalyNote.isEmpty()) {
                body.put("status", "anomaly");
                body.put("anomalyNote", anomalyNote);
            }
            if (!operator.isEmpty()) body.put("operator", operator);
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
        JSONObject o = request("POST", "/api/tags", body);
        UploadResult r = new UploadResult();
        JSONObject tag = o.optJSONObject("tag");
        if (tag != null) r.tag = toTag(tag);
        r.created = o.optBoolean("created", false);
        r.status = o.optString("status", "ok");
        JSONObject v = o.optJSONObject("verdict");
        if (v != null) {
            r.complete = v.optBoolean("complete", false);
            r.verdictDetail = v.isNull("detail") ? null : v.optString("detail", null);
        }
        return r;
    }

    public Tag rename(long id, String name) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("name", name == null ? "" : name);
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        }
        JSONObject o = request("PATCH", "/api/tags/" + id, body);
        JSONObject tag = o.optJSONObject("tag");
        return tag == null ? null : toTag(tag);
    }

    public void delete(long id) throws IOException {
        request("DELETE", "/api/tags/" + id, null);
    }

    /** 让 URL 里的值安全（EPC/TID 是 hex，其实用不到，留着以防万一）。 */
    static String enc(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
