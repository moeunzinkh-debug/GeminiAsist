package com.yourname.chatgpt;

import org.junit.Test;
import static org.junit.Assert.*;

public class ChatGptUrlPolicyTest {
    @Test public void chatgptAndSignInHostsStayInternal() {
        assertTrue(ChatGptUrlPolicy.isInternal("https://chatgpt.com/c/abc"));
        assertTrue(ChatGptUrlPolicy.isChatGpt("HTTPS://CHATGPT.COM:443/"));
        assertTrue(ChatGptUrlPolicy.isInternal("https://www.chatgpt.com/"));
        assertTrue(ChatGptUrlPolicy.isChatGpt("https://chatgpt.com/"));
        assertTrue(ChatGptUrlPolicy.isInternal("https://auth.openai.com/authorize"));
        assertTrue(ChatGptUrlPolicy.isInternal("https://chat.openai.com/"));
        assertTrue(ChatGptUrlPolicy.isInternal("https://accounts.google.com/ServiceLogin"));
        assertTrue(ChatGptUrlPolicy.isInternal("https://appleid.apple.com/auth"));
        assertTrue(ChatGptUrlPolicy.isInternal("https://challenges.cloudflare.com/"));
        assertFalse(ChatGptUrlPolicy.isChatGpt("https://accounts.google.com/"));
        assertFalse(ChatGptUrlPolicy.isChatGpt("https://auth.openai.com/"));
    }

    @Test public void lookalikeAndUntrustedOriginsAreNotInternal() {
        String[] urls = {null, "", "not a url", "http://chatgpt.com/",
                "https://chatgpt.com.evil.test/", "https://evilchatgpt.com/",
                "https://chatgpt.com@evil.test/", "https://evil.test@chatgpt.com/",
                "https://chatgpt.com:8443/", "https://chatgpt.com./",
                "https://openai.com.evil.test/", "https://www.google.com/",
                "file:///sdcard/private.txt", "content://private/data", "javascript:alert(1)",
                "intent://app#Intent;end", "data:text/html,hello", "about:blank"};
        for (String url : urls) {
            assertFalse(String.valueOf(url), ChatGptUrlPolicy.isInternal(url));
            assertFalse(String.valueOf(url), ChatGptUrlPolicy.isChatGpt(url));
        }
    }

    @Test public void onlyOrdinaryWebLinksMayOpenBrowser() {
        assertTrue(ChatGptUrlPolicy.isWebLink("https://example.org/article"));
        assertTrue(ChatGptUrlPolicy.isWebLink("http://example.org/"));
        assertFalse(ChatGptUrlPolicy.isWebLink("intent://example.org/"));
        assertFalse(ChatGptUrlPolicy.isWebLink("file:///etc/passwd"));
        assertFalse(ChatGptUrlPolicy.isWebLink("https://user:password@example.org/"));
        assertFalse(ChatGptUrlPolicy.isWebLink(null));
    }
}
