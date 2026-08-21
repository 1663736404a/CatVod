package com.github.catvod.spider;

import android.text.TextUtils;
import com.whl.quickjs.android.QuickJSLoader;
import com.whl.quickjs.wrapper.QuickJSContext;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Solves the TVHTML5 serverAbrStreamingUrl n challenge with the current base.js. */
final class YoutubeNsig {
    private static final Pattern[] FACTORIES = new Pattern[]{
            Pattern.compile("([\\w$]+)\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{[^{}]{0,240}?set\\(\\\"alr\\\",\\\"yes\\\"\\)"),
            Pattern.compile("function\\s+([\\w$]+)\\s*\\([^)]*\\)\\s*\\{[^{}]{0,240}?set\\(\\\"alr\\\",\\\"yes\\\"\\)")
    };

    private YoutubeNsig() {}

    static String solve(String playerCode, String value) {
        if (TextUtils.isEmpty(playerCode) || TextUtils.isEmpty(value)) return null;
        Matcher match = null;
        for (Pattern pattern : FACTORIES) {
            Matcher candidate = pattern.matcher(playerCode);
            if (candidate.find()) { match = candidate; break; }
        }
        if (match == null) return null;
        String factory = match.group(1);
        int anchor = Math.max(playerCode.lastIndexOf("})(_yt_player);"), playerCode.lastIndexOf("}).call(this);"));
        if (anchor < 0) return null;
        String shim = "var globalThis=globalThis||this;var location={href:'https://www.youtube.com/',host:'www.youtube.com',hostname:'www.youtube.com',origin:'https://www.youtube.com',pathname:'/',protocol:'https:',search:'',hash:''};if(!globalThis.window)globalThis.window=globalThis;if(!globalThis.self)globalThis.self=globalThis;if(!globalThis.document)globalThis.document={};if(!globalThis.navigator)globalThis.navigator={};if(!globalThis.performance)globalThis.performance={};if(!globalThis.sessionStorage)globalThis.sessionStorage={};if(!globalThis.trustedTypes)globalThis.trustedTypes={};";
        String injected = "globalThis.__solve_n=function(v){var u=" + factory + "('https://youtube.com/watch?v=x','s',undefined);u.set('n',v);var p=Object.getPrototypeOf(u),k=Object.keys(p).concat(Object.getOwnPropertyNames(p));for(var i=0;i<k.length;i++){if(['constructor','set','get','clone'].indexOf(k[i])<0){u[k[i]]();break;}}return u.get('n');};";
        QuickJSContext context = null;
        try {
            QuickJSLoader.init();
            context = QuickJSContext.create();
            Object result = context.evaluate(shim + playerCode.substring(0, anchor) + injected + playerCode.substring(anchor)
                    + ";globalThis.__solve_n(" + quote(value) + ");");
            return result == null ? null : String.valueOf(result);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (context != null) context.destroy();
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
