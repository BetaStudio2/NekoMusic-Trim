package com.neko.music.i18n;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Accept-Language} 解析：只认站点支持的语言，其余一律回落源语言。
 *
 * <p>该结果直接决定 API 响应文案用哪种语言，因此把边界
 * （q 值排序、通配、地区变体、无头）单独锁住。</p>
 */
class RequestLocaleTest {

    @AfterEach
    void tearDown() {
        RequestLocale.clear();
    }

    @Test
    void resolvesSupportedLanguages() {
        assertEquals(RequestLocale.EN, RequestLocale.resolve("en"));
        assertEquals(RequestLocale.EN, RequestLocale.resolve("en-US"));
        assertEquals(RequestLocale.EN, RequestLocale.resolve("en-GB,en;q=0.9"));
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("zh"));
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("zh-CN,zh;q=0.9,en;q=0.8"));
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("zh-Hans-CN"));
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("zh-SG"));
        assertEquals("ja", RequestLocale.resolve("ja-JP,ja;q=0.9"));
        assertEquals("ko", RequestLocale.resolve("ko-KR"));
    }

    @Test
    void distinguishesTraditionalChineseFromSimplified() {
        // 繁体/港台变体不能落到 zh-CN：命中比 zh 更长的 zh-hant / zh-tw / zh-hk 前缀
        assertEquals("zh-TW", RequestLocale.resolve("zh-TW"));
        assertEquals("zh-TW", RequestLocale.resolve("zh-Hant-TW"));
        assertEquals("zh-HK", RequestLocale.resolve("zh-HK"));
        assertEquals("zh-TW", RequestLocale.resolve("zh-MO,zh;q=0.9"));
        // 显式写了简体繁体混排时按 q 值取优先级更高的那门
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("zh-Hant;q=0.5,zh-Hans;q=0.9"));
    }

    @Test
    void honorsQualityValuesAndOrder() {
        // en 的 q 更高，即便 zh 排在前面也选英文
        assertEquals(RequestLocale.EN, RequestLocale.resolve("zh;q=0.5,en;q=0.9"));
        // 同 q 时按出现顺序取第一个受支持的
        assertEquals(RequestLocale.EN, RequestLocale.resolve("en;q=0.9,zh;q=0.9"));
        // q=0 表示「明确不接受」，不能当候选
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("en;q=0,zh"));
    }

    @Test
    void fallsBackToDefault() {
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("*"));
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve(""));
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("   "));
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve(null));
        assertEquals(RequestLocale.DEFAULT, RequestLocale.resolve("fr-FR,de;q=0.8"));
    }

    @Test
    void threadLocalDefaultsAndClears() {
        // 不在请求线程里（启动预热、定时任务、单测）必须拿到源语言，而不是 null
        assertEquals(RequestLocale.DEFAULT, RequestLocale.current());
        RequestLocale.set(RequestLocale.EN);
        assertEquals(RequestLocale.EN, RequestLocale.current());
        RequestLocale.set(null);
        assertEquals(RequestLocale.DEFAULT, RequestLocale.current());
        RequestLocale.clear();
        assertEquals(RequestLocale.DEFAULT, RequestLocale.current());
    }
}
