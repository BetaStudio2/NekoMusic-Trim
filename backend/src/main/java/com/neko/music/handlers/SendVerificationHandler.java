package com.neko.music.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.neko.music.Main;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.NekoAuthAccountService;
import com.neko.music.service.SendVerificationCodeResult;
import com.neko.music.service.UserAuthService;
import com.neko.music.util.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Optional;

@WebServlet("/api/user/send-verification")
public class SendVerificationHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(SendVerificationHandler.class);
    private UserAuthService userAuthService;

    @Override
    public void init() throws ServletException {
        userAuthService = Main.getUserAuthService();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        if (enforceAuthAbuseLimit(request, response, AuthAbuseLimiter.Scope.SEND_CODE)) {
            return;
        }

        logger.info("收到发送验证码请求");

        // 读取请求体
        StringBuilder requestBody = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                requestBody.append(line);
            }
        }

        try {
            JsonNode requestData = Main.getObjectMapper().readTree(requestBody.toString());

            String email = null;
            String nickname = "用户";
            String captchaPassToken = null;
            if (requestData != null) {
                if (requestData.has("email")) {
                    email = requestData.get("email").asText();
                }
                if (requestData.has("nickname")) {
                    nickname = requestData.get("nickname").asText();
                }
                if (requestData.has("captchaPassToken")) {
                    captchaPassToken = requestData.get("captchaPassToken").asText().trim();
                }
            }

            // 验证请求参数
            if (email == null || email.trim().isEmpty()) {
                sendResponse(response, false, "auth.password_reset.email_required", null);
                return;
            }

            // 验证邮箱格式
            if (!isValidEmail(email)) {
                sendResponse(response, false, "auth.password_reset.email_invalid", null);
                return;
            }

            // 来源 IP 发码限流先于一次性通行令牌的消费：被限流时不应白白烧掉用户已经解好的
            // captchaPassToken。单靠滑块 + 同邮箱冷却挡不住「换收件人批量发码」，必须叠加 IP 维度封顶。
            Optional<Long> ipLimited = userAuthService.acquireVerificationIpSlot(
                    ClientIpResolver.clientIp(request));
            if (ipLimited.isPresent()) {
                logger.warn("注册发码超过来源 IP 上限: {}", email);
                sendCooldownResponse(response, ipLimited.get());
                return;
            }

            if (captchaPassToken == null || captchaPassToken.isEmpty()) {
                logger.warn("发送验证码缺少 captchaPassToken IP: {}", request.getRemoteAddr());
                sendResponse(response, false, "captcha.required", null);
                return;
            }
            if (!Main.getSliderCaptchaService().consumePassToken(
                    captchaPassToken,
                    request.getHeader("User-Agent"))) {
                sendResponse(response, false, "captcha.expired_or_used", null);
                return;
            }

            // 检查邮箱是否在白名单中
            if (!userAuthService.isWhitelistedEmail(email)) {
                logger.warn("邮箱不在白名单中: {}", email);
                sendResponse(response, false, "auth.captcha.email_domain_not_allowed", null);
                return;
            }

            SendVerificationCodeResult result = userAuthService.sendVerificationCode(email, nickname);

            if (result.rateLimited()) {
                sendCooldownResponse(response, result.retryAfterSec());
                return;
            }
            if (result.success()) {
                logger.info("验证码发送成功: {}", email);
                sendResponse(response, true, "auth.captcha.sent", null);
            } else {
                logger.error("验证码发送失败: {}", email);
                sendResponse(response, false, "auth.captcha.send_failed", null);
            }

        } catch (NekoAuthAccountService.AccountBridgeException e) {
            if (e.rateLimited()) {
                sendCooldownResponse(response, e.retryAfterSeconds());
            } else if ("EMAIL_TAKEN".equals(e.code())) {
                sendResponse(response, false, "auth.register.email_exists", null);
            } else if ("EMAIL_DOMAIN_NOT_ALLOWED".equals(e.code())) {
                sendResponse(response, false, "auth.captcha.email_domain_not_allowed", null);
            } else if ("REGISTRATION_DISABLED".equals(e.code())) {
                sendResponse(response, false, "auth.register.disabled", null);
            } else if (e.notConfigured() || e.unavailable()) {
                logger.error("NekoAuth 账号桥接不可用: {}", e.code());
                sendResponse(response, false, "auth.service_unavailable", null);
            } else {
                logger.warn("NekoAuth 发码失败: status={} code={}", e.status(), e.code());
                sendResponse(response, false, "auth.captcha.send_failed", null);
            }
        } catch (Exception e) {
            logger.error("处理发送验证码请求时发生错误: {}", e.getMessage(), e);
            sendResponse(response, false, "common.internal_error", null);
        }
    }

    /**
     * 验证邮箱格式
     */
    private boolean isValidEmail(String email) {
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }


    /**
     * 发送JSON响应
     */
}
