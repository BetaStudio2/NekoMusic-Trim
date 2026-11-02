package com.neko.music.handlers;

import com.neko.music.i18n.ApiMessages;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.neko.music.Main;
import com.neko.music.database.CommentDatabaseManager;
import com.neko.music.database.CommentDatabaseManager.CommentRow;
import com.neko.music.model.User;
import com.neko.music.service.IpRegionService;
import com.neko.music.util.PermissionHelper;
import com.neko.music.util.PublicMusicLookup;
import com.neko.music.util.RequestAuthUtil;
import com.neko.music.util.SensitiveWordUtil;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 歌曲评论：列表 / 发表 / 回复 / 删除共用一个端点 {@code /api/comments}。
 *
 * <ul>
 *   <li>{@code GET  /api/comments?musicId=&page=&pageSize=} 拉取楼层（含回复），无需登录</li>
 *   <li>{@code POST /api/comments} body {@code {musicId, content, parentId?}} 发表评论或回复，需登录</li>
 *   <li>{@code DELETE /api/comments?id=} 删除自己的评论（管理员令牌可删任意一条）；
 *       物理删除，删楼层会连带删除该楼层下的全部回复</li>
 * </ul>
 *
 * <p>评论只做两层：顶层楼层 + 楼层内回复，回复再回复仍归到同一楼层并用 {@code replyToUser} 标记 @ 对象，
 * 避免无限嵌套带来的分页与展示复杂度。每条评论都会快照写入发表时的 IP 归属地。
 *
 * <p>回复成功后会同时给对方写一条站内消息（{@code /api/user/notifications}），
 * 回复自己不再提醒；写消息失败只记日志，评论本身仍然算成功。
 */
public class MusicCommentHandler extends ApiServlet {

    private static final Logger logger = LoggerFactory.getLogger(MusicCommentHandler.class);

