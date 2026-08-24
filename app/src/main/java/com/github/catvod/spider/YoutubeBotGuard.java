package com.github.catvod.spider;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.github.catvod.crawler.SpiderDebug;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Mints a real visitor-bound GVS poToken in Android WebView using bgutils-js v4.
 *
 * <p>All network traffic is routed back into {@link YTHttp} instead of being issued by the WebView.
 * This is not an optimisation, it is a correctness requirement: {@code WebView} has its own network
 * stack and ignores an OkHttp {@code java.net.Proxy}, so a {@code fetch()} inside the page goes out
 * directly no matter what {@code ext.proxy} says. Where YouTube is unreachable that surfaced as
 * {@code BotGuard JS 失败: TypeError: Failed to fetch} (the module import or the GenerateIT POST
 * dying), followed by {@code webview-timeout} and
 * {@code TVHTML5 SABR 条件失败: missing-visitor-bound-potoken} — playback never started because the
 * token was never minted.
 *
 * <p>So the page performs no I/O of its own: the bgutils modules are fetched in Java and injected
 * as source, and the challenge/GenerateIT calls are proxied through a {@code @JavascriptInterface}
 * bridge onto the spider's client. The WebView is then only a JS engine, which is all BotGuard
 * actually needs it for.
 */
final class YoutubeBotGuard {

    private static final String ORIGIN = "https://esm.sh";
    private static final String CDN = ORIGIN + "/bgutils-js@4.0.3/";
    private static final String[] MODULES = {"botguard", "webpo", "utils"};
    /** Bound on the crawl, so a CDN layout change cannot turn into an unbounded fetch loop. */
    private static final int MAX_MODULES = 40;
    /** Deadline for the whole mint, including module downloads over a slow proxy. */
    private static final long TIMEOUT_MS = 45000L;

    private final YoutubePoToken configured;
    private final Context context;
    private final YTHttp http;
    /** Module sources are version-pinned and identical for every visitor, so cache them. */
    private static final Map<String, String> MODULE_CACHE = new HashMap<>();

    YoutubeBotGuard(Context context, YoutubePoToken configured) {
        this(context, configured, null);
    }

    YoutubeBotGuard(Context context, YoutubePoToken configured, YTHttp http) {
        this.context = context == null ? null : context.getApplicationContext();
        this.configured = configured;
        this.http = http;
    }

    String token(String visitorData) {
        String token = configured.get(YoutubePlayer.CLIENT, visitorData);
        return token != null ? token : mint(visitorData);
    }

    private String mint(String binding) {
        if (context == null || binding == null) return null;
        if (http == null) {
            SpiderDebug.log("YouTube BotGuard 失败: no-http-client");
            return null;
        }
        // Fetch the modules before the WebView exists: this is the step that used to fail silently
        // inside the page, and doing it here means a proxy/network problem is reported as itself.
        Map<String, String> graph = graph();
        if (graph == null) return null;
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        new Handler(Looper.getMainLooper()).post(() -> createWebView(binding, graph, result, done));
        try {
            if (!done.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                SpiderDebug.log("YouTube BotGuard 失败: webview-timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            SpiderDebug.log("YouTube BotGuard 失败: interrupted");
        }
        if (result[0] != null) SpiderDebug.log("YouTube BotGuard 成功: visitor-bound token 已生成");
        return result[0];
    }

    /**
     * Downloads the whole module graph, keyed by absolute URL.
     *
     * <p>A single fetch per entry point is not enough. What esm.sh returns for
     * {@code /bgutils-js@4.0.3/botguard} is a two-statement shim:
     * <pre>
     * import "/bgutils-js@4.0.3/es2022/dist/utils/helpers.mjs";
     * export * from "/bgutils-js@4.0.3/es2022/botguard.mjs";
     * </pre>
     * and the target itself keeps importing relative paths
     * ({@code from"./dist/utils/helpers.mjs"}). Since the page blocks network loads, every
     * transitive dependency has to be present locally, so the graph is walked here.
     *
     * @return url -&gt; source, or {@code null} when any module could not be fetched.
     */
    private Map<String, String> graph() {
        Map<String, String> sources = new LinkedHashMap<>();
        java.util.ArrayDeque<String> queue = new java.util.ArrayDeque<>();
        for (String name : MODULES) queue.add(CDN + name);
        while (!queue.isEmpty()) {
            String url = queue.poll();
            if (sources.containsKey(url)) continue;
            if (sources.size() >= MAX_MODULES) {
                SpiderDebug.log("YouTube BotGuard 失败: 模块图超过 " + MAX_MODULES + " 个");
                return null;
            }
            String source = fetchModule(url);
            if (source == null || source.isEmpty()) {
                SpiderDebug.log("YouTube BotGuard 失败: 模块下载失败 " + url
                        + "（检查 ext.proxy 是否可达 esm.sh）");
                return null;
            }
            sources.put(url, source);
            for (String specifier : specifiers(source)) {
                String resolved = resolve(url, specifier);
                if (resolved != null && !sources.containsKey(resolved)) queue.add(resolved);
            }
        }
        return sources;
    }

    /** Fetches one module through the spider's client, with a process-wide cache. */
    private String fetchModule(String url) {
        synchronized (MODULE_CACHE) {
            String cached = MODULE_CACHE.get(url);
            if (cached != null && !cached.isEmpty()) return cached;
        }
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "*/*");
        String source = http.string(url, headers, 20000L);
        if (source == null || source.isEmpty()) return null;
        synchronized (MODULE_CACHE) {
            MODULE_CACHE.put(url, source);
        }
        return source;
    }

    /**
     * Extracts every module specifier from {@code import}/{@code export} statements.
     *
     * <p>Scans for the {@code from"..."} / {@code import"..."} forms esbuild emits (minified, so no
     * space) as well as the spaced form, rather than parsing JS.
     */
    static java.util.List<String> specifiers(String source) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (source == null) return out;
        int index = 0;
        int length = source.length();
        while (index < length) {
            int from = indexOfKeyword(source, index);
            if (from < 0) break;
            int cursor = from;
            // Skip the keyword, then any whitespace before the quote.
            while (cursor < length && source.charAt(cursor) != '"' && source.charAt(cursor) != '\''
                    && source.charAt(cursor) != '\n' && source.charAt(cursor) != ';') cursor++;
            if (cursor >= length || (source.charAt(cursor) != '"' && source.charAt(cursor) != '\'')) {
                index = from + 6;
                continue;
            }
            char delimiter = source.charAt(cursor);
            int end = source.indexOf(delimiter, cursor + 1);
            if (end < 0) break;
            String specifier = source.substring(cursor + 1, end);
            if (!specifier.isEmpty()) out.add(specifier);
            index = end + 1;
        }
        return out;
    }

