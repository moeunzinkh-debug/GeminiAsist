package com.yourname.voicestudio;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Exact HTTPS origin only — the one Hugging Face Space this tag wraps.
 *
 * <p>Internal (stays inside the WebView) for the Voice Studio tag:
 * <ul>
 *   <li>{@code takumi0002-tts-kh.hf.space} — the SRT Smart Voice Studio
 *       (Gradio) page itself, including its {@code /gradio_api/} endpoints.</li>
 * </ul>
 *
 * <p>Every other link opens in the external browser. The microphone and file
 * uploads, plus MP3/SRT downloads, are granted to that one origin only: the
 * page is an anonymous, login-free Space, so nothing else has to be trusted.
 */
final class VoiceStudioUrlPolicy {
    static final String HOME_URL = "https://takumi0002-tts-kh.hf.space/";
    /** The Space host, without scheme or trailing slash. */
    static final String SPACE_HOST = "takumi0002-tts-kh.hf.space";

    private VoiceStudioUrlPolicy() { }

    static boolean isInternal(String url) {
        return isVoiceStudio(url);
    }

    /** The only origin allowed to record audio, upload files and export files. */
    static boolean isVoiceStudio(String url) {
        return isSecureHost(url, SPACE_HOST);
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
