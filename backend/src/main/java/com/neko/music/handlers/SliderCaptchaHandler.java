package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.SliderCaptchaService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/** GET /api/captcha/slider — 签发滑块挑战（获取邮箱验证码前在弹窗中拉取并展示） */
@WebServlet("/api/captcha/slider")
public class SliderCaptchaHandler extends ApiServlet {

    private static final Logger logger = LoggerFactory.getLogger(SliderCaptchaHandler.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (enforceAuthAbuseLimit(request, response, AuthAbuseLimiter.Scope.CAPTCHA)) {
            return;
        }

        response.setContentType("application/json;charset=UTF-8");

        try {
            SliderCaptchaService svc = Main.getSliderCaptchaService();
            Map<String, Object> data = svc.createChallengePayload(request.getHeader("User-Agent"));
            sendJson(response, true, "ok", data);
        } catch (Exception e) {
            logger.error("生成滑块验证码失败", e);
            sendJson(response, false, "captcha.generate_failed", null);
        }
    }

}