    /**
     * @return index just past the next real {@code from} / {@code import} keyword, or {@code -1}.
     *
     * <p>The preceding character must not be an identifier character, otherwise {@code from} inside
     * {@code String.fromCharCode(...)} matches and the following literal is mistaken for a module
     * specifier. helpers.mjs contains exactly that, and it yielded a phantom {@code "$1"} from
     * {@code .replace(/,\s*([\]}])/g,"$1")}. A phantom is not fetchable, but it would still take
     * part in the in-page specifier rewrite, so it is excluded here.
     */
    private static int indexOfKeyword(String source, int start) {
        int cursor = start;
        while (cursor < source.length()) {
            int fromIndex = source.indexOf("from", cursor);
            int importIndex = source.indexOf("import", cursor);
            int at;
            int length;
            if (fromIndex < 0 && importIndex < 0) return -1;
            if (fromIndex < 0 || (importIndex >= 0 && importIndex < fromIndex)) {
                at = importIndex;
                length = 6;
            } else {
                at = fromIndex;
                length = 4;
            }
            if (standalone(source, at, length)) return at + length;
            cursor = at + 1;
        }
        return -1;
    }

    /** True when the keyword is not part of a longer identifier or property access. */
    private static boolean standalone(String source, int at, int length) {
        if (at > 0) {
            char before = source.charAt(at - 1);
            if (before == '.' || before == '_' || before == '$'
                    || Character.isLetterOrDigit(before)) return false;
        }
        int after = at + length;
        if (after < source.length()) {
            char next = source.charAt(after);
            if (next == '_' || next == '$' || Character.isLetterOrDigit(next)) return false;
        }
        return true;
    }

    /**
     * Resolves a specifier against its importer.
     *
     * @return the absolute URL, or {@code null} for a bare specifier (nothing to fetch) or a
     *         cross-origin one (out of scope for this graph).
     */
    static String resolve(String base, String specifier) {
        if (specifier == null || specifier.isEmpty()) return null;
        if (specifier.startsWith("http://") || specifier.startsWith("https://")) {
            return specifier.startsWith(ORIGIN) ? specifier : null;
        }
        if (specifier.startsWith("//")) return null;
        if (specifier.startsWith("/")) return ORIGIN + specifier;
        if (!specifier.startsWith("./") && !specifier.startsWith("../")) return null;
        String directory = base.substring(0, base.lastIndexOf('/') + 1);
        String path = directory + specifier;
        // Collapse ./ and ../ so the key matches what a later importer would produce.
        int origin = path.indexOf("://");
        int split = path.indexOf('/', origin + 3);
        if (split < 0) return path;
        String host = path.substring(0, split);
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        for (String part : path.substring(split + 1).split("/")) {
            if (part.isEmpty() || ".".equals(part)) continue;
            if ("..".equals(part)) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(part);
        }
        StringBuilder out = new StringBuilder(host);
        for (String part : parts) out.append('/').append(part);
        return out.toString();
    }

