package com.xm486.pimet;

import android.content.Context;
import android.webkit.CookieManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Pi-Web 本地 HTTP 客户端
 * 基于 HttpURLConnection 的轻量同步客户端，供后台线程调用。
 * 端口动态读取自 PiMetConfig，超时设为 8 秒，支持 Cookie 鉴权及 JSON 解析。
 */
public final class PiWebApi {

    public static final int TIMEOUT_MS = 8000;

    private PiWebApi() {}

    public static int getPort(Context context) {
        return PiMetConfig.getWebPort(context);
    }

    public static String getBaseUrl(Context context) {
        return "http://127.0.0.1:" + getPort(context);
    }

    public static class ApiException extends Exception {
        public final int statusCode;
        public final JSONObject errorJson;

        public ApiException(int statusCode, String message, JSONObject errorJson) {
            super(message);
            this.statusCode = statusCode;
            this.errorJson = errorJson;
        }
    }

    public static class Response {
        public final int statusCode;
        public final String rawBody;

        public Response(int statusCode, String rawBody) {
            this.statusCode = statusCode;
            this.rawBody = rawBody != null ? rawBody : "";
        }

        public boolean isSuccess() {
            return statusCode >= 200 && statusCode < 300;
        }

        public JSONObject asJsonObject() throws JSONException {
            if (rawBody.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(rawBody);
        }

        public JSONArray asJsonArray() throws JSONException {
            if (rawBody.trim().isEmpty()) {
                return new JSONArray();
            }
            return new JSONArray(rawBody);
        }
    }

    public static Response request(Context context, String method, String path, Object body) throws Exception {
        String baseUrl = getBaseUrl(context);
        String fullUrl = path.startsWith("http://") || path.startsWith("https://")
                ? path
                : (baseUrl + (path.startsWith("/") ? path : ("/" + path)));

        URL url = new URL(fullUrl);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(TIMEOUT_MS);
        conn.setReadTimeout(TIMEOUT_MS);
        conn.setInstanceFollowRedirects(false);

        String upperMethod = method != null ? method.toUpperCase() : "GET";
        setRequestMethod(conn, upperMethod);

        conn.setRequestProperty("Accept", "application/json, text/plain, */*");

        // 共享 WebView 全局登录 Cookie
        try {
            CookieManager cm = CookieManager.getInstance();
            if (cm != null) {
                String cookie = cm.getCookie(fullUrl);
                if (cookie != null && !cookie.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookie);
                }
            }
        } catch (Throwable ignored) {}

        // 发送 Body
        if (body != null && !"GET".equals(upperMethod) && !"HEAD".equals(upperMethod)) {
            byte[] bytes;
            if (body instanceof JSONObject || body instanceof JSONArray) {
                bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            } else if (body instanceof byte[]) {
                bytes = (byte[]) body;
            } else {
                bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            }

            try {
                conn.setDoOutput(true);
            } catch (Exception e) {
                conn.setRequestProperty("X-HTTP-Method-Override", upperMethod);
                try {
                    conn.setRequestMethod("POST");
                } catch (Throwable ignored) {}
                conn.setDoOutput(true);
            }
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bytes);
                os.flush();
            }
        }

        int statusCode;
        try {
            statusCode = conn.getResponseCode();
        } catch (Exception e) {
            conn.disconnect();
            throw e;
        }

        InputStream stream = null;
        try {
            if (statusCode >= 200 && statusCode < 400) {
                stream = conn.getInputStream();
            } else {
                stream = conn.getErrorStream();
            }

            String responseBody = "";
            if (stream != null) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int read;
                while ((read = stream.read(buf)) != -1) {
                    baos.write(buf, 0, read);
                }
                responseBody = new String(baos.toByteArray(), StandardCharsets.UTF_8);
            }

            return new Response(statusCode, responseBody);
        } finally {
            if (stream != null) {
                try { stream.close(); } catch (Throwable ignored) {}
            }
            conn.disconnect();
        }
    }

    private static void setRequestMethod(HttpURLConnection conn, String method) throws Exception {
        try {
            conn.setRequestMethod(method);
        } catch (ProtocolException pe) {
            // Android 系统若对 PATCH 校验抛出 ProtocolException，通过反射强设 method 字段
            if ("PATCH".equalsIgnoreCase(method)) {
                try {
                    Field field = HttpURLConnection.class.getDeclaredField("method");
                    field.setAccessible(true);
                    field.set(conn, "PATCH");
                } catch (Throwable ignored) {
                    conn.setRequestProperty("X-HTTP-Method-Override", "PATCH");
                    conn.setRequestMethod("POST");
                }
            } else {
                throw pe;
            }
        }
    }

    private static JSONObject handleJsonResponse(Response res) throws Exception {
        JSONObject json;
        try {
            json = res.asJsonObject();
        } catch (Exception parseEx) {
            if (!res.isSuccess()) {
                throw new ApiException(res.statusCode, "HTTP " + res.statusCode + ": " + res.rawBody, null);
            }
            throw parseEx;
        }

        if (!res.isSuccess()) {
            String err = json.optString("error", "");
            if (err.isEmpty()) err = json.optString("message", "HTTP " + res.statusCode);
            throw new ApiException(res.statusCode, err, json);
        }
        return json;
    }

    public static JSONObject get(Context context, String path) throws Exception {
        return handleJsonResponse(request(context, "GET", path, null));
    }

    public static JSONArray getArray(Context context, String path) throws Exception {
        Response res = request(context, "GET", path, null);
        if (!res.isSuccess()) {
            JSONObject errJson = null;
            try { errJson = res.asJsonObject(); } catch (Throwable ignored) {}
            String msg = errJson != null ? errJson.optString("error", "HTTP " + res.statusCode) : ("HTTP " + res.statusCode);
            throw new ApiException(res.statusCode, msg, errJson);
        }
        return res.asJsonArray();
    }

    public static JSONObject post(Context context, String path, Object body) throws Exception {
        return handleJsonResponse(request(context, "POST", path, body));
    }

    public static JSONObject put(Context context, String path, Object body) throws Exception {
        return handleJsonResponse(request(context, "PUT", path, body));
    }

    public static JSONObject patch(Context context, String path, Object body) throws Exception {
        return handleJsonResponse(request(context, "PATCH", path, body));
    }

    public static JSONObject delete(Context context, String path) throws Exception {
        return delete(context, path, null);
    }

    public static JSONObject delete(Context context, String path, Object body) throws Exception {
        return handleJsonResponse(request(context, "DELETE", path, body));
    }
}
