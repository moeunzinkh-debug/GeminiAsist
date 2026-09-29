package com.yourname.gemini;

import org.junit.Test;
import static org.junit.Assert.*;

public class UrlPolicyTest {
    @Test public void geminiAndAccountsStayInternal() {
        assertTrue(UrlPolicy.isInternal("https://gemini.google.com/app"));
        assertTrue(UrlPolicy.isGemini("HTTPS://GEMINI.GOOGLE.COM:443/"));
        assertTrue(UrlPolicy.isInternal("https://accounts.google.com/ServiceLogin"));
        assertFalse(UrlPolicy.isGemini("https://accounts.google.com/"));
    }

    @Test public void lookalikeAndUntrustedOriginsAreNotInternal() {
        String[] urls = {null, "", "not a url", "http://gemini.google.com/",
                "https://gemini.google.com.evil.test/", "https://evilgemini.google.com/",
                "https://gemini.google.com@evil.test/", "https://evil.test@gemini.google.com/",
                "https://gemini.google.com:8443/", "https://gemini.google.com./",
                "https://accounts.google.com.evil.test/", "https://www.google.com/",
                "file:///sdcard/private.txt", "content://private/data", "javascript:alert(1)",
                "intent://app#Intent;end", "data:text/html,hello", "about:blank"};
        for (String url : urls) {
            assertFalse(String.valueOf(url), UrlPolicy.isInternal(url));
            assertFalse(String.valueOf(url), UrlPolicy.isGemini(url));
        }
    }

    @Test public void onlyOrdinaryWebLinksMayOpenBrowser() {
        assertTrue(UrlPolicy.isWebLink("https://example.org/article"));
        assertTrue(UrlPolicy.isWebLink("http://example.org/"));
        assertFalse(UrlPolicy.isWebLink("intent://example.org/"));
        assertFalse(UrlPolicy.isWebLink("file:///etc/passwd"));
        assertFalse(UrlPolicy.isWebLink("https://user:password@example.org/"));
        assertFalse(UrlPolicy.isWebLink(null));
    }
}