    private void createWebView(String binding, Map<String, String> sources, String[] result, CountDownLatch done) {
        try {
            WebView web = new WebView(context);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(YoutubePlayer.DEFAULT_UA);
            // Blocking network loads makes the isolation explicit: if any code still tries to reach
            // the network from inside the page, it fails loudly here instead of silently bypassing
            // the proxy.
            settings.setBlockNetworkLoads(true);
            web.addJavascriptInterface(new Bridge(result, done, web, http), "CatVodBotGuard");
            web.setWebViewClient(new WebViewClient());
            web.loadDataWithBaseURL("https://www.youtube.com/", html(binding, sources),
                    "text/html", "UTF-8", null);
        } catch (Throwable error) {
            SpiderDebug.log("YouTube BotGuard WebView 创建失败: " + error);
            done.countDown();
        }
    }

    /**
     * Builds the page.
     *
     * <p>Modules are injected as blob URLs so their {@code import} statements resolve locally, and
     * a {@code fetch} shim forwards every request to Java. bgutils takes a {@code fetchFunction},
     * but {@code GenerateIT} and the interpreter's own calls use the global, so the global is
     * replaced rather than only passing the option.
     */
    private static String html(String binding, Map<String, String> graph) {
        StringBuilder page = new StringBuilder();
        page.append("<!doctype html><meta charset=utf-8><script>");
        // Java-backed fetch: returns a Response built from the bridge's JSON envelope. Synchronous
        // bridge calls are fine here — this runs on the WebView's JS thread, not the UI thread.
        page.append("self.fetch=async function(input,init){")
                .append("const url=typeof input==='string'?input:(input&&input.url)||String(input);")
                .append("const opt=init||{};let method=(opt.method||(input&&input.method)||'GET').toUpperCase();")
                .append("let headers={};const h=opt.headers||(input&&input.headers);")
                .append("if(h){if(typeof h.forEach==='function'){h.forEach((v,k)=>{headers[k]=v;});}")
                .append("else{for(const k in h){headers[k]=h[k];}}}")
                .append("let body=opt.body;if(body&&typeof body!=='string'){try{body=new TextDecoder().decode(body);}catch(e){body=String(body);}}")
                .append("const raw=CatVodBotGuard.request(method,url,JSON.stringify(headers),body==null?null:String(body));")
                .append("const res=JSON.parse(raw);if(res.error)throw new TypeError('proxy-fetch: '+res.error);")
                .append("return new Response(res.body,{status:res.status,headers:res.headers||{}});};");
        page.append("</script><script type=module>");
        // The graph is materialised as blob URLs. A module's own imports are rewritten to the blob
        // URL of the dependency, so resolution happens entirely in-page: with network loads blocked
        // an unrewritten specifier would fail, which is the point — nothing may escape to the CDN.
        // Dependencies are created first (reverse insertion order), so a blob URL always exists
        // before the module referencing it is built.
        page.append("const SRC=").append(sourceMap(graph)).append(";");
        page.append("const DEPS=").append(depsMap(graph)).append(";");
        page.append("const BLOB={};");
        page.append("const build=(u,seen)=>{if(BLOB[u])return BLOB[u];if(seen.has(u))return BLOB[u]||'';seen.add(u);");
        page.append("let src=SRC[u];if(src==null)return '';");
        page.append("const deps=DEPS[u]||[];for(const [spec,dep] of deps){const url=build(dep,seen);");
        // Replace the specifier only inside its quotes, so identical text elsewhere is untouched.
        page.append("src=src.split('\"'+spec+'\"').join('\"'+url+'\"').split(\"'\"+spec+\"'\").join(\"'\"+url+\"'\");}");
        page.append("BLOB[u]=URL.createObjectURL(new Blob([src],{type:'text/javascript'}));return BLOB[u];};");
        page.append("const ENTRY=").append(entryMap(graph)).append(";");
        page.append("try{");
        page.append("const bgm=await import(build(ENTRY.botguard,new Set()));");
        page.append("const wpm=await import(build(ENTRY.webpo,new Set()));");
        page.append("const um=await import(build(ENTRY.utils,new Set()));");
        page.append("const {BotGuardClient,getChallenge}=bgm;const {WebPoMinter}=wpm;const {buildURL,GOOG_API_KEY,getHeaders}=um;");
        page.append("const K='O43z0dpjhgX20SCx4KAo';");
        page.append("const c=await getChallenge({requestKey:K,fetchFunction:(u,o)=>fetch(u,o),useYouTubeAPI:true});");
        page.append("const code=c.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;if(!code)throw Error('missing-interpreter');");
        page.append("(0,eval)(code);const signals=[];");
        page.append("const bg=await BotGuardClient.create({program:c.program,globalName:c.globalName,globalObject:globalThis});");
        page.append("const response=await bg.snapshot({webPoSignalOutput:signals});if(!signals[0])throw Error('missing-minter');");
        page.append("const r=await fetch(buildURL('GenerateIT',true),{method:'POST',headers:{...getHeaders(),'content-type':'application/json+protobuf','x-goog-api-key':GOOG_API_KEY},body:JSON.stringify([K,response])});");
        page.append("const d=await r.json();if(!d||!d[0])throw Error('GenerateIT');");
        page.append("const m=await WebPoMinter.create({integrityToken:d[0]},signals);");
        page.append("CatVodBotGuard.success(await m.mintAsWebsafeString(").append(js(binding)).append("));");
        page.append("}catch(e){CatVodBotGuard.failure(String(e&&e.stack||e));}</script>");
        return page.toString();
    }

