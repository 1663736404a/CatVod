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
    private volatile boolean closed;

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
        return string(url, headers, 0);
    }

    /** Fetches text with an optional per-call deadline for metadata requests. */
    String string(String url, Map<String, String> headers, long timeoutMs) {
        if (closed) return "";
        OkHttpClient active = client;
        if (timeoutMs > 0) {
            active = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build();
        }
        try (Response response = active.newCall(request(url, headers).build()).execute()) {
            if (response.body() == null) return "";
            return response.body().string();
        } catch (Throwable e) {
            return "";
        }
    }

    /** One generic exchange, exposing the status and response headers to the caller. */
    static final class Exchange {
        int code;
        String body = "";
        final Map<String, String> headers = new HashMap<>();
    }

    /**
     * Runs an arbitrary request on this client, so callers that are not OkHttp-aware still go
     * through the configured proxy. Used by the BotGuard fetch bridge.
     *
     * @param method  HTTP method; only GET and POST are used by BotGuard.
     * @param body    request body, or {@code null} for a bodiless request.
     * @throws IOException on a transport failure, so the caller can report it verbatim.
     */
    Exchange exchange(String method, String url, Map<String, String> headers, String body,
                      long timeoutMs) throws IOException {
        if (closed) throw new IOException("Canceled: YouTube spider destroyed");
        OkHttpClient active = timeoutMs > 0
                ? client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
                : client;
        String verb = method == null || method.isEmpty() ? "GET" : method.toUpperCase();
        Request.Builder builder = request(url, headers);
        if ("POST".equals(verb) || "PUT".equals(verb) || "PATCH".equals(verb)) {
            String type = headers == null ? null : headers.get("content-type");
            if (type == null && headers != null) type = headers.get("Content-Type");
            MediaType media = null;
            if (type != null && !type.isEmpty()) {
                try {
                    media = MediaType.parse(type);
                } catch (Throwable ignored) {
                    // An unparseable content-type falls back to OkHttp's default.
                }
            }
            builder.method(verb, RequestBody.create(body == null ? "" : body, media));
        } else {
            builder.method(verb, null);
        }
        try (Response response = active.newCall(builder.build()).execute()) {
            Exchange result = new Exchange();
            result.code = response.code();
            result.body = response.body() == null ? "" : response.body().string();
            for (String name : response.headers().names()) {
                result.headers.put(name, response.header(name));
            }
            return result;
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
        if (closed) throw new IOException("Canceled: YouTube spider destroyed");
        String target = url + (url.contains("?") ? "&" : "?") + "rn=" + rn;
        Map<String, String> merged = new HashMap<>();
        merged.put("Content-Type", "application/x-protobuf");
        merged.put("Accept", "application/vnd.yt-ump");
        // Identity encoding keeps UMP framing byte-exact for the streaming parser.
        merged.put("Accept-Encoding", "identity");
        if (headers != null) merged.putAll(headers);
        Request request = request(target, merged).post(RequestBody.create(payload, PROTOBUF)).build();
        // Deliberately no callTimeout. callTimeout bounds the WHOLE call including streaming the UMP
        // body, so an 8s deadline aborted perfectly healthy responses as soon as one pump returned
        // several large segments at once (observed: completed=5 at 2160p, then
        // InterruptedIOException every ~8.1s, 15 times in one session). Seeking into a video with a
        // resume position made it worse, because the producer has to refetch init plus a burst of
        // segments from the seek point.
        //
        // Liveness is still bounded, just not by a deadline on bulk transfer: connectTimeout and
        // readTimeout (set on the shared client) catch a dead or stalled socket, and the session's
        // canceled flag is checked inside the UMP parse loop so teardown abandons the response.
        Response response = client.newCall(request).execute();
        Result result = new Result();
        result.raw = response;
        result.code = response.code();
        result.contentType = response.header("content-type");
        return result;
    }

    void close() {
        closed = true;
        try {
            client.dispatcher().cancelAll();
            client.connectionPool().evictAll();
        } catch (Throwable ignored) {
            // Best-effort cancellation; in-flight callers also observe the closed flag.
        }
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