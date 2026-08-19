package com.github.catvod.spider;

import android.annotation.SuppressLint;

import java.io.IOException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * HTTP helper for the YouTube spider.
 *
 * <p>Uses OkHttp directly rather than {@code net.OkHttp} because this spider needs streaming
 * response bodies (SABR/UMP parsing) and per-request Range headers, neither of which the shared
 * helper exposes.
 */
final class YTHttp {

    private static final MediaType PROTOBUF = MediaType.get("application/x-protobuf");
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient client;
    private final Map<String, String> baseHeaders = new HashMap<>();

    YTHttp(Map<String, String> headers) {
        this(headers, null);
    }

    /**
     * @param proxy optional {@code host:port} HTTP proxy, taken from the site's {@code proxy}
     *              extend value. All YouTube traffic from this spider goes through it.
     */
    YTHttp(Map<String, String> headers, String proxy) {
        if (headers != null) baseHeaders.putAll(headers);
        this.client = build(proxy);
    }
    private static OkHttpClient build(String proxy) {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
.connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .retryOnConnectionFailure(true);
        if (proxy != null && !proxy.isEmpty()) {
            int colon = proxy.lastIndexOf(':');
            if (colon > 0) {
                try {
                    String host = proxy.substring(0, colon);
                    int port = Integer.parseInt(proxy.substring(colon + 1).trim());
                    builder.proxy(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                            new java.net.InetSocketAddress(host, port)));
                } catch (Throwable ignored) {
                    // A malformed proxy value is ignored rather than breaking every request.
                }
            }
        }
        try {
            builder.hostnameVerifier((hostname, session) -> true)
                    .sslSocketFactory(sslContext().getSocketFactory(), trustAll());
        } catch (Throwable ignored) {
            // Fall back to platform TLS verification.
        }
        return builder.build();
    }

    private static SSLContext sslContext() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{trustAll()}, new SecureRandom());
        return context;
    }

    @SuppressLint({"TrustAllX509TrustManager", "CustomX509TrustManager"})
    private static X509TrustManager trustAll() {
        return new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
    }

    private Request.Builder request(String url, Map<String, String> headers) {
        Request.Builder builder = new Request.Builder().url(url);
        Map<String, String> merged = new HashMap<>(baseHeaders);
        if (headers != null) merged.putAll(headers);
        for (Map.Entry<String, String> entry : merged.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null) continue;
            builder.header(entry.getKey(), entry.getValue());
        }
        return builder;
    }

    /** @return the response body as text, or an empty string on failure. */
    String string(String url) {
        return string(url, null);
    }

    String string(String url, Map<String, String> headers) {
        try (Response response = client.newCall(request(url, headers).build()).execute()) {
            if (response.body() == null) return "";
            return response.body().string();
        } catch (Throwable e) {
            return "";
        }
    }

    String postJson(String url, String json, Map<String, String> headers) throws IOException {
        Map<String, String> merged = new HashMap<>();
        merged.put("Content-Type", "application/json");
        merged.put("Origin", "https://www.youtube.com");
        if (headers != null) merged.putAll(headers);
        Request request = request(url, merged).post(RequestBody.create(json, JSON)).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw new IOException("HTTP " + response.code());
            return response.body() == null ? "" : response.body().string();
        }
    }

    /**
     * Response holder for byte-range and streaming requests.
     *
     * <p>{@link #close()} must be called; {@link #raw} is only valid until then.
     */
    static final class Result implements java.io.Closeable {
        int code;
        byte[] body;
        String contentType;
        String contentRange;
        String contentLength;
        Response raw;

        @Override
        public void close() {
            if (raw != null) {
                try {
                    raw.close();
                } catch (Throwable ignored) {
                    // Already closed or the connection is gone.
                }
            }
        }
    }

    /** Fetches a URL fully into memory, optionally with a Range header. */
    Result get(String url, Map<String, String> headers, String range) {
        Map<String, String> merged = new HashMap<>();
        if (headers != null) merged.putAll(headers);
        if (range != null && !range.isEmpty()) merged.put("Range", range);
        Result result = new Result();
        try (Response response = client.newCall(request(url, merged).build()).execute()) {
            result.code = response.code();
            result.contentType = response.header("content-type");
            result.contentRange = response.header("content-range");
            result.contentLength = response.header("content-length");
            result.body = response.body() == null ? new byte[0] : response.body().bytes();
            return result;
        } catch (Throwable e) {
            result.code = 500;
            result.body = new byte[0];
            return result;
        }
    }

    /**
     * Issues a SABR POST and leaves the body open for incremental UMP parsing.
     *
     * <p>The caller owns the returned {@link Result} and must close it.
     */
    Result postSabr(String url, byte[] payload, Map<String, String> headers, long rn) throws IOException {
        String target = url + (url.contains("?") ? "&" : "?") + "rn=" + rn;
        Map<String, String> merged = new HashMap<>();
        merged.put("Content-Type", "application/x-protobuf");
        merged.put("Accept", "application/vnd.yt-ump");
        // Identity encoding keeps UMP framing byte-exact for the streaming parser.
        merged.put("Accept-Encoding", "identity");
        if (headers != null) merged.putAll(headers);
        Request request = request(target, merged).post(RequestBody.create(payload, PROTOBUF)).build();
        Response response = client.newCall(request).execute();
        Result result = new Result();
        result.raw = response;
        result.code = response.code();
        result.contentType = response.header("content-type");
        return result;
    }

    /** @return true when an exception looks like a transient transport failure worth retrying. */
    static boolean isRetryable(Throwable error) {
        String text = String.valueOf(error);
        String name = error == null ? "" : error.getClass().getName();
        String probe = text + " " + name;
        return probe.contains("SocketTimeout")
                || probe.contains("Connection reset")
                || probe.contains("unexpected end of stream")
                || probe.contains("ProtocolException")
                || probe.contains("StreamResetException")
                || probe.contains("Canceled")
                || probe.contains("EOFException")
                || probe.contains("IncompleteRead")
                || probe.contains("Read timed out");
    }
}