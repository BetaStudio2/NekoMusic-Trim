package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.activation.DataHandler;
import javax.mail.*;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import javax.mail.util.ByteArrayDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public class EmailService {
    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);
    private final ConfigManager configManager;
    private final String reviewTemplate;
    private final String reviewApprovedTemplate;
    private final String videoRenderCompleteTemplate;
    private final String neteaseLyricsValidationFailedTemplate;

    /** 超过此长度时正文不内嵌歌词，改为 UTF-8 附件（不截断） */
    private static final int NETEASE_LYRICS_INLINE_MAX_CHARS = 48_000;

    /** SMTP 网络超时：避免异常邮件服务器长时间占住异步发送线程。 */
    private static final String SMTP_CONNECTION_TIMEOUT_MS = "5000";
    private static final String SMTP_READ_TIMEOUT_MS = "10000";
    private static final String SMTP_WRITE_TIMEOUT_MS = "10000";

    /** 邮件正文中「审核时间 / 生成时间 / 通知时间」统一格式。 */
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static String nowTimestamp() {
        return LocalDateTime.now().format(TIMESTAMP_FORMAT);
    }

    private static final ExecutorService ASYNC_MAIL_EXECUTOR = Executors.newSingleThreadExecutor(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "email-async-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });

    public EmailService(ConfigManager configManager) {
        this.configManager = configManager;
        this.reviewTemplate = loadTemplate("Review.html",
                "无法加载审核邮件模板 Review.html", "加载审核邮件模板失败", getDefaultReviewTemplate());
        this.reviewApprovedTemplate = loadTemplate("ReviewApproved.html",
                "无法加载审核通过邮件模板 ReviewApproved.html", "加载审核通过邮件模板失败",
                getDefaultReviewApprovedTemplate());
        this.videoRenderCompleteTemplate = loadTemplate("VideoRenderComplete.html",
                "无法加载邮件模板 VideoRenderComplete.html", "加载视频渲染完成邮件模板失败",
                getDefaultVideoRenderCompleteTemplate());
        this.neteaseLyricsValidationFailedTemplate = loadTemplate("NeteaseLyricsValidationFailed.html",
                "无法加载邮件模板 NeteaseLyricsValidationFailed.html", "加载网易云歌词校验失败邮件模板失败",
                getDefaultNeteaseLyricsValidationFailedTemplate());
    }

    /** 从 classpath 读取 UTF-8 模板；缺失或读取失败时记录日志并回退到内置默认模板。 */
    private String loadTemplate(String resource, String missingLogMessage,
                                String failureLogMessage, String defaultTemplate) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (is == null) {
                logger.error(missingLogMessage);
                return defaultTemplate;
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error(failureLogMessage, e);
            return defaultTemplate;
        }
    }

    /**
     * 默认审核邮件模板
     */
    private String getDefaultReviewTemplate() {
        return "<!DOCTYPE html>" +
               "<html><head><meta charset='UTF-8'><title>审核结果</title></head>" +
               "<body>" +
               "<h2>您好，</h2>" +
               "<p>您的音乐《{{musicName}}》审核未通过。</p>" +
               "<p>拒绝原因：{{rejectReason}}</p>" +
               "<p>审核时间：{{auditDate}}</p>" +
               "</body></html>";
    }

    /**
     * 默认审核通过邮件模板
     */
    private String getDefaultReviewApprovedTemplate() {
        return "<!DOCTYPE html>" +
               "<html><head><meta charset='UTF-8'><title>审核结果</title></head>" +
               "<body>" +
               "<h2>您好，</h2>" +
               "<p>恭喜！您的音乐《{{musicName}}》已通过审核。</p>" +
               "<p>您的音乐已添加到 NekoMusic 音乐库中。</p>" +
               "<p>审核时间：{{auditDate}}</p>" +
               "</body></html>";
    }

    private String getDefaultVideoRenderCompleteTemplate() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body>"
                + "<h2>分享视频已生成</h2>"
                + "<p>《{{musicName}}》 · {{artistName}}</p>"
                + "<p>时长约 {{durationSec}} 秒</p>"
                + "<p><a href=\"{{downloadUrl}}\">前往下载 MP4</a></p>"
                + "<p>生成时间：{{completedAt}}</p>"
                + "</body></html>";
    }

    /**
     * 视频渲染完成通知（HTML 邮件，含下载页链接）。
     */
    public boolean sendVideoRenderCompleteEmail(String toEmail, String musicName, String artistName,
                                                double durationSec, String downloadUrl, boolean watermarked) {
        String completedAt = nowTimestamp();
        String durationText = String.format(java.util.Locale.ROOT, "%.0f", durationSec);

        String content = videoRenderCompleteTemplate
                .replace("{{musicName}}", escapeHtml(musicName))
                .replace("{{artistName}}", escapeHtml(artistName))
                .replace("{{durationSec}}", escapeHtml(durationText))
                .replace("{{downloadUrl}}", escapeHtml(downloadUrl))
                .replace("{{completedAt}}", escapeHtml(completedAt));
        if (watermarked) {
            content = content.replace("style=\"{{showWatermarkNote}}\"", "");
        } else {
            content = content.replace("style=\"{{showWatermarkNote}}\"", "style=\"display:none\"");
        }

        String subject = "NekoMusic - 分享视频已生成";
        return sendEmail(toEmail, subject, content);
    }

    private static String escapeHtml(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String getDefaultNeteaseLyricsValidationFailedTemplate() {
        return "<!DOCTYPE html><html><head><meta charset='UTF-8'></head><body>"
                + "<h2>网易云歌词 LRC 校验失败</h2>"
                + "<p>{{title}} — {{artist}} | 曲库 ID: {{musicId}} | 网易云 ID: {{songId}}</p>"
                + "<p><strong>原因：</strong>{{reason}}</p>"
                + "{{lyricsBlock}}"
                + "<p>{{notifiedAt}}</p>"
                + "</body></html>";
    }

    /**
     * 入库完成后异步发送：网易云歌词 LRC 校验失败通知（单封邮件 BCC 群发所有管理员）。
     */
    public void scheduleNeteaseInvalidLyricsAlertToAdmins(
            long neteaseSongId,
            int ingestedMusicId,
            String title,
            String artist,
            String rawLyrics,
            String validationReason
    ) {
        ASYNC_MAIL_EXECUTOR.execute(() -> {
            try {
                sendNeteaseInvalidLyricsAlertToAdmins(
                        neteaseSongId, ingestedMusicId, title, artist, rawLyrics, validationReason);
            } catch (Exception e) {
                logger.warn("异步发送网易云歌词校验失败邮件异常 neteaseSongId={} musicId={}: {}",
                        neteaseSongId, ingestedMusicId, e.getMessage(), e);
            }
        });
    }

    /**
     * 单封邮件 BCC 群发给数据库中所有已启用管理员。
     *
     * @return 是否发送成功
     */
    public boolean sendNeteaseInvalidLyricsAlertToAdmins(
            long neteaseSongId,
            int ingestedMusicId,
            String title,
            String artist,
            String rawLyrics,
            String validationReason
    ) {
        List<String> recipients = new ArrayList<>(new LinkedHashSet<>(Main.getAdminDatabaseManager().getActiveAdminEmails()));
        if (recipients.isEmpty()) {
            logger.warn("网易云歌词校验失败，但无可用管理员邮箱，跳过邮件通知 neteaseSongId={} musicId={}",
                    neteaseSongId, ingestedMusicId);
            return false;
        }

        String raw = rawLyrics == null ? "" : rawLyrics;
        String attachmentFileName = buildNeteaseLyricsAttachmentFileName(title, neteaseSongId);
        byte[] attachmentBytes = raw.isEmpty() ? null : raw.getBytes(StandardCharsets.UTF_8);
        String lyricsBlock = "<pre>" + escapeHtml(raw) + "</pre>";
        if (raw.length() > NETEASE_LYRICS_INLINE_MAX_CHARS) {
            lyricsBlock += "<p>（正文较长，相同内容另附附件 <strong>" + escapeHtml(attachmentFileName)
                    + "</strong> 备份，未截断）</p>";
        }

        String notifiedAt = nowTimestamp();
        String content = neteaseLyricsValidationFailedTemplate
                .replace("{{musicId}}", escapeHtml(String.valueOf(ingestedMusicId)))
                .replace("{{songId}}", escapeHtml(String.valueOf(neteaseSongId)))
                .replace("{{title}}", escapeHtml(nullToEmpty(title)))
                .replace("{{artist}}", escapeHtml(nullToEmpty(artist)))
                .replace("{{reason}}", escapeHtml(nullToEmpty(validationReason)))
                .replace("{{lyricsBlock}}", lyricsBlock)
                .replace("{{notifiedAt}}", escapeHtml(notifiedAt));

        String subject = "NekoMusic - 网易云补全歌词校验失败 · " + nullToEmpty(title);
        boolean ok = sendEmailBroadcast(recipients, subject, content, attachmentFileName, attachmentBytes);
        logger.info("网易云歌词校验失败邮件(BCC 群发): neteaseSongId={} musicId={} 收件人={} 附件={} 成功={}",
                neteaseSongId, ingestedMusicId, recipients.size(), attachmentBytes != null, ok);
        return ok;
    }

    private static String buildNeteaseLyricsAttachmentFileName(String title, long neteaseSongId) {
        String safe = nullToEmpty(title).replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_").strip();
        if (safe.isEmpty()) {
            safe = "unknown";
        }
        if (safe.length() > 80) {
            safe = safe.substring(0, 80);
        }
        return "netease-lyrics-" + neteaseSongId + "-" + safe + ".lrc";
    }

    /**
     * 一封邮件、BCC 抄送所有收件人（发件人地址作为唯一 To，兼容多数 SMTP）。
     */
    private boolean sendEmailBroadcast(
            List<String> bccRecipients,
            String subject,
            String htmlContent,
            String attachmentFileName,
            byte[] attachmentBytes
    ) {
        if (bccRecipients == null || bccRecipients.isEmpty()) {
            return false;
        }
        try {
            Session session = createSession();

            String from = configManager.getSmtpUsername();
            MimeMessage message = new MimeMessage(session);
            message.setFrom(new InternetAddress(from));
            message.setRecipient(Message.RecipientType.TO, new InternetAddress(from));
            InternetAddress[] bcc = new InternetAddress[bccRecipients.size()];
            for (int i = 0; i < bccRecipients.size(); i++) {
                bcc[i] = new InternetAddress(bccRecipients.get(i).trim());
            }
            message.setRecipients(Message.RecipientType.BCC, bcc);
            message.setSubject(subject, "UTF-8");

            MimeMultipart multipart = new MimeMultipart();
            MimeBodyPart htmlPart = new MimeBodyPart();
            htmlPart.setContent(htmlContent, "text/html; charset=utf-8");
            multipart.addBodyPart(htmlPart);

            if (attachmentBytes != null && attachmentBytes.length > 0 && attachmentFileName != null) {
                MimeBodyPart attachPart = new MimeBodyPart();
                attachPart.setDataHandler(new DataHandler(
                        new ByteArrayDataSource(attachmentBytes, "text/plain; charset=UTF-8")));
                attachPart.setFileName(MimeUtility.encodeText(attachmentFileName, "UTF-8", "B"));
                attachPart.setDisposition(MimeBodyPart.ATTACHMENT);
                multipart.addBodyPart(attachPart);
            }

            message.setContent(multipart);
            Transport.send(message);
            logger.info("群发邮件已发送: BCC {} 人, subject={}, attachment={}",
                    bccRecipients.size(), subject, attachmentFileName != null);
            return true;
        } catch (Exception e) {
            logger.error("群发邮件失败: {}", e.getMessage(), e);
            return false;
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * 发送审核拒绝邮件
     */
    public boolean sendReviewRejectedEmail(String toEmail, String musicName, String artistName, String rejectReason) {
        // 使用缓存的模板
        
        // 格式化日期
        String auditDate = nowTimestamp();
        
        // 替换模板变量
        String content = reviewTemplate
            .replace("{{musicName}}", escapeHtml(musicName))
            .replace("{{artistName}}", escapeHtml(artistName))
            .replace("{{rejectReason}}", escapeHtml(rejectReason))
            .replace("{{auditDate}}", auditDate);
        
        // 如果有拒绝原因则显示，否则隐藏
        if (rejectReason == null || rejectReason.isEmpty()) {
            content = content.replace("style=\"{{showReason}}\"", "style=\"display:none\"");
        } else {
            content = content.replace("style=\"{{showReason}}\"", "");
        }

        String subject = "NekoMusic - 审核结果通知";
        return sendEmail(toEmail, subject, content);
    }

    /** 异步发送审核拒绝邮件，不阻塞审核接口响应。 */
    public void scheduleReviewRejectedEmail(String toEmail, String musicName, String artistName, String rejectReason) {
        scheduleReviewEmail("拒绝", toEmail, () -> sendReviewRejectedEmail(
                toEmail, musicName, artistName, rejectReason));
    }

    /**
     * 发送审核通过邮件
     */
    public boolean sendReviewApprovedEmail(String toEmail, String musicName, String artistName) {
        // 使用缓存的模板
        
        // 格式化日期
        String auditDate = nowTimestamp();
        
        // 替换模板变量
        String content = reviewApprovedTemplate
            .replace("{{musicName}}", escapeHtml(musicName))
            .replace("{{artistName}}", escapeHtml(artistName))
            .replace("{{auditDate}}", auditDate);

        String subject = "NekoMusic - 审核结果通知";
        return sendEmail(toEmail, subject, content);
    }

    /** 异步发送审核通过邮件，不阻塞审核接口响应。 */
    public void scheduleReviewApprovedEmail(String toEmail, String musicName, String artistName) {
        scheduleReviewEmail("通过", toEmail, () -> sendReviewApprovedEmail(toEmail, musicName, artistName));
    }

    /** 审核邮件统一进入单线程异步队列，避免并发建连触发 SMTP 限流。 */
    private void scheduleReviewEmail(String result, String toEmail, BooleanSupplier sender) {
        try {
            ASYNC_MAIL_EXECUTOR.execute(() -> {
                try {
                    if (sender.getAsBoolean()) {
                        logger.info("审核{}邮件已发送至: {}", result, toEmail);
                    } else {
                        logger.warn("审核{}邮件发送失败: {}", result, toEmail);
                    }
                } catch (Exception e) {
                    logger.error("异步发送审核{}邮件异常: {}", result, toEmail, e);
                }
            });
        } catch (RuntimeException e) {
            logger.error("提交审核{}邮件异步任务失败: {}", result, toEmail, e);
        }
    }

    /** 按当前 SMTP 配置构造会话（SSL/TLS 与认证方式与原各发送方法内联写法一致）。 */
    private Session createSession() {
        Properties props = new Properties();
        props.put("mail.smtp.host", configManager.getSmtpHost());
        props.put("mail.smtp.port", String.valueOf(configManager.getSmtpPort()));
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.connectiontimeout", SMTP_CONNECTION_TIMEOUT_MS);
        props.put("mail.smtp.timeout", SMTP_READ_TIMEOUT_MS);
        props.put("mail.smtp.writetimeout", SMTP_WRITE_TIMEOUT_MS);
        if (configManager.isSmtpSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
        } else if (configManager.isSmtpTls()) {
            props.put("mail.smtp.starttls.enable", "true");
        }
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(
                        configManager.getSmtpUsername(),
                        configManager.getSmtpPassword()
                );
            }
        });
    }

    /**
     * 发送邮件
     */
    private boolean sendEmail(String to, String subject, String content) {
        try {
            Session session = createSession();

            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(configManager.getSmtpUsername()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to));
            message.setSubject(subject);
            message.setContent(content, "text/html; charset=utf-8");

            Transport.send(message);
            logger.info("邮件已发送至: {}", to);
            return true;
        } catch (Exception e) {
            logger.error("发送邮件失败: {}", e.getMessage(), e);
            return false;
        }
    }

}
