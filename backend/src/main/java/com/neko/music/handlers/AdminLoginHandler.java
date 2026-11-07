package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.neko.music.Main;
import com.neko.music.model.Admin;
import com.neko.music.service.AdminAuthService;
import com.neko.music.service.AdminLoginThrottleService;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.LoginThrottleService;
import com.neko.music.service.SourceBanService;
import com.neko.music.util.ClientIpResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 管理端登录。
 *
 * <p>三条防线缺一不可：</p>
 * <ul>
 *   <li>口令校验等时（{@link AdminAuthService#authenticateResolved}）——用户名不存在时同样跑一次
 *       同成本 Argon2，杜绝「秒回 401」这个用户名枚举时间侧信道；</li>
 *   <li>失败限流与锁定（{@link AdminLoginThrottleService}）——等时校验会让不存在的用户名也消耗
 *       Argon2，必须配合限流，同时阻断慢速口令爆破。账号维度按解析出的管理员 ID 分桶，因此
 *       数据库排序规则如何折叠（大小写 / 全角 / 兼容 / 重音）都不会被拆成多个空桶；计数只与请求
 *       有关、与账号是否存在无关，因此不会重新引出枚举面；</li>
 *   <li>来源请求限流（{@link AuthAbuseLimiter}）——与账号无关的请求速率闸门：脚本用不同用户名平铺
 *       请求时失败计数不会触发，只有它兜得住。防线<b>不依赖</b>前置门禁，门禁被
 *       绕过时仍独立生效。</li>
 *   <li>来源递增退避封禁（{@link SourceBanService}）——反复失败的来源被临时封禁，且复发代价逐级
 *       加长；同为「只看请求、不看账号存在性」，因此也不构成枚举信号。</li>
 * </ul>
 */
public class AdminLoginHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(AdminLoginHandler.class);

    private AdminAuthService adminAuthService;
    private AdminLoginThrottleService throttleService;
    private SourceBanService sourceBanService;

    @Override
    public void init() throws ServletException {
        adminAuthService = Main.getAdminAuthService();
        throttleService = Main.getAdminLoginThrottleService();
        sourceBanService = Main.getSourceBanService();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        response.setContentType("application/json;charset=UTF-8");

        // 处理预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // 与账号无关的来源请求速率闸门：脚本用不同用户名平铺请求时失败计数不会触发，只有它兜得住。
        // 该防线不依赖前置门禁，门禁被绕过时仍独立生效。
        if (enforceAuthAbuseLimit(request, response, AuthAbuseLimiter.Scope.LOGIN)) {
            return;
        }

        try {
            // 读取请求体
            String requestBody = readBody(request);

            // 解析JSON请求体
            Map<String, String> requestData = Main.getObjectMapper().readValue(requestBody.toString(), Map.class);
            String username = requestData.get("username");
            String password = requestData.get("password");

            // 验证参数
            if (username == null || password == null || username.trim().isEmpty() || password.trim().isEmpty()) {
                sendErrorResponse(response, 400, "admin.login.credentials_required");
                return;
            }

            String name = username.trim();
            String clientIp = ClientIpResolver.clientIp(request);

            // init() 未执行时（理论上不会）退回全局单例，保证行为不变
            AdminAuthService authService = adminAuthService != null ? adminAuthService : Main.getAdminAuthService();

            // 先按与认证完全相同的路径解析账号：账号维度按服务端身份（管理员 ID）分桶。数据库排序
            // 规则如何折叠（大小写 / 全角 / 兼容 / 重音）、提交值如何变形，都会解析到同一 ID，因而
            // 共享同一计数桶——不再要求应用层复刻排序规则的等价类（CWE-178）。
            Optional<Admin> resolved = authService.findByUsername(name);
            String accountBucket = LoginThrottleService.accountBucket(resolved.map(Admin::id), name);

            // 原子计数必须早于口令校验：并发突发时每个请求拿到互不相同的计数，超过阈值的请求
            // 直接在这里被拒，不会出现「都读到未超限 → 全部放行」的竞态；被锁定后对「存在的用户名」
            // 与「不存在的用户名」返回完全一致。
            if (throttleService != null) {
                Optional<Long> locked = throttleService.beginAttempt(accountBucket, clientIp);
                if (locked.isPresent()) {
                    logger.warn("管理端登录已被限流: username={} ip={}", name, clientIp);
                    long retryAfterSec = locked.get();
                    response.setHeader("Retry-After", String.valueOf(retryAfterSec));
                    sendErrorResponse(response, 429,
                            ApiMessages.translate("auth.login.too_many_attempts", retryAfterSec));
                    return;
                }
            }

            // 验证管理员凭据（复用已解析结果；账号不存在时仍跑等时陪跑）
            Optional<Admin> adminOpt = authService.authenticateResolved(resolved, password);

            if (adminOpt.isPresent()) {
                Admin admin = adminOpt.get();

                if (throttleService != null) {
                    throttleService.recordSuccess(accountBucket, clientIp);
                }
                if (sourceBanService != null) {
                    sourceBanService.recordAuthSuccess(clientIp);
                }

                // 创建会话令牌
                String sessionToken = authService.createAdminSession(admin);
                if (sessionToken == null) {
                    sendErrorResponse(response, 500, "admin.login.create_session_failed");
                    return;
                }
                
                // 创建成功响应
                Map<String, Object> successResponse = new HashMap<>();
                successResponse.put("success", true);
                successResponse.put("message", ApiMessages.translate("auth.login.ok"));
                successResponse.put("token", sessionToken);  // 返回会话令牌
                successResponse.put("admin", Map.of(
                    "id", admin.id(),
                    "username", admin.username(),
                    "email", admin.email(),
                    "role", admin.role(),
                    "createdAt", admin.createdAt(),
                    "lastLoginAt", admin.lastLoginAt()
                ));
                
                response.setStatus(HttpServletResponse.SC_OK);
                PrintWriter out = response.getWriter();
                out.print(Main.getObjectMapper().writeValueAsString(successResponse));
                out.flush();
            } else {
                // 本次失败已在 beginAttempt 里计过数（与账号是否存在无关，因此 429 不是枚举信号）；
                // 再记一次来源维度的失败，供递增退避封禁使用（同样是「只看请求、不看账号存在性」）
                if (sourceBanService != null) {
                    sourceBanService.recordAuthFailure(clientIp, "admin-login");
                }
                sendErrorResponse(response, 401, "admin.login.bad_credentials");
            }
        } catch (Exception e) {
            logger.error("管理员登录处理错误", e);
            sendErrorResponse(response, 500, "common.internal_error");
        }
    }

}