    private static final String COOLDOWN_KEY_PREFIX = "comment:cooldown:";
    private static final int MAX_CONTENT_LENGTH = 500;
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;
    /** 同一用户两次发评论的最小间隔（秒） */
    private static final int POST_INTERVAL_SECONDS = 5;
    /** 站内消息类型：有人回复了你的评论 */
    private static final String NOTIFY_TYPE_COMMENT_REPLY = "comment_reply";
    /** 站内消息标题里展示的歌名长度上限（超出截断，避免整句被文案长度截掉） */
    private static final int NOTIFY_TITLE_MUSIC_MAX_LENGTH = 40;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Integer musicId = parseInteger(req.getParameter("musicId"));
        if (musicId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "music.id_missing");
            return;
        }

        if (PublicMusicLookup.findById(musicId).isEmpty()) {
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "music.not_found");
            return;
        }

        int page = Math.max(1, parseIntegerOrDefault(req.getParameter("page"), 1));
        int pageSize = clamp(parseIntegerOrDefault(req.getParameter("pageSize"), DEFAULT_PAGE_SIZE),
                1, MAX_PAGE_SIZE);

        Integer viewerId = RequestAuthUtil.authenticate(req);
        boolean admin = viewerId == null && PermissionHelper.isAdminAuthorized(req);

        CommentDatabaseManager db = Main.getCommentDatabaseManager();
        int total = db.countTopLevel(musicId);
        List<CommentRow> roots = db.listTopLevel(musicId, pageSize, (page - 1) * pageSize);

        List<Integer> rootIds = new ArrayList<>();
        for (CommentRow root : roots) {
            rootIds.add(root.id);
        }
        Map<Integer, List<CommentRow>> repliesByRoot = new LinkedHashMap<>();
        for (CommentRow reply : db.listReplies(musicId, rootIds)) {
            repliesByRoot.computeIfAbsent(reply.parentId, key -> new ArrayList<>()).add(reply);
        }

        List<Integer> userIds = new ArrayList<>();
        for (CommentRow root : roots) {
            userIds.add(root.userId);
            if (root.replyToUserId != null) {
                userIds.add(root.replyToUserId);
            }
        }
        for (List<CommentRow> replies : repliesByRoot.values()) {
            for (CommentRow reply : replies) {
                userIds.add(reply.userId);
                if (reply.replyToUserId != null) {
                    userIds.add(reply.replyToUserId);
                }
            }
        }
        Map<Integer, String> nicknames = Main.getNicknameResolver()
                .resolveByLocalUserIds(userIds, "听众");

        JsonArray comments = new JsonArray();
        for (CommentRow root : roots) {
            JsonObject floor = toJson(root, viewerId, admin, nicknames);
            List<CommentRow> replies = repliesByRoot.getOrDefault(root.id, List.of());
            floor.addProperty("replyCount", replies.size());
            JsonArray replyArr = new JsonArray();
            for (CommentRow reply : replies) {
                replyArr.add(toJson(reply, viewerId, admin, nicknames));
            }
            floor.add("replies", replyArr);
            comments.add(floor);
        }

        JsonObject data = new JsonObject();
        data.addProperty("musicId", musicId);
        data.addProperty("page", page);
        data.addProperty("pageSize", pageSize);
        data.addProperty("total", total);
        data.addProperty("totalComments", db.countAll(musicId));
        data.addProperty("totalPages", total == 0 ? 0 : (total + pageSize - 1) / pageSize);
        data.addProperty("hasMore", (long) page * pageSize < total);
        data.add("comments", comments);

        JsonObject body = new JsonObject();
        body.addProperty("success", true);
        body.addProperty("message", ApiMessages.translate("comment.list_ok"));
        body.add("data", data);
        sendSuccessResponse(resp, body);
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Integer userId = RequestAuthUtil.authenticate(req);
        if (userId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "comment.login_required");
            return;
        }

        JsonObject request;
        try {
            request = Main.getGson().fromJson(readBody(req), JsonObject.class);
        } catch (Exception e) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "auth.bad_request");
            return;
        }
        if (request == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "sensitive.body_required");
            return;
        }

        Integer musicId = request.has("musicId") && !request.get("musicId").isJsonNull()
                ? request.get("musicId").getAsInt() : null;
        if (musicId == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "music.id_missing");
            return;
        }

        String content = request.has("content") && !request.get("content").isJsonNull()
                ? request.get("content").getAsString().trim() : "";
        if (content.isEmpty()) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "comment.content_required");
            return;
        }
        if (content.length() > MAX_CONTENT_LENGTH) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST,
                    ApiMessages.translate("comment.too_long", MAX_CONTENT_LENGTH));
            return;
        }
        if (SensitiveWordUtil.contains(content)) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "comment.contains_banned");
            return;
        }

        PublicMusicLookup.PublicMusic music = PublicMusicLookup.findById(musicId).orElse(null);
        if (music == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "music.not_found");
            return;
        }

        Integer parentId = request.has("parentId") && !request.get("parentId").isJsonNull()
                ? request.get("parentId").getAsInt() : null;

        CommentDatabaseManager db = Main.getCommentDatabaseManager();
        Integer rootId = null;
        Integer replyToUserId = null;
        if (parentId != null) {
            CommentRow parent = db.findById(parentId);
            if (parent == null || parent.musicId != musicId) {
                sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "comment.reply_target_not_found");
                return;
            }
            rootId = parent.parentId != null ? parent.parentId : parent.id;
            replyToUserId = parent.userId;
        }

        if (Main.getRedisService() != null
                && !Main.getRedisService().setIfAbsentWithExpiry(COOLDOWN_KEY_PREFIX + userId, "1", POST_INTERVAL_SECONDS)) {
            sendCooldownResponse(resp, POST_INTERVAL_SECONDS);
            return;
        }

        String clientIp = IpRegionService.clientIp(req);
        String ipRegion = Main.getIpRegionService().resolve(clientIp);
        int newId = db.insert(musicId, userId, rootId, replyToUserId, content, ipRegion, clientIp);
        if (newId <= 0) {
            if (Main.getRedisService() != null) {
                Main.getRedisService().del(COOLDOWN_KEY_PREFIX + userId);
            }
            sendErrorResponse(resp, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "comment.post_failed");
            return;
        }

        notifyCommentReply(userId, replyToUserId, musicId, music.title, content);

        JsonObject data = new JsonObject();
        data.addProperty("id", newId);
        data.addProperty("musicId", musicId);
        data.addProperty("parentId", rootId);
        data.addProperty("content", content);
        data.addProperty("ipRegion", ipRegion);
        data.addProperty("createdAt", com.neko.music.util.DbTimeUtil.nowShanghaiWallClock());
        data.addProperty("isReply", parentId != null);

        JsonObject body = new JsonObject();
        body.addProperty("success", true);
        body.addProperty("message", parentId == null ? ApiMessages.translate("comment.post_ok") : ApiMessages.translate("comment.reply_ok"));
        body.add("data", data);
        sendSuccessResponse(resp, body);
    }

    /**
     * 回复落地后给被回复的人写一条站内消息。
     *
     * <p>离线用户不需要任何长连接：下次打开收件箱带上游标补拉就能看到这条记录。</p>
     */
    private void notifyCommentReply(int actorUserId, Integer receiverUserId, int musicId, String musicTitle,
            String content) {
        if (receiverUserId == null || receiverUserId == actorUserId) {
            return;
        }
        try {
            String actorNickname = Main.getUserAuthService().findUserById(actorUserId)
                    .map(User::nickname)
                    .filter(name -> name != null && !name.isBlank())
                    .orElse("有人");
            String songName = displayMusicTitle(musicTitle);
            int created = Main.getUserNotificationService().notify(
                    receiverUserId,
                    NOTIFY_TYPE_COMMENT_REPLY,
                    songName == null
                            ? actorNickname + " 回复了你的评论"
                            : actorNickname + " 回复了你在《" + songName + "》下的评论",
                    content,
                    "/detail/" + musicId,
                    actorUserId);
            if (created <= 0) {
                logger.warn("评论回复站内消息写入失败: musicId={}, receiver={}", musicId, receiverUserId);
            }
        } catch (Exception e) {
            // 通知只是评论的附带效果，失败不能影响已经成功的评论
            logger.error("评论回复站内消息异常: musicId={}, actor={}", musicId, actorUserId, e);
        }
    }

    /** 标题里的歌名：过长只截断展示，保证「《…》下的评论」这句提示不会被尾部截掉。 */
    private static String displayMusicTitle(String title) {
        if (title == null || title.isBlank()) {
            return null;
        }
        String trimmed = title.trim();
        return trimmed.length() <= NOTIFY_TITLE_MUSIC_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, NOTIFY_TITLE_MUSIC_MAX_LENGTH) + "…";
    }

    @Override
    protected void doDelete(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        Integer userId = RequestAuthUtil.authenticate(req);
        boolean admin = false;
        if (userId == null) {
            admin = PermissionHelper.isAdminAuthorized(req);
            if (!admin) {
                sendErrorResponse(resp, HttpServletResponse.SC_UNAUTHORIZED, "auth.login_required");
                return;
            }
        }

        Integer id = parseInteger(req.getParameter("id"));
        if (id == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "comment.id_required");
            return;
        }

        CommentDatabaseManager db = Main.getCommentDatabaseManager();
        CommentRow row = db.findById(id);
        if (row == null) {
            sendErrorResponse(resp, HttpServletResponse.SC_NOT_FOUND, "comment.not_found");
            return;
        }
        if (!admin && row.userId != userId) {
            sendErrorResponse(resp, HttpServletResponse.SC_FORBIDDEN, "comment.delete_own_only");
            return;
        }
        int affected = db.delete(id, row.parentId, userId == null ? 0 : userId, admin);
        if (affected <= 0) {
            sendErrorResponse(resp, HttpServletResponse.SC_BAD_REQUEST, "comment.delete_failed");
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("id", id);
        data.addProperty("repliesDeleted", row.parentId == null ? Math.max(0, affected - 1) : 0);
        JsonObject body = new JsonObject();
        body.addProperty("success", true);
        body.addProperty("message", ApiMessages.translate("admin.lyrics.delete_ok"));
        body.add("data", data);
        sendSuccessResponse(resp, body);
    }

    /** 单条评论（含作者、被回复者、可否删除）→ JSON。 */
    private static JsonObject toJson(CommentRow row, Integer viewerId, boolean admin,
                                     Map<Integer, String> nicknames) {
        JsonObject json = new JsonObject();
        json.addProperty("id", row.id);
        json.addProperty("musicId", row.musicId);
        json.addProperty("content", row.content);
        json.addProperty("createdAt", row.createdAt);
        json.addProperty("ipRegion", IpRegionService.displayStored(row.ipRegion));
        json.addProperty("canDelete", admin || (viewerId != null && viewerId == row.userId));

        JsonObject user = new JsonObject();
        user.addProperty("id", row.userId);
        user.addProperty("nickname", nicknames.getOrDefault(row.userId, "听众"));
        json.add("user", user);

        if (row.replyToUserId != null) {
            JsonObject replyTo = new JsonObject();
            replyTo.addProperty("id", row.replyToUserId);
            replyTo.addProperty("nickname", nicknames.getOrDefault(row.replyToUserId, "听众"));
            json.add("replyToUser", replyTo);
        }
        return json;
    }

    private static Integer parseInteger(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int parseIntegerOrDefault(String raw, int fallback) {
        Integer parsed = parseInteger(raw);
        return parsed == null ? fallback : parsed;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
