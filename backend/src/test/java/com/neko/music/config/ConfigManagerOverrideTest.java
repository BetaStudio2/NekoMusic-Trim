package com.neko.music.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据库系统设置叠加到配置树上的行为：登记键生效、未登记键被忽略、非法值只跳过该条。
 *
 * <p>这些用例不读磁盘上的 config.yml：{@link ConfigManager#applyOverrides} 在配置树为空时
 * 会现场建树，因此可以直接断言「数据库值能否走到读取端」。</p>
 */
class ConfigManagerOverrideTest {

    @Test
    @DisplayName("登记键：按点分路径合并后读取端立即生效")
    void registeredKeysReachGetters() {
        ConfigManager config = new ConfigManager();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("port", "12345");
        overrides.put("video_render.worker_threads", "7");
        overrides.put("smtp.host", "smtp.example.com");

        config.applyOverrides(overrides);

        assertEquals(12345, config.getPort());
        assertEquals(7, config.getVideoRenderWorkerThreads());
        assertEquals("smtp.example.com", config.getSmtpHost());
    }

    @Test
    @DisplayName("策略项：不在清单里，数据库给什么都不会生效")
    void policyKeysAreNotOverridable() {
        ConfigManager config = new ConfigManager();
        config.applyOverrides(Map.of(
                "video_render.non_vip_max_duration_sec", "99",
                "netease_search_fill.language", "日语",
                "netease_search_fill.upload_user_id", "7",
                "video_render.non_vip_daily_limit", "5"));

        assertEquals(30, config.getVideoRenderNonVipMaxDurationSec(), "策略项不应被数据库值改写");
        assertEquals("", config.getNeteaseFillLanguage());
        assertEquals(null, config.getNeteaseFillUploadUserId());
        assertEquals(5, config.getVideoRenderNonVipDailyLimit(), "同批次的普通项仍应生效");
    }

    @Test
    @DisplayName("出厂默认值补全：文件里缺省的登记键落回出厂默认值")
    void registryDefaultsFillMissingKeys() {
        ConfigManager config = new ConfigManager();
        JsonNode filled = config.withRegistryDefaults(null); // 相当于 config.yml 什么都没有

        assertEquals(65535, filled.get("port").asInt(), "端口应落到出厂默认端口");
        assertEquals("", filled.get("smtp").get("host").asText(), "出厂默认值允许是空串");
        assertEquals("10", filled.get("video_render").get("non_vip_daily_limit").asText(),
                "清单里的默认值要落进配置树");
        assertTrue(filled.get("video_render").get("non_vip_max_duration_sec") == null,
                "策略项不在清单里，不该被补成默认值");

        JsonNode kept = config.withRegistryDefaults(
                JsonNodeFactory.instance.objectNode().put("port", 9999));
        assertEquals(9999, kept.get("port").asInt(), "文件里已有的值不能被默认值覆盖");
    }

    @Test
    @DisplayName("未登记键：不写入配置树，避免管理接口变成任意注入入口")
    void unregisteredKeysAreIgnored() {
        ConfigManager config = new ConfigManager();
        config.applyOverrides(Map.of("mysql.password", "injected"));

        assertEquals("", config.getMysqlPassword());
    }

    @Test
    @DisplayName("非法值：只跳过该条，其余条目仍然生效")
    void invalidValuesAreSkippedIndividually() {
        ConfigManager config = new ConfigManager();
        Map<String, String> overrides = new LinkedHashMap<>();
        overrides.put("port", "not-a-number");
        overrides.put("video_render.worker_threads", "9");

        config.applyOverrides(overrides);

        assertEquals(65535, config.getPort(), "非法值不应改写端口");
        assertEquals(9, config.getVideoRenderWorkerThreads());
    }

    @Test
    @DisplayName("列表项：按行拆成数组，读取端还原为逗号分隔")
    void listValuesBecomeArrays() {
        ConfigManager config = new ConfigManager();
        config.applyOverrides(Map.of("whitelist_email", "a.com\nb.com"));

        assertEquals("a.com,b.com", config.getEmailWhitelist());
        assertEquals("a.com\nb.com", config.effectiveValue("whitelist_email").orElseThrow());
    }

    @Test
    @DisplayName("effectiveValue：键缺失返回 empty，供首次启动回退到出厂默认值")
    void effectiveValueIsEmptyForMissingKeys() {
        ConfigManager config = new ConfigManager();

        assertTrue(config.effectiveValue("smtp.host").isEmpty());
        assertEquals("", config.effectiveValue("smtp.host").orElse(""));
    }

    @Test
    @DisplayName("数值留空：回落到出厂默认值，而不是被当成 0 写进配置树")
    void blankNumericFallsBackToDefault() {
        ConfigManager config = new ConfigManager();
        config.applyOverrides(Map.of("port", "12345"));
        assertEquals(12345, config.getPort());

        config.applyOverrides(Map.of("port", ""));
        assertEquals(65535, config.getPort(), "端口留空应回到默认端口，而不是随机端口");
    }

    @Test
    @DisplayName("重复叠加：后一次的值覆盖前一次，不影响未提交的键")
    void laterOverridesWin() {
        ConfigManager config = new ConfigManager();
        config.applyOverrides(Map.of("port", "10000", "smtp.host", "first.example.com"));
        config.applyOverrides(Map.of("port", "10001"));

        assertEquals(10001, config.getPort());
        assertEquals("first.example.com", config.getSmtpHost(), "未提交的键应保留上一次的值");
    }
}
