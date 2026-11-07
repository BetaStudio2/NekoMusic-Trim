package com.neko.music.handlers;

import com.fasterxml.jackson.databind.JsonNode;
import com.neko.music.Main;
import com.neko.music.service.AuthAbuseLimiter;
import com.neko.music.service.SliderCaptchaService;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * POST /api/captcha/slider/verify — 提交位移，成功返回短时 captchaPassToken（供发送邮箱验证码接口消费）。
 */
@WebServlet("/api/captcha/slider/verify")
public class SliderCaptchaVerifyHandler extends ApiServlet {

    private static final Logger logger = LoggerFactory.getLogger(SliderCaptchaVerifyHandler.class);

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (enforceAuthAbuseLimit(request, response, AuthAbuseLimiter.Scope.CAPTCHA)) {
            return;
        }

        response.setContentType("application/json;charset=UTF-8");

        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }

        try {
            JsonNode root = Main.getObjectMapper().readTree(body.toString());
            if (root == null) {
                sendJson(response, false, "captcha.body_required", null);
                return;
            }
            String token = root.has("captchaToken") ? root.get("captchaToken").asText().trim() : "";
            if (token.isEmpty()) {
                sendJson(response, false, "captcha.token_required", null);
                return;
            }
            if (!root.has("captchaOffsetX") || !root.get("captchaOffsetX").isNumber()) {
                sendJson(response, false, "captcha.offset_x_required", null);
                return;
            }
            int offsetX = root.get("captchaOffsetX").asInt();
            List<SliderCaptchaService.DragSample> trace = parseTrace(root.get("dragTrace"));

            Optional<String> pass = Main.getSliderCaptchaService().verifySlideAndIssuePass(
                    token,
                    offsetX,
                    trace,
                    request.getHeader("User-Agent"));
            if (pass.isEmpty()) {
                sendJson(response, false, "captcha.wrong_position", null);
                return;
            }
            sendJson(response, true, "captcha.verified", Map.of("captchaPassToken", pass.get()));
        } catch (Exception e) {
            logger.error("滑块校验接口异常", e);
            sendJson(response, false, "auth.register.internal_error", null);
        }
    }

    private static List<SliderCaptchaService.DragSample> parseTrace(JsonNode traceNode) {
        if (traceNode == null || !traceNode.isArray()) {
            return List.of();
        }
        List<SliderCaptchaService.DragSample> trace = new ArrayList<>();
        for (JsonNode sample : traceNode) {
            if (trace.size() >= 160) {
                return List.of();
            }
            if (sample == null || !sample.isObject()
                    || !sample.has("t") || !sample.has("x")
                    || !sample.get("t").canConvertToLong()
                    || !sample.get("x").canConvertToInt()) {
                return List.of();
            }
            trace.add(new SliderCaptchaService.DragSample(
                    sample.get("t").asLong(), sample.get("x").asInt()));
        }
        return trace;
    }

}