    /** Emits {@code {url: source}}; keys are quoted because URLs are not identifiers. */
    private static String sourceMap(Map<String, String> graph) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : graph.entrySet()) {
            if (!first) out.append(',');
            first = false;
            out.append(js(entry.getKey())).append(':').append(js(entry.getValue()));
        }
        return out.append('}').toString();
    }

    /** Emits {@code {url: [[specifier, resolvedUrl], ...]}} for in-page rewriting. */
    private static String depsMap(Map<String, String> graph) {
        StringBuilder out = new StringBuilder("{");
        boolean firstModule = true;
        for (Map.Entry<String, String> entry : graph.entrySet()) {
            java.util.LinkedHashMap<String, String> deps = new java.util.LinkedHashMap<>();
            for (String specifier : specifiers(entry.getValue())) {
                String resolved = resolve(entry.getKey(), specifier);
                if (resolved != null && graph.containsKey(resolved)) deps.put(specifier, resolved);
            }
            if (deps.isEmpty()) continue;
            if (!firstModule) out.append(',');
            firstModule = false;
            out.append(js(entry.getKey())).append(":[");
            boolean firstDep = true;
            for (Map.Entry<String, String> dep : deps.entrySet()) {
                if (!firstDep) out.append(',');
                firstDep = false;
                out.append('[').append(js(dep.getKey())).append(',').append(js(dep.getValue())).append(']');
            }
            out.append(']');
        }
        return out.append('}').toString();
    }

    /** Maps each entry point name to its absolute URL. */
    private static String entryMap(Map<String, String> graph) {
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        for (String name : MODULES) {
            String url = CDN + name;
            if (!graph.containsKey(url)) continue;
            if (!first) out.append(',');
            first = false;
            out.append(name).append(':').append(js(url));
        }
        return out.append('}').toString();
    }

    /** Quotes a value as a JS string literal, safe to embed inside a {@code <script>} block. */
    private static String js(String value) {
        if (value == null) return "''";
        StringBuilder out = new StringBuilder(value.length() + 16).append('\'');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '\'': out.append("\\'"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\u2028': out.append("\\u2028"); break;
                case '\u2029': out.append("\\u2029"); break;
                case '<':
                    // Never let "</script>" appear literally inside the injected source.
                    out.append("\\x3c");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.append('\'').toString();
    }

    private static final class Bridge {
        final String[] result;
        final CountDownLatch done;
        final WebView web;
        final YTHttp http;

        Bridge(String[] result, CountDownLatch done, WebView web, YTHttp http) {
            this.result = result;
            this.done = done;
            this.web = web;
            this.http = http;
        }

        /** Runs one BotGuard HTTP call on the spider's client, so it uses the configured proxy. */
        @JavascriptInterface
        public String request(String method, String url, String headersJson, String body) {
            return YTBotGuardFetch.run(http, method, url, headersJson, body);
        }

        @JavascriptInterface
        public void success(String token) {
            result[0] = token == null || token.isEmpty() ? null : token;
            if (result[0] == null) SpiderDebug.log("YouTube BotGuard 失败: empty-token");
            finish();
        }

        @JavascriptInterface
        public void failure(String error) {
            SpiderDebug.log("YouTube BotGuard JS 失败: " + error);
            finish();
        }

        void finish() {
            done.countDown();
            new Handler(Looper.getMainLooper()).post(web::destroy);
        }
    }
}
