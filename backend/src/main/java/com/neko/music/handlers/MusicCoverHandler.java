package com.neko.music.handlers;

import com.neko.music.util.ClientAborts;
import com.neko.music.util.CoverThumbnails;
import com.neko.music.util.CoverArtCache;

import com.neko.music.util.MusicLookup;

import com.neko.music.util.HttpResourceCache;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

public class MusicCoverHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(MusicCoverHandler.class);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        String pathInfo = request.getPathInfo();
        
        // ?size= 命中白名单时返回方形缩略图（按需生成并缓存），否则原图
        int size = parseSize(request);

        if (pathInfo == null || pathInfo.equals("/")) {
            sendDefaultIcon(request, size, response);
            return;
        }
        
        // 解析音乐ID (路径格式: /{id})
        String idStr = pathInfo.replace("/", "");
        int musicId;
        
        try {
            musicId = Integer.parseInt(idStr);
        } catch (NumberFormatException e) {
            response.setStatus(HttpStatus.BAD_REQUEST_400);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().println("Invalid music ID");
            return;
        }

        if (!MusicLookup.musicRowExists(musicId)) {
            sendDefaultIcon(request, size, response);
            return;
        }

        Optional<CoverArtCache.Snapshot> embedded = CoverArtCache.find(musicId);
        if (embedded.isPresent()) {
            sendEmbeddedCover(request, embedded.get(), size, response);
            return;
        }

        sendDefaultIcon(request, size, response);
    }

    /** 解析 ?size=，不在白名单内按 0（原图）处理。 */
    private static int parseSize(HttpServletRequest request) {
        String raw = request.getParameter("size");
        if (raw == null || raw.isBlank()) {
            return 0;
        }
        try {
            int size = Integer.parseInt(raw.trim());
            return CoverThumbnails.isAllowedSize(size) ? size : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 发送从音频中解析出的内嵌封面。
     *
     * <p>ETag 取封面内容哈希而不是音频 mtime：只改标题/歌词时音频 mtime 会变，但封面字节没变，
     * 客户端与 CDN 才能继续用 304 命中。缩略图按同一哈希做缓存键。</p>
     */
    private void sendEmbeddedCover(HttpServletRequest request, CoverArtCache.Snapshot cover,
                                   int size, HttpServletResponse response) throws IOException {
        String etag = "\"c-" + cover.etag() + (size > 0 ? "-" + size : "") + "\"";
        if (HttpResourceCache.sendNotModifiedIfFresh(request, response, etag)) {
            return;
        }
        if (size > 0) {
            Optional<Path> thumb = CoverThumbnails.fromBytes(cover.data(), cover.etag(), size);
            if (thumb.isPresent()) {
                sendImageFile(request, thumb.get(), response);
                return;
            }
        }
        response.setStatus(HttpStatus.OK_200);
        response.setContentType(cover.mime());
        response.setHeader("ETag", etag);
        response.setHeader("Cache-Control", HttpResourceCache.CACHE_CONTROL_FILE);
        response.setContentLength(cover.data().length);
        try (OutputStream outputStream = response.getOutputStream()) {
            outputStream.write(cover.data());
        } catch (IOException e) {
            if (ClientAborts.isClientAbort(e)) {
                logger.debug("客户端在内嵌封面发送完成前断开连接");
                return;
            }
            throw e;
        }
    }

    private void sendImageFile(HttpServletRequest request, Path imagePath, HttpServletResponse response) throws IOException {
        // ETag、Last-Modified、Content-Length 都来自这一次 stat；304 分支到此为止，不打开文件
        BasicFileAttributes attrs = Files.readAttributes(imagePath, BasicFileAttributes.class);
        if (HttpResourceCache.sendNotModifiedIfFresh(request, response, HttpResourceCache.etagFor(attrs))) {
            return;
        }

        String mimeType = getMimeType(imagePath.toString());
        response.setContentType(mimeType);
        response.setStatus(HttpStatus.OK_200);
        HttpResourceCache.applyFileCachingHeaders(attrs, response, HttpResourceCache.CACHE_CONTROL_FILE);
        response.setContentLengthLong(attrs.size());
        
        try (InputStream inputStream = Files.newInputStream(imagePath);
             OutputStream outputStream = response.getOutputStream()) {
            copyStream(inputStream, outputStream);
        } catch (IOException e) {
            if (ClientAborts.isClientAbort(e)) {
                logger.debug("客户端在封面发送完成前断开连接: {}", imagePath);
                return;
            }
            throw e;
        }
    }
    
    private void sendDefaultIcon(HttpServletRequest request, int size, HttpServletResponse response) throws IOException {
        if (size > 0) {
            Optional<Path> thumb = CoverThumbnails.fromResource("DefaultIcon.png", size);
            if (thumb.isPresent()) {
                sendImageFile(request, thumb.get(), response);
                return;
            }
        }
        if (HttpResourceCache.sendNotModifiedDefaultIcon(request, response)) {
            return;
        }

        InputStream defaultIconStream = getClass().getClassLoader().getResourceAsStream("DefaultIcon.png");
        if (defaultIconStream == null) {
            response.setStatus(HttpStatus.NOT_FOUND_404);
            response.setContentType("text/plain;charset=utf-8");
            response.getWriter().println("Default icon not found");
            return;
        }

        response.setContentType("image/png");
        response.setStatus(HttpStatus.OK_200);
        HttpResourceCache.applyDefaultIconCachingHeaders(response);

        try (InputStream inputStream = defaultIconStream;
             OutputStream outputStream = response.getOutputStream()) {
            copyStream(inputStream, outputStream);
        } catch (IOException e) {
            if (ClientAborts.isClientAbort(e)) {
                logger.debug("客户端在默认封面发送完成前断开连接");
                return;
            }
            logger.error("发送默认图标时出错", e);
        }
    }

    private void copyStream(InputStream inputStream, OutputStream outputStream) throws IOException {
        // 64KB 缓冲：封面常见几百 KB，用 8KB 缓冲要多几十次 read/write
        byte[] buffer = new byte[65536];
        int bytesRead;

        while ((bytesRead = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, bytesRead);
        }

        outputStream.flush();
    }

    
    private String getMimeType(String filePath) {
        String extension = getFileExtension(filePath).toLowerCase();
        
        switch (extension) {
            case "jpg":
            case "jpeg":
                return "image/jpeg";
            case "png":
                return "image/png";
            case "gif":
                return "image/gif";
            case "bmp":
                return "image/bmp";
            case "webp":
                return "image/webp";
            default:
                return "image/jpeg";
        }
    }
    
    private String getFileExtension(String fileName) {
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            return fileName.substring(lastDotIndex + 1);
        }
        return "";
    }
}
