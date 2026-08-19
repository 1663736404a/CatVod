package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 网盘驱动共用的 HTTP 层。
 *
 * <p>{@link com.github.catvod.net.OkHttp} 的 GET 只返回 body，而网盘凭据往往靠响应头里的
 * Set-Cookie 滚动续期（夸克的 {@code __puus} 就是），所以这里直接用 okhttp3，把状态码、body、
 * 响应头一起带回来。
 */
final class ApiHttp {

    private ApiHttp() {
    }

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static volatile OkHttpClient fallback;

    /** 一次请求的完整结果。 */
    static class Res {

        final int code;
        final String body;
        final Map<String, List<String>> headers;

        Res(int code, String body, Map<String, List<String>> headers) {
            this.code = code;
            this.body = body;
            this.headers = headers == null ? new HashMap<>() : headers;
        }
    }

    /** 优先复用宿主的客户端，拿不到就自己建一个。 */
    private static OkHttpClient client() {
        try {
            OkHttpClient hosted = Spider.client();
            if (hosted != null) return hosted;
        } catch (Throwable ignored) {
            // 宿主未提供，走自建
        }
        if (fallback == null) {
            synchronized (ApiHttp.class) {
                if (fallback == null) {
                    fallback = new OkHttpClient.Builder()
                            .connectTimeout(15, TimeUnit.SECONDS)
                            .readTimeout(30, TimeUnit.SECONDS)
                            .writeTimeout(30, TimeUnit.SECONDS)
                            .followRedirects(true)
                            .build();
                }
            }
        }
        return fallback;
    }

    static Res get(String url, Map<String, String> header) {
        return execute(new Request.Builder().url(url).get(), header);
    }

    static Res post(String url, String json, Map<String, String> header) {
        RequestBody body = RequestBody.create(json == null ? "" : json, JSON);
        return execute(new Request.Builder().url(url).post(body), header);
    }

    private static Res execute(Request.Builder builder, Map<String, String> header) {
        if (header != null && !header.isEmpty()) {
            Map<String, String> clean = new HashMap<>();
            for (Map.Entry<String, String> e : header.entrySet()) {
                // Headers.of 遇到 null 会抛，先过滤
                if (TextUtils.isEmpty(e.getKey()) || e.getValue() == null) continue;
                clean.put(e.getKey(), e.getValue());
            }
            builder.headers(Headers.of(clean));
        }
        try (Response response = client().newCall(builder.build()).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            return new Res(response.code(), text, response.headers().toMultimap());
        } catch (Throwable e) {
            SpiderDebug.log("网盘请求失败 " + e);
            return new Res(0, "", null);
        }
    }

    /** 从响应头收集 Set-Cookie 里的 {@code name=value}，属性段丢掉。 */
    static String cookies(Map<String, List<String>> headers) {
        if (headers == null) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (entry.getKey() == null || !"set-cookie".equalsIgnoreCase(entry.getKey())) continue;
            if (entry.getValue() == null) continue;
            for (String line : entry.getValue()) {
                if (TextUtils.isEmpty(line)) continue;
                int semi = line.indexOf(';');
                String pair = (semi > 0 ? line.substring(0, semi) : line).trim();
                if (!pair.contains("=")) continue;
                sb.append(pair).append(';');
            }
        }
        return sb.toString();
    }
}