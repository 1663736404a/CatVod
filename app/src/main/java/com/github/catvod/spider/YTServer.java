package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A JAR-owned loopback HTTP server for YouTube media delivery.
 *
 * <p>Rationale: the host's {@code /proxy} endpoint is reachable only while the host's jar-loader
 * still holds this Spider and its {@code proxy} method. When the host tears the catalog down
 * ({@code base-loader: clear reason=mobile-home-destroy}) it drops that registry, and every
 * subsequent segment request is answered by the host itself with
 * {@code proxy: response invalid reason=null_or_empty} — HTTP 500 — without ever entering this
 * JAR. Playback then survives only as long as the player's existing buffer (observed: ~36s) and
 * dies with {@code ERROR_CODE_IO_BAD_HTTP_STATUS}, which sends the host back to the catalog.
 *
 * <p>Serving media from a socket this JAR owns removes that dependency: the accept loop is a live
 * daemon thread, so it keeps the JAR's class loader and SABR sessions alive across a loader clear
 * and the player's URLs stay valid for the whole episode.
 *
 * <p>Bound to the loopback interface only — the endpoint is unauthenticated and must not be
 * reachable from the LAN. A consequence is that these URLs are for in-process playback; screen
 * casting to another device still needs the host's own proxy URL.
 */
final class YTServer {

    private static final String PATH = "/yt";
    private static final long IDLE_SHUTDOWN_MS = 600000L;

    private static final Map<String, YTPlay> OWNERS = new ConcurrentHashMap<>();
    private static final AtomicLong LAST_REQUEST = new AtomicLong();
    private static ServerSocket socket;
    private static ExecutorService workers;
    private static int port;

    private YTServer() {
    }

    static void register(String ownerId, YTPlay play) {
        if (ownerId == null || play == null) return;
        OWNERS.put(ownerId, play);
        // The host re-creates the Spider on every catalog rebuild and does not always call
        // destroy(), so bound the registry rather than trusting unregister() alone. Insertion order
        // is monotonic in the id suffix, so the lowest ids are the oldest.
        while (OWNERS.size() > 4) {
            String oldest = null;
            long oldestSeq = Long.MAX_VALUE;
            for (String key : OWNERS.keySet()) {
                if (key.equals(ownerId)) continue;
                long seq;
                try {
                    seq = Long.parseLong(key.substring(1));
                } catch (Throwable e) {
                    seq = -1L;
                }
                if (seq < oldestSeq) {
                    oldestSeq = seq;
                    oldest = key;
                }
            }
            if (oldest == null) break;
            OWNERS.remove(oldest);
        }
    }

    static void unregister(String ownerId) {
        if (ownerId != null) OWNERS.remove(ownerId);
    }

