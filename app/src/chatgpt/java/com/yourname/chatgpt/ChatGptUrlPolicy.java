package com.yourname.chatgpt;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Exact HTTPS origins only; an account page is never a camera/microphone origin.
 *
 * <p>Internal (stays inside the WebView) for the ChatGPT tag:
 * <ul>
 *   <li>{@code chatgpt.com} and its subdomains — the product itself;</li>
 *   <li>{@code openai.com} and its subdomains — the sign-in redirect chain
 *       ({@code auth.openai.com}, {@code auth0.openai.com}, legacy
 *       {@code chat.openai.com} URLs, openai.com pages);</li>
 *   <li>{@code accounts.google.com} / {@code appleid.apple.com} — Google and
 *       Apple single sign-on;</li>
 *   <li>{@code challenges.cloudflare.com} — bot check interposed during
 *       sign-in.</li>
 * </ul>
 *
 * <p>Every other link opens in the external browser. Camera, microphone and
 * file uploads are granted to {@code chatgpt.com} origins only.
 */
final class ChatGptUrlPolicy {
    static final String HOME_URL = "https://chatgpt.com/";

    private ChatGptUrlPolicy() { }

    static boolean isInternal(String url) {
        return isSecureSubdomainOf(url, "chatgpt.com")
                || isSecureSubdomainOf(url, "openai.com")
                || isSecureHost(url, "accounts.google.com")
                || isSecureHost(url, "appleid.apple.com")
                || isSecureHost(url, "challenges.cloudflare.com");
    }

    /** The only origin allowed to use the camera, microphone and uploads. */
    static boolean isChatGpt(String url) {
        return isSecureSubdomainOf(url, "chatgpt.com");
    }

    static boolean isWebLink(String url) {
        URI uri = parse(url);
        return uri != null && uri.getHost() != null
                && uri.getRawUserInfo() == null
                && ("https".equalsIgnoreCase(uri.getScheme())
                || "http".equalsIgnoreCase(uri.getScheme()));
    }

    /** True for {@code https://domain/} and any {@code https://sub.domain/}. */
    private static boolean isSecureSubdomainOf(String url, String domain) {
        URI uri = parse(url);
        if (uri == null
                || !"https".equalsIgnoreCase(uri.getScheme())
                || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        String host = uri.getHost();
        if (host == null) return false;
        host = host.toLowerCase(java.util.Locale.ROOT);
        return host.equals(domain) || host.endsWith("." + domain);
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
