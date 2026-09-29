package com.yourname.gemini;

import java.net.URI;
import java.net.URISyntaxException;

/** Exact HTTPS origins only; an account page is never a camera/microphone origin. */
final class UrlPolicy {
    static final String HOME_URL = "https://gemini.google.com/";

    private UrlPolicy() { }

    static boolean isInternal(String url) {
        return isSecureHost(url, "gemini.google.com")
                || isSecureHost(url, "accounts.google.com");
    }

    static boolean isGemini(String url) {
        return isSecureHost(url, "gemini.google.com");
    }

    static boolean isWebLink(String url) {
        URI uri = parse(url);
        return uri != null && uri.getHost() != null && uri.getRawUserInfo() == null
                && ("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()));
    }

    private static boolean isSecureHost(String url, String host) {
        URI uri = parse(url);
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && host.equalsIgnoreCase(uri.getHost()) && uri.getRawUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
    }

    private static URI parse(String url) {
        if (url == null) return null;
        try {
            return new URI(url);
        } catch (URISyntaxException ignored) {
            return null;
        }
    }
}