    /** Returns the live port, starting the server on first use. {@code 0} means unavailable. */
    static synchronized int ensure() {
        if (socket != null && !socket.isClosed()) return port;
        try {
            // Port 0 lets the OS pick a free one. A previous incarnation of this JAR (loaded by an
            // older class loader) may still be serving an in-flight session on its own port, so we
            // must never insist on a fixed number.
            ServerSocket opened = new ServerSocket(0, 32, InetAddress.getLoopbackAddress());
            opened.setReuseAddress(true);
            socket = opened;
            port = opened.getLocalPort();
            workers = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "youtube-media-worker");
                thread.setDaemon(true);
                return thread;
            });
            LAST_REQUEST.set(System.currentTimeMillis());
            Thread accept = new Thread(() -> acceptLoop(opened), "youtube-media-server");
            accept.setDaemon(true);
            accept.start();
            Thread idle = new Thread(() -> idleLoop(opened), "youtube-media-idle");
            idle.setDaemon(true);
            idle.start();
            SpiderDebug.log("YouTube 媒体服务已启动: port=" + port);
            return port;
        } catch (Throwable e) {
            SpiderDebug.log("YouTube 媒体服务启动失败: " + String.valueOf(e));
            socket = null;
            port = 0;
            return 0;
        }
    }

    /** Absolute URL for one media request, or {@code null} when the server is unavailable. */
    static String url(String ownerId, String siteKey, String params) {
        int live = ensure();
        if (live <= 0) return null;
        return "http://127.0.0.1:" + live + PATH + "?do=csp&siteKey=" + siteKey
                + "&yto=" + ownerId + (params == null ? "" : params);
    }

    private static void acceptLoop(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                workers.execute(() -> serve(client));
            } catch (Throwable e) {
                if (server.isClosed()) return;
            }
        }
    }

    private static void idleLoop(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Thread.sleep(60000L);
            } catch (InterruptedException ignored) {
            }
            if (System.currentTimeMillis() - LAST_REQUEST.get() < IDLE_SHUTDOWN_MS) continue;
            synchronized (YTServer.class) {
                if (socket != server) return;
                socket = null;
                port = 0;
            }
            try {
                server.close();
            } catch (Throwable ignored) {
            }
            SpiderDebug.log("YouTube 媒体服务空闲关闭");
            return;
        }
    }

    private static void serve(Socket client) {
        LAST_REQUEST.set(System.currentTimeMillis());
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(30000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String line = readLine(in);
            if (line == null || line.isEmpty()) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) {
                write(out, 400, "text/plain; charset=utf-8", null,
                        new ByteArrayInputStream("bad request".getBytes(StandardCharsets.UTF_8)), false);
                return;
            }
            String method = parts[0].toUpperCase();
            String target = parts[1];
            Map<String, String> headers = new LinkedHashMap<>();
            String header;
            while ((header = readLine(in)) != null && !header.isEmpty()) {
                int colon = header.indexOf(':');
                if (colon <= 0) continue;
                headers.put(header.substring(0, colon).trim().toLowerCase(),
                        header.substring(colon + 1).trim());
            }
            boolean head = "HEAD".equals(method);
            if (!head && !"GET".equals(method)) {
                write(out, 405, "text/plain; charset=utf-8", null,
                        new ByteArrayInputStream("method not allowed".getBytes(StandardCharsets.UTF_8)), false);
                return;
            }
            Map<String, String> params = params(target);
            // The player's Range header must reach YTPlay under the same key the host's proxy uses,
            // otherwise the direct byte-range routes lose their bounds.
            String range = headers.get("range");
            if (range != null && !range.isEmpty()) params.put("range", range);

            YTPlay play = OWNERS.get(params.get("yto"));
            if (play == null) {
                // Fall back to any live instance: a stale MPD from an earlier Spider instance is
                // still better served than answered with an error the player treats as fatal.
                for (YTPlay value : OWNERS.values()) {
                    play = value;
                    break;
                }
            }
            if (play == null) {
                write(out, 503, "text/plain; charset=utf-8", null,
                        new ByteArrayInputStream("no youtube session".getBytes(StandardCharsets.UTF_8)), false);
                return;
            }
            Object[] result;
            try {
                result = play.proxy(params);
            } catch (Throwable e) {
                SpiderDebug.log("YouTube 媒体服务处理异常: " + String.valueOf(e));
                result = null;
            }
            if (result == null || result.length < 3) {
                write(out, 503, "text/plain; charset=utf-8", null,
                        new ByteArrayInputStream("no media".getBytes(StandardCharsets.UTF_8)), false);
                return;
            }
            int code = result[0] instanceof Integer ? (Integer) result[0] : 200;
            String contentType = result[1] == null ? "application/octet-stream" : String.valueOf(result[1]);
            InputStream body = result[2] instanceof InputStream ? (InputStream) result[2] : null;
            Map<String, String> extra = null;
            if (result.length > 3 && result[3] instanceof Map) {
                extra = new LinkedHashMap<>();
                for (Object entry : ((Map<?, ?>) result[3]).entrySet()) {
                    Map.Entry<?, ?> item = (Map.Entry<?, ?>) entry;
                    if (item.getKey() == null || item.getValue() == null) continue;
                    extra.put(String.valueOf(item.getKey()), String.valueOf(item.getValue()));
                }
            }
            write(out, code, contentType, extra, body, head);
        } catch (Throwable ignored) {
            // A player abort mid-segment is normal; nothing to recover here.
        } finally {
            try {
                client.close();
            } catch (Throwable ignored) {
            }
            LAST_REQUEST.set(System.currentTimeMillis());
        }
    }

    private static void write(OutputStream out, int code, String contentType,
                             Map<String, String> extra, InputStream body, boolean head) throws IOException {
        Long length = null;
        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                if (!"content-length".equalsIgnoreCase(entry.getKey())) continue;
                try {
                    length = Long.parseLong(entry.getValue().trim());
                } catch (Throwable ignored) {
                }
            }
        }
        if (length == null && body instanceof ByteArrayInputStream) {
            length = (long) body.available();
        }
        StringBuilder head1 = new StringBuilder();
        head1.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        head1.append("Content-Type: ").append(contentType).append("\r\n");
        boolean sawAcceptRanges = false;
        if (extra != null) {
            for (Map.Entry<String, String> entry : extra.entrySet()) {
                String key = entry.getKey();
                if ("content-type".equalsIgnoreCase(key) || "content-length".equalsIgnoreCase(key)
                        || "connection".equalsIgnoreCase(key) || "transfer-encoding".equalsIgnoreCase(key)) {
                    continue;
                }
                if ("accept-ranges".equalsIgnoreCase(key)) sawAcceptRanges = true;
                head1.append(key).append(": ").append(entry.getValue()).append("\r\n");
            }
        }
        if (!sawAcceptRanges) head1.append("Accept-Ranges: none\r\n");
        if (length != null) head1.append("Content-Length: ").append(length).append("\r\n");
        // Close-delimited framing keeps an unknown-length body valid without chunked encoding.
        head1.append("Connection: close\r\n\r\n");
        out.write(head1.toString().getBytes(StandardCharsets.UTF_8));
        if (head || body == null) {
            out.flush();
            return;
        }
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = body.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        out.flush();
        try {
            body.close();
        } catch (Throwable ignored) {
        }
    }

    private static Map<String, String> params(String target) {
        Map<String, String> params = new LinkedHashMap<>();
        int mark = target.indexOf('?');
        if (mark < 0) return params;
        for (String pair : target.substring(mark + 1).split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            params.put(decode(key), decode(value));
        }
        return params;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Throwable e) {
            return value;
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int value;
        while ((value = in.read()) != -1) {
            if (value == '\n') return sb.toString();
            if (value != '\r') sb.append((char) value);
            if (sb.length() > 8192) return sb.toString();
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String reason(int code) {
        switch (code) {
            case 200:
                return "OK";
            case 206:
                return "Partial Content";
            case 400:
                return "Bad Request";
            case 404:
                return "Not Found";
            case 405:
                return "Method Not Allowed";
            case 410:
                return "Gone";
            case 500:
                return "Internal Server Error";
            case 503:
                return "Service Unavailable";
            default:
                return code < 400 ? "OK" : "Error";
        }
    }
}
