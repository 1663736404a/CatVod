package com.github.catvod.spider;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Mints a real visitor-bound GVS poToken in Android WebView using bgutils-js v4. */
final class YoutubeBotGuard {
    private final YoutubePoToken configured;
    private final Context context;

    YoutubeBotGuard(Context context, YoutubePoToken configured) {
        this.context = context == null ? null : context.getApplicationContext();
        this.configured = configured;
    }

    String token(String visitorData) {
        String token = configured.get(YoutubePlayer.CLIENT, visitorData);
        return token != null ? token : mint(visitorData);
    }

    private String mint(String binding) {
        if (context == null || binding == null) return null;
        CountDownLatch done = new CountDownLatch(1);
        String[] result = new String[1];
        new Handler(Looper.getMainLooper()).post(() -> createWebView(binding, result, done));
        try { done.await(35, TimeUnit.SECONDS); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return result[0];
    }

    private void createWebView(String binding, String[] result, CountDownLatch done) {
        try {
            WebView web = new WebView(context);
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUserAgentString(YoutubePlayer.DEFAULT_UA);
            web.addJavascriptInterface(new Bridge(result, done, web), "CatVodBotGuard");
            web.setWebViewClient(new WebViewClient());
            web.loadDataWithBaseURL("https://www.youtube.com/", html(binding), "text/html", "UTF-8", null);
        } catch (Throwable ignored) { done.countDown(); }
    }

    private static String html(String binding) {
        String b = js(binding);
        return "<!doctype html><meta charset=utf-8><script type=module>"
                + "import{BotGuardClient,getChallenge}from'https://esm.sh/bgutils-js@4.0.3/botguard';"
                + "import{WebPoMinter}from'https://esm.sh/bgutils-js@4.0.3/webpo';"
                + "import{buildURL,GOOG_API_KEY,getHeaders}from'https://esm.sh/bgutils-js@4.0.3/utils';"
                + "const K='O43z0dpjhgX20SCx4KAo';try{"
                + "const c=await getChallenge({requestKey:K,fetchFunction:(u,o)=>fetch(u,o),useYouTubeAPI:true});"
                + "const code=c.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;if(!code)throw Error('missing-interpreter');"
                + "(0,eval)(code);const signals=[];const bg=await BotGuardClient.create({program:c.program,globalName:c.globalName,globalObject:globalThis});"
                + "const response=await bg.snapshot({webPoSignalOutput:signals});if(!signals[0])throw Error('missing-minter');"
                + "const r=await fetch(buildURL('GenerateIT',true),{method:'POST',headers:{...getHeaders(),'content-type':'application/json+protobuf','x-goog-api-key':GOOG_API_KEY},body:JSON.stringify([K,response])});"
                + "const d=await r.json();if(!d||!d[0])throw Error('GenerateIT');const m=await WebPoMinter.create({integrityToken:d[0]},signals);"
                + "CatVodBotGuard.success(await m.mintAsWebsafeString("+b+"));}catch(e){CatVodBotGuard.failure(String(e&&e.stack||e));}</script>";
    }

    private static String js(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n") + "'";
    }

    private static final class Bridge {
        final String[] result; final CountDownLatch done; final WebView web;
        Bridge(String[] result, CountDownLatch done, WebView web) { this.result=result; this.done=done; this.web=web; }
        @JavascriptInterface public void success(String token) { result[0]=token == null||token.isEmpty()?null:token; finish(); }
        @JavascriptInterface public void failure(String error) { finish(); }
        void finish() { done.countDown(); new Handler(Looper.getMainLooper()).post(web::destroy); }
    }
}
