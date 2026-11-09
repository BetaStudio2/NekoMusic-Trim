package com.neko.music.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.neko.music.config.ConfigManager;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class NotificationService {
    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final ConfigManager configManager;
    private final String webhookUrl;
    private final String authToken;
    private final CloseableHttpClient httpClient;
    private final ExecutorService asyncExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "webhook-notification");
        thread.setDaemon(true);
        return thread;
    });

    public NotificationService(ConfigManager configManager) {
        this.configManager = configManager;
        String url = configManager.getMsgUrl();
        // 自动添加 http:// 前缀（如果缺少）
        if (url != null && !url.isEmpty() && !url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        // 添加 /send 路径
        if (url != null && !url.isEmpty() && !url.endsWith("/send")) {
            url = url + "/send";
        }
        this.webhookUrl = url;
        this.authToken = configManager.getMsgToken();
        // 创建复用的 HttpClient
        this.httpClient = HttpClients.createDefault();

        logger.info("NotificationService 初始化完成, Webhook URL: {}", webhookUrl);
    }
    
    /**
     * 发送通知
     * @param message 通知消息内容
     * @return 是否发送成功
     */
    public boolean sendNotification(String message) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            logger.warn("Webhook URL 未配置，跳过发送通知");
            return false;
        }
        
        if (authToken == null || authToken.isEmpty()) {
            logger.warn("Auth Token 未配置，跳过发送通知");
            return false;
        }
        
        try {
            // 构建 JSON 请求体
            String jsonBody = String.format("{\"message\": %s}",
                objectMapper.writeValueAsString(message));

            // 创建 POST 请求
            HttpPost httpPost = new HttpPost(webhookUrl);
            httpPost.setHeader("Content-Type", "application/json");
            httpPost.setHeader("Authorization", "Bearer " + authToken);
            httpPost.setEntity(new StringEntity(jsonBody, ContentType.APPLICATION_JSON));

            // 发送请求
            try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
                int statusCode = response.getCode();
                if (statusCode == 200) {
                    logger.info("通知发送成功: {}", message);
                    return true;
                } else {
                    logger.warn("通知发送失败，状态码: {}", statusCode);
                    return false;
                }
            }
        } catch (Exception e) {
            logger.error("发送通知时发生错误: {}", e.getMessage(), e);
            return false;
        }
    }

    /** 异步发送网易云歌词校验失败通知，不阻塞歌曲入库。 */
    public void scheduleNeteaseInvalidLyricsNotification(
            long neteaseSongId,
            int ingestedMusicId,
            String title,
            String artist,
            String rawLyrics,
            String validationReason
    ) {
        asyncExecutor.execute(() -> {
            String lyrics = rawLyrics == null ? "" : rawLyrics;
            if (lyrics.length() > 8_000) {
                lyrics = lyrics.substring(0, 8_000) + "\n...(歌词已截断)";
            }
            String message = "网易云自动爬虫 · 歌词 LRC 校验失败\n"
                    + "歌曲：" + safe(title) + " — " + safe(artist) + "\n"
                    + "曲库 ID：" + ingestedMusicId + "\n"
                    + "网易云 ID：" + neteaseSongId + "\n"
                    + "失败原因：" + safe(validationReason) + "\n\n"
                    + "lrc 原文：\n" + lyrics;
            try {
                sendNotification(message);
            } catch (Exception e) {
                logger.warn("异步发送网易云歌词校验失败 Webhook 异常 neteaseSongId={} musicId={}: {}",
                        neteaseSongId, ingestedMusicId, e.getMessage(), e);
            }
        });
    }

    /** 异步发送网易云登录掉线告警（Cookie 失效 / 被风控），不阻塞巡检线程。 */
    public void scheduleNeteaseLoginOfflineNotification(String reason) {
        asyncExecutor.execute(() -> {
            String message = "网易云登录状态已掉线\n" + safe(reason);
            try {
                sendNotification(message);
            } catch (Exception e) {
                logger.warn("异步发送网易云掉线告警异常: {}", e.getMessage(), e);
            }
        });
    }

    /** 异步发送 QQ 音乐登录掉线告警（Cookie 失效 / 被风控），不阻塞补全线程。 */
    public void scheduleQqLoginOfflineNotification(String reason) {
        asyncExecutor.execute(() -> {
            String message = "QQ 音乐登录状态已掉线\n" + safe(reason);
            try {
                sendNotification(message);
            } catch (Exception e) {
                logger.warn("异步发送 QQ 音乐掉线告警异常: {}", e.getMessage(), e);
            }
        });
    }

    /** 异步发送歌词报错待审核提醒，不阻塞用户提交。 */
    public void scheduleLyricsReportSubmittedNotification(
            long reportId,
            int musicId,
            String title,
            String artist,
            int userId,
            String note
    ) {
        asyncExecutor.execute(() -> {
            String message = "歌词报错待审核\n"
                    + "报错记录：" + reportId + "\n"
                    + "歌曲：" + safe(title) + " — " + safe(artist) + "\n"
                    + "曲库 ID：" + musicId + "\n"
                    + "提交用户 ID：" + userId
                    + (note == null || note.isBlank() ? "" : "\n补充说明：" + safe(note));
            try {
                sendNotification(message);
            } catch (Exception e) {
                logger.warn("异步发送歌词报错提醒异常 reportId={}: {}", reportId, e.getMessage(), e);
            }
        });
    }

    /**
     * 异步发送「来源因反复认证失败被临时封禁」告警，用于让处置可见（不阻塞认证请求）。
     *
     * <p>是否发送由 {@link SourceBanService} 按来源冷却后再决定，这里只负责投递。IP 与入口名称
     * 都不含用户提交内容，直接进消息。</p>
     */
    public void scheduleAuthSourceBanNotification(String clientIp, long durationSeconds, String scope) {
        asyncExecutor.execute(() -> {
            String message = "认证失败来源封禁告警\n"
                    + "来源 IP：" + safe(clientIp) + "\n"
                    + "触发入口：" + safe(scope) + "\n"
                    + "封禁时长：" + durationSeconds + " 秒";
            try {
                sendNotification(message);
            } catch (Exception e) {
                logger.warn("异步发送来源封禁告警异常 ip={}: {}", clientIp, e.getMessage(), e);
            }
        });
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private String sanitizeForNotification(String input) {
        if (input == null) {
            return "";
        }
        String sanitized = input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#x27;");
        // 移除除换行/制表外的控制字符，避免下游渲染异常
        return sanitized.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "");
    }

    /**
     * 发送音乐上传完成通知
     * @param musicTitle 音乐标题
     * @param artist 艺术家
     * @param uploadUserId 上传用户ID
     * @return 是否发送成功
     */
    public boolean sendMusicUploadNotification(String musicTitle, String artist, int uploadUserId) {
        String safeTitle = sanitizeForNotification(musicTitle);
        String safeArtist = sanitizeForNotification(artist);
        String message = String.format("音乐审核提醒\n标题: %s\n艺术家: %s\n上传用户ID: %d",
            safeTitle, safeArtist, uploadUserId);
        return sendNotification(message);
    }
}
