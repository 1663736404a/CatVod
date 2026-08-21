package com.github.catvod.spider;

import android.text.TextUtils;

import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Solves TVHTML5 serverAbrStreamingUrl n with the host QuickJS runtime. */
final class YoutubeNsig {
    private static final Pattern[] FACTORIES = new Pattern[]{
            Pattern.compile("([\\w$]+)\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{[^{}]{0,240}?set\\(\\\"alr\\\",\\\"yes\\\"\\)"),
            Pattern.compile("function\\s+([\\w$]+)\\s*\\([^)]*\\)\\s*\\{[^{}]{0,240}?set\\(\\\"alr\\\",\\\"yes\\\"\\)")
    };

    private YoutubeNsig() {}

    static String solve(String playerCode, String value) {
        if (TextUtils.isEmpty(playerCode) || TextUtils.isEmpty(value)) return null;
        String factory = factory(playerCode);
        if (factory == null) return null;
        int anchor = Math.max(playerCode.lastIndexOf("})(_yt_player);"), playerCode.lastIndexOf("}).call(this);"));
        if (anchor < 0) return null;
        Object context = null;
        try {
            Class<?> loader = Class.forName("com.whl.quickjs.android.QuickJSLoader");
            loader.getMethod("init").invoke(null);
            Class<?> type = Class.forName("com.whl.quickjs.wrapper.QuickJSContext");
            context = type.getMethod("create").invoke(null);
            Method evaluate = type.getMethod("evaluate", String.class);
            Object result = evaluate.invoke(context, source(playerCode, anchor, factory, value));
            return result == null ? null : String.valueOf(result);
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (context != null) {
                try { context.getClass().getMethod("destroy").invoke(context); } catch (Throwable ignored) {}
            }
        }
    }

    private static String factory(String code) {
        for (Pattern pattern : FACTORIES) {
            Matcher matcher = pattern.matcher(code);
            if (matcher.find()) return matcher.group(1);
        }
        return null;
    }

    private static String source(String code, int anchor, String factory, String value) {
        String shim = "var globalThis=globalThis||this;var location={href:'https://www.youtube.com/',host:'www.youtube.com',hostname:'www.youtube.com',origin:'https://www.youtube.com',pathname:'/',protocol:'https:',search:'',hash:''};if(!globalThis.window)globalThis.window=globalThis;if(!globalThis.self)globalThis.self=globalThis;if(!globalThis.document)globalThis.document={};if(!globalThis.navigator)globalThis.navigator={};if(!globalThis.performance)globalThis.performance={};if(!globalThis.sessionStorage)globalThis.sessionStorage={};if(!globalThis.trustedTypes)globalThis.trustedTypes={};";
        String inject = "globalThis.__solve_n=function(v){var u=" + factory + "('https://youtube.com/watch?v=x','s',undefined);u.set('n',v);var p=Object.getPrototypeOf(u),k=Object.keys(p).concat(Object.getOwnPropertyNames(p));for(var i=0;i<k.length;i++){if(['constructor','set','get','clone'].indexOf(k[i])<0){u[k[i]]();break;}}return u.get('n');};";
        return shim + code.substring(0, anchor) + inject + code.substring(anchor) + ";globalThis.__solve_n(" + quote(value) + ");";
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }
}
