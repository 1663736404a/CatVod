package com.github.catvod.spider;

import android.net.Uri;
import android.text.TextUtils;

/** Applies a solved n value to the SABR endpoint. */
final class YoutubeNsig {
    private YoutubeNsig() {}

    static String replace(String url, String solved) {
        if (TextUtils.isEmpty(url) || TextUtils.isEmpty(solved)) return url;
        Uri uri = Uri.parse(url);
        if (TextUtils.isEmpty(uri.getQueryParameter("n"))) return url;
        return uri.buildUpon().clearQuery().encodedQuery(rebuildQuery(uri, solved)).build().toString();
    }

    static boolean needsSolve(String url) {
        return !TextUtils.isEmpty(url) && !TextUtils.isEmpty(Uri.parse(url).getQueryParameter("n"));
    }

    private static String rebuildQuery(Uri uri, String solved) {
        StringBuilder query = new StringBuilder();
        for (String name : uri.getQueryParameterNames()) {
            for (String value : uri.getQueryParameters(name)) {
                if (query.length() > 0) query.append('&');
                query.append(Uri.encode(name)).append('=').append(Uri.encode("n".equals(name) ? solved : value));
            }
        }
        return query.toString();
    }
}
