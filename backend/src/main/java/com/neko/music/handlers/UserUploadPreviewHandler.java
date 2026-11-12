package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.util.ClientAborts;
import com.neko.music.util.HttpResourceCache;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.neko.music.i18n.ApiMessages;

public class UserUploadPreviewHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(UserUploadPreviewHandler.class);
    private static final String UPLOAD_DIR = "user_upload";

    /** 分片输出缓冲：与媒体接口保持一致，避免整文件读进内存。 */
    private static final int BUFFER_SIZE = 65536;
    
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 设置CORS响应头
        
        // 处理OPTIONS预检请求
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        
        // 验证管理员权限
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            logger.warn("未授权访问预览接口，缺少Authorization头");
            response.sendError(401, "auth.unauthorized");
            return;
        }
        
        String token = authHeader.substring(7);
        boolean isValid = Main.getAdminAuthService().validateAdminToken(token);
        if (!isValid) {
            logger.warn("未授权访问预览接口，无效的token");
            response.sendError(401, "auth.unauthorized");
            return;
        }
        
        // 获取文件路径参数
        String filePath = request.getParameter("path");
        if (filePath == null || filePath.isEmpty()) {
            logger.warn("预览请求缺少文件路径参数");
            response.sendError(400, ApiMessages.translate("upload.preview.path_required"));
            return;
        }
        
        // 安全检查：确保文件路径在审核目录内
        Path requestedPath;
        Path uploadDirPath;
        try {
            // 统一使用正斜杠处理路径
            String normalizedFilePath = filePath.replace('\\', '/');
            
            // 确保请求的路径是相对路径
            if (Paths.get(normalizedFilePath).isAbsolute()) {
                // 如果是绝对路径，尝试转换为相对路径
                Path currentDir = Paths.get("").toAbsolutePath();
                Path absolutePath = Paths.get(normalizedFilePath).toAbsolutePath();
                requestedPath = currentDir.relativize(absolutePath).normalize();
                logger.info("检测到绝对路径，已转换为相对路径: {}", requestedPath);
            } else {
                requestedPath = Paths.get(normalizedFilePath).normalize();
            }
            
            uploadDirPath = Paths.get(UPLOAD_DIR).normalize();
            
            logger.info("请求路径: {}", requestedPath);
            logger.info("上传目录: {}", uploadDirPath);
            logger.info("请求路径是否以上传目录开头: {}", requestedPath.startsWith(uploadDirPath));
            
        } catch (Exception e) {
            logger.error("文件路径解析失败: {}", filePath, e);
            response.sendError(400, ApiMessages.translate("upload.preview.path_invalid"));
            return;
        }
        
        if (!requestedPath.startsWith(uploadDirPath)) {
            logger.warn("访问被拒绝，文件路径不在审核目录内。请求路径: {}, 上传目录: {}", requestedPath, uploadDirPath);
            response.sendError(403, ApiMessages.translate("upload.preview.forbidden"));
            return;
        }
        
        // 检查文件是否存在
        if (!Files.exists(requestedPath) || !Files.isRegularFile(requestedPath)) {
            logger.warn("预览文件不存在: {}", filePath);
            response.sendError(404, ApiMessages.translate("upload.preview.file_missing"));
            return;
        }
        
        sendFile(requestedPath, request, response);

        logger.debug("管理员预览文件成功: {}", filePath);
    }

    /**
     * 发送预览文件内容：带 {@code Range} 时回 {@code 206} 单段内容，否则整包 {@code 200}。
     *
     * <p>必须支持 Range：客户端（浏览器 / 客户端 App）与 CDN 都会按需分片取音频，CDN 的分片
     * 回源只有在源站回 {@code 206} 时才会拼出完整文件；源站忽略 {@code Range} 直接回整包时，
     * 大文件会被 CDN 截断（客户端表现为 {@code ERR_HTTP2_PROTOCOL_ERROR}）。</p>
     *
     * <p>预览文件按「磁盘文件」缓存六个月（{@link HttpResourceCache#CACHE_CONTROL_PRIVATE_FILE}），
     * 重复试听由浏览器直接命中，避免几十 MB 的原始音频反复回源。这里只给浏览器私有缓存：预览内容
     * 是待审核的原始文件，且接口要求管理员 token，若声明 {@code public} 会被 CDN 缓存成免鉴权可取的
     * 公共地址。同一路径内容被替换时 ETag 变化，条件请求（{@code If-None-Match}）会重新取回新对象。</p>
     */
    static void sendFile(Path file, HttpServletRequest request, HttpServletResponse response) throws IOException {
        long size = Files.size(file);
        applyFileResponseHeaders(file, response);
        HttpResourceCache.setAcceptRangesBytes(response);
        if (request.getHeader("Range") == null
                && HttpResourceCache.sendNotModifiedIfFresh(
                        request, response, HttpResourceCache.strongEtagForFile(file),
                        HttpResourceCache.CACHE_CONTROL_PRIVATE_FILE)) {
            return;
        }
        HttpResourceCache.applyFileCachingHeaders(file, response, HttpResourceCache.CACHE_CONTROL_PRIVATE_FILE);

        RangeSpec range = parseRange(request.getHeader("Range"), size);
        if (range == RangeSpec.UNSATISFIABLE) {
            response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
            response.setHeader("Content-Range", "bytes */" + size);
            return;
        }
        long start = range == null ? 0 : range.start();
        long end = range == null || size == 0 ? size - 1 : range.end();
        long length = size == 0 ? 0 : end - start + 1;
        if (range == null) {
            response.setStatus(HttpServletResponse.SC_OK);
        } else {
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader("Content-Range", "bytes " + start + "-" + end + "/" + size);
        }
        response.setContentLengthLong(length);

        try (InputStream in = Files.newInputStream(file); OutputStream out = response.getOutputStream()) {
            if (start > 0) {
                in.skipNBytes(start);
            }
            byte[] buffer = new byte[BUFFER_SIZE];
            long remaining = length;
            while (remaining > 0) {
                int count = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                if (count < 0) {
                    break;
                }
                out.write(buffer, 0, count);
                remaining -= count;
            }
            out.flush();
        } catch (IOException e) {
            if (ClientAborts.isClientAbort(e)) {
                logger.debug("管理员在预览文件发送完成前断开连接: {}", file);
                return;
            }
            throw e;
        }
    }

    /** 单段字节区间；{@link #UNSATISFIABLE} 表示无法满足（回 {@code 416}）。 */
    record RangeSpec(long start, long end) {
        static final RangeSpec UNSATISFIABLE = new RangeSpec(-1, -1);
    }

    /**
     * 解析 {@code Range} 请求头，只服务单段（多段、语法非法一律按整包处理，避免给客户端 500）。
     * 返回 {@code null} = 按整包发送，{@link RangeSpec#UNSATISFIABLE} = 回 {@code 416}。
     */
    static RangeSpec parseRange(String header, long size) {
        if (header == null || !header.startsWith("bytes=")) {
            return null;
        }
        String spec = header.substring(6).trim();
        int comma = spec.indexOf(',');
        if (comma >= 0) {
            spec = spec.substring(0, comma).trim();
        }
        int dash = spec.indexOf('-');
        if (dash < 0) {
            return null;
        }
        String startText = spec.substring(0, dash).trim();
        String endText = spec.substring(dash + 1).trim();
        try {
            if (startText.isEmpty()) {
                if (endText.isEmpty() || size == 0) {
                    return endText.isEmpty() ? null : RangeSpec.UNSATISFIABLE;
                }
                long suffix = Long.parseLong(endText);
                if (suffix <= 0) {
                    return RangeSpec.UNSATISFIABLE;
                }
                return new RangeSpec(Math.max(0, size - suffix), size - 1);
            }
            long start = Long.parseLong(startText);
            long end = endText.isEmpty() ? size - 1 : Long.parseLong(endText);
            if (start < 0 || start >= size || end < start) {
                return RangeSpec.UNSATISFIABLE;
            }
            return new RangeSpec(start, Math.min(end, size - 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 写入预览文件的基础响应头（{@code Content-Type} / {@code Content-Disposition}）。
     *
     * <p>{@code Content-Length}、{@code Accept-Ranges}、缓存与 {@code 206} 相关头部都由
     * {@link #sendFile} 按整包 / 分片分别设置。</p>
     */
    static void applyFileResponseHeaders(Path file, HttpServletResponse response) throws IOException {
        String fileName = file.getFileName().toString();
        response.setContentType(getContentType(fileName));
        response.setHeader("Content-Disposition", "inline; filename=\"" + fileName + "\"");
    }

    @Override
    protected void doOptions(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        // 设置CORS响应头
        response.setStatus(HttpServletResponse.SC_OK);
    }
    
    static String getContentType(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return "application/octet-stream";
        }
        
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return "application/octet-stream";
        }
        
        String extension = fileName.substring(lastDotIndex + 1).toLowerCase();
        return switch (extension) {
            case "mp3" -> "audio/mpeg";
            case "flac" -> "audio/flac";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "m4a" -> "audio/mp4";
            case "aac" -> "audio/aac";
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            // 审核页通过 response.text() 读取，不需要浏览器按 text/plain 渲染。
            // 使用不可压缩 MIME，避免 CDN 对 gzip 表示写入未压缩正文的 Content-Length。
            case "lrc", "txt" -> "application/octet-stream";
            default -> "application/octet-stream";
        };
    }
}
