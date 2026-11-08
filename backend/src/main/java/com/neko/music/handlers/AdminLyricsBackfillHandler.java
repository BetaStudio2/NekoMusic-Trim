package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.service.LyricsBackfillService;
import com.neko.music.util.AdminPermissionUtil;
import com.neko.music.util.PermissionHelper;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** 后台历史歌词逐字迁移任务：状态、启动、取消。 */
public class AdminLyricsBackfillHandler extends HttpServlet {

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!PermissionHelper.checkPermission(
                request, response, AdminPermissionUtil.Permission.MUSIC_VIEW)) {
            return;
        }
        String path = path(request);
        if (!"/status".equals(path)) {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "未知接口");
            return;
        }
        writeStatus(response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!PermissionHelper.checkPermission(
                request, response, AdminPermissionUtil.Permission.MUSIC_EDIT)) {
            return;
        }
        LyricsBackfillService service = Main.getLyricsBackfillService();
        if (service == null) {
            writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, "迁移服务未就绪");
            return;
        }
        String path = path(request);
        switch (path) {
            case "/start" -> {
                if (!service.start()) {
                    writeError(response, HttpServletResponse.SC_CONFLICT, "迁移任务已在运行");
                    return;
                }
                writeStatus(response);
            }
            case "/cancel" -> {
                if (!service.cancel()) {
                    writeError(response, HttpServletResponse.SC_CONFLICT, "当前没有运行中的迁移任务");
                    return;
                }
                writeStatus(response);
            }
            default -> writeError(response, HttpServletResponse.SC_NOT_FOUND, "未知接口");
        }
    }

    private static void writeStatus(HttpServletResponse response) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "ok");
        body.put("data", Main.getLyricsBackfillService().status());
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType("application/json;charset=utf-8");
        Main.getObjectMapper().writeValue(response.getWriter(), body);
    }

    private static void writeError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=utf-8");
        Main.getObjectMapper().writeValue(response.getWriter(),
                Map.of("success", false, "message", message));
    }

    private static String path(HttpServletRequest request) {
        String path = request.getPathInfo();
        return path == null ? "" : path;
    }
}
