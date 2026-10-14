package com.yourname.voicestudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoiceStudioUrlPolicyTest {
    @Test public void theSpaceAndItsEndpointsStayInternal() {
        assertTrue(VoiceStudioUrlPolicy.isInternal("https://takumi0002-tts-kh.hf.space/"));
        assertTrue(VoiceStudioUrlPolicy.isVoiceStudio("HTTPS://TAKUMI0002-TTS-KH.HF.SPACE:443/"));
        assertTrue(VoiceStudioUrlPolicy.isVoiceStudio("https://takumi0002-tts-kh.hf.space/gradio_api/queue/join"));
        assertTrue(VoiceStudioUrlPolicy.isInternal("https://takumi0002-tts-kh.hf.space/gradio_api/file=/tmp/gradio/audio.mp3"));
    }

    @Test public void lookalikeAndUntrustedOriginsAreNotInternal() {
        String[] urls = {null, "", "not a url", "http://takumi0002-tts-kh.hf.space/",
                "https://takumi0002-tts-kh.hf.space.evil.test/", "https://eviltakumi0002-tts-kh.hf.space/",
                "https://takumi0002-tts-kh.hf.space@evil.test/", "https://evil.test@takumi0002-tts-kh.hf.space/",
                "https://takumi0002-tts-kh.hf.space:8443/", "https://takumi0002-tts-kh.hf.space./",
                "https://hf.space/", "https://other-space.hf.space/", "https://huggingface.co/spaces",
                "https://www.google.com/", "file:///sdcard/private.txt", "content://private/data",
                "javascript:alert(1)", "intent://app#Intent;end", "data:text/html,hello", "about:blank"};
        for (String url : urls) {
            assertFalse(String.valueOf(url), VoiceStudioUrlPolicy.isInternal(url));
            assertFalse(String.valueOf(url), VoiceStudioUrlPolicy.isVoiceStudio(url));
        }
    }

    @Test public void onlyOrdinaryWebLinksMayOpenBrowser() {
        assertTrue(VoiceStudioUrlPolicy.isWebLink("https://example.org/article"));
        assertTrue(VoiceStudioUrlPolicy.isWebLink("http://example.org/"));
        assertFalse(VoiceStudioUrlPolicy.isWebLink("intent://example.org/"));
        assertFalse(VoiceStudioUrlPolicy.isWebLink("file:///etc/passwd"));
        assertFalse(VoiceStudioUrlPolicy.isWebLink("blob:https://takumi0002-tts-kh.hf.space/9d1f"));
        assertFalse(VoiceStudioUrlPolicy.isWebLink("https://user:password@example.org/"));
        assertFalse(VoiceStudioUrlPolicy.isWebLink(null));
    }
}
