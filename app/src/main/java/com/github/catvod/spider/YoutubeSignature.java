package com.github.catvod.spider;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the signature timestamp required by TVHTML5 player requests. */
final class YoutubeSignature {
    private static final Pattern STS = Pattern.compile("(?:signatureTimestamp|sts)[=:]\\s*(\\d{5,})");

    private YoutubeSignature() {}

    static Integer timestamp(String playerCode) {
        if (playerCode == null) return null;
        Matcher matcher = STS.matcher(playerCode);
        if (!matcher.find()) return null;
        try {
            return Integer.parseInt(matcher.group(1));
        } catch (Throwable ignored) {
            return null;
        }
    }
}
