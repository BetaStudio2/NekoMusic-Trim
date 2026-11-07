package com.neko.music.seo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserAgentClassifierTest {

    @Test
    void realBrowsersReceiveSpa() {
        assertFalse(UserAgentClassifier.shouldRenderSeo(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
                true));
    }

    @Test
    void crawlersAndMissingUserAgentReceiveSeoHtml() {
        assertTrue(UserAgentClassifier.shouldRenderSeo(
                "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)", true));
        assertTrue(UserAgentClassifier.shouldRenderSeo(null, true));
    }

    @Test
    void browserLikeUserAgentWithoutBrowserHeadersReceivesSeoHtml() {
        assertTrue(UserAgentClassifier.shouldRenderSeo(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36",
                false));
    }
}
