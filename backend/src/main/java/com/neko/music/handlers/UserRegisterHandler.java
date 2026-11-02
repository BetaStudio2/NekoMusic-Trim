package com.neko.music.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.neko.music.Main;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.NekoAuthAccountService;
import com.neko.music.service.SourceBanService;
import com.neko.music.service.UserAuthService;
import com.neko.music.util.ClientIpResolver;
import com.neko.music.util.NicknameValidator;
import jakarta.servlet.annotation.WebServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Map;

@WebServlet("/api/user/register")
public class UserRegisterHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(UserRegisterHandler.class);
    private UserAuthService userAuthService;
    private SourceBanService sourceBanService;

    @Override
    public void init() throws ServletException {
        userAuthService = Main.getUserAuthService();
        sourceBanService = Main.getSourceBanService();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");
        if (enforceAuthAbuseLimit(request, response, AuthAbuseLimiter.Scope.REGISTER)) {
            return;
        }

        String clientIp = ClientIpResolver.clientIp(request);

        StringBuilder requestBody = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                requestBody.append(line);
            }
        }

        try {
            JsonNode requestData = Main.getObjectMapper().readTree(requestBody.toString());
            if (requestData == null) {
                sendResponse(response, false, "auth.register.body_required", null);
                return;
            }

            String nickname = requestData.has("nickname") ? requestData.get("nickname").asText().trim() : null;
            String password = requestData.has("password") ? requestData.get("password").asText() : null;
            String email = requestData.has("email") ? requestData.get("email").asText().trim() : null;
            String verificationCode = requestData.has("verificationCode") ? requestData.get("verificationCode").asText().trim() : null;

            // 1. 基础非空校验
            if (isEmpty(nickname) || isEmpty(password) || isEmpty(email)) {
                sendResponse(response, false, "auth.register.fields_required", null);
                return;
            }

            // 2. 邮箱验证码
            if (isEmpty(verificationCode)) {
                logger.warn("检测到恶意注册尝试：未提供验证码。IP: {}", request.getRemoteAddr());
                recordAuthFailure(clientIp, "register");
                sendResponse(response, false, "auth.register.captcha_required", null);
                return;
            }

            // 3. 邮箱格式校验；域名白名单由 NekoAuth 后端统一判断
            if (!isValidEmail(email)) {
                sendResponse(response, false, "auth.register.email_invalid", null);
                return;
            }

            // 4. 昵称与密码长度/合规校验
            String nicknameError = NicknameValidator.validate(nickname);
            if (nicknameError != null) {
                sendResponse(response, false, nicknameError, null);
                return;
            }
            if (password.length() < 6 || password.length() > 30) {
                sendResponse(response, false, "auth.register.password_length_invalid", null);
                return;
            }

            // 5. 验证码、昵称与账号创建都由 NekoAuth 处理；本地只保存业务资料
            boolean success = userAuthService.registerUserWithCode(
                    nickname, password, email, verificationCode);
            if (success) {
                logger.info("用户注册成功: {}", nickname);
                sendResponse(response, true, "auth.register.ok", Map.of("nickname", nickname));
            } else {
                recordAuthFailure(clientIp, "register");
                sendResponse(response, false, "auth.register.captcha_invalid", null);
            }

        } catch (NekoAuthAccountService.AccountBridgeException e) {
            if (isVerificationFailure(e)) {
                // 验证码是可猜的短秘密：失败同样按来源计入递增退避封禁，防「换邮箱猜码」
                recordAuthFailure(clientIp, "register");
                sendResponse(response, false, "auth.register.captcha_invalid", null);
            } else if (e.rateLimited()) {
                sendCooldownResponse(response, e.retryAfterSeconds());
            } else if ("EMAIL_TAKEN".equals(e.code())) {
                sendResponse(response, false, "auth.register.email_exists", null);
            } else if ("EMAIL_DOMAIN_NOT_ALLOWED".equals(e.code())) {
                sendResponse(response, false, "auth.register.email_domain_invalid", null);
            } else if ("REGISTRATION_DISABLED".equals(e.code())) {
                sendResponse(response, false, "auth.register.disabled", null);
            } else if ("WEAK_PASSWORD".equals(e.code())) {
                sendResponse(response, false, "auth.register.password_length_invalid", null);
            } else if (e.notConfigured() || e.unavailable()) {
                logger.error("NekoAuth 账号桥接不可用: {}", e.code());
                sendResponse(response, false, "auth.service_unavailable", null);
            } else {
                logger.warn("NekoAuth 注册失败: status={} code={}", e.status(), e.code());
                sendResponse(response, false, "auth.register.internal_error", null);
            }
        } catch (Exception e) {
            logger.error("注册处理异常: {}", e.getMessage(), e);
            sendResponse(response, false, "auth.register.internal_error", null);
        }
    }

    /** 计一次来源认证失败（未初始化时静默跳过，避免把注册链路打死）。 */
    private void recordAuthFailure(String clientIp, String scope) {
        if (sourceBanService != null) {
            sourceBanService.recordAuthFailure(clientIp, scope);
        }
    }

    private boolean isEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    private static boolean isVerificationFailure(NekoAuthAccountService.AccountBridgeException e) {
        return e.status() == 400 && e.code().startsWith("EMAIL_VERIFICATION_");
    }

    private boolean isValidEmail(String email) {
        return email.matches("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    }

}
