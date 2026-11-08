package com.neko.music.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.music.Main;
import com.neko.music.i18n.ApiMessages;
import com.neko.music.service.PlaylistRecommendationService;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.RequestAuthUtil;
import com.neko.music.util.UserLookup;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

/**
 * 推荐歌单接口：登录用户使用画像模型，游客使用社区热门与新鲜度策略。
 *
 * <p>响应始终为私有且不缓存；refresh=true 时保留高置信结果并替换探索位，
 * 让用户在首页刷新后能看到新的歌单组合。</p>
 */
public class RecommendedPlaylistsHandler extends ApiServlet {
    private static final Logger logger = LoggerFactory.getLogger(RecommendedPlaylistsHandler.class);

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        // 新接口显式声明缓存策略；不依赖兜底 Filter，避免未来路由调整后意外缓存个性化结果。
        resp.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_NO_STORE);

        String token = RequestAuthUtil.extractToken(req);
        Integer userId = null;
        if (token != null) {
            userId = Main.getUserAuthService() == null ? null
                    : Main.getUserAuthService().validateToken(token).orElse(null);
            if (userId == null) {
                sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.invalid_bearer");
                return;
            }
        }

        PlaylistRecommendationService service = Main.getPlaylistRecommendationService();
        if (service == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "recommend.service_unavailable");
            return;
        }

        boolean refresh = isTruthy(req.getParameter("refresh"));
        try {
            List<PlaylistRecommendationService.RecommendedPlaylist> data =
                    service.recommend(userId, refresh);
            JsonObject body = new JsonObject();
            body.addProperty("success", true);
            body.addProperty("message", ApiMessages.translate("recommend.playlist.ok"));
            body.addProperty("generatedAt", Instant.now().toString());
            body.addProperty("personalized", data.stream()
                    .anyMatch(one -> "personalized".equals(one.source())));
            body.addProperty("count", data.size());

            JsonArray items = new JsonArray();
            for (PlaylistRecommendationService.RecommendedPlaylist one : data) {
                JsonObject item = new JsonObject();
                item.addProperty("rank", one.rank());
                item.addProperty("id", one.id());
                item.addProperty("userId", one.userId());
                item.addProperty("name", one.name());
                item.addProperty("description", one.description());
                item.addProperty("musicCount", one.musicCount());
                item.addProperty("source", one.source());
                item.addProperty("reason", ApiMessages.translate(
                        "recommend.playlist.reason." + one.reason()));
                item.addProperty("exploration", one.exploration());

                JsonObject creator = new JsonObject();
                creator.addProperty("id", one.userId());
                creator.addProperty("nickname", UserLookup.getNickname(one.userId()));
                item.add("creator", creator);

                JsonArray covers = new JsonArray();
                one.coverUrls().forEach(covers::add);
                item.add("coverUrls", covers);
                item.addProperty("firstMusicCover", covers.isEmpty() ? "/api/music/cover/0"
                        : covers.get(0).getAsString());
                items.add(item);
            }
            body.add("data", items);
            sendSuccessResponse(resp, body);
        } catch (Exception e) {
            logger.error("处理推荐歌单请求失败 userId={}", userId, e);
            sendErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "common.internal_error");
        }
    }

    private static boolean isTruthy(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value);
    }
}
