package com.neko.music.handlers;

import com.neko.music.Main;
import com.neko.music.util.HtmlEscaper;
import com.neko.music.util.HttpResourceCache;
import com.neko.music.util.SiteUrlResolver;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 站点 sitemap 索引与分类子图。
 *
 * <p>sitemap 协议规定单个 {@code <urlset>} 最多包含 50,000 个 URL。旧实现把固定页、歌曲页与
 * 歌单页塞进同一个文件（最多 70,000 条），超出部分可能被搜索引擎忽略。这里拆成固定页、
 * 歌曲页与歌单页三个子图，由 {@code /sitemap.xml} 统一发现。</p>
 */
public class SitemapHandler extends HttpServlet {
    private static final Logger logger = LoggerFactory.getLogger(SitemapHandler.class);
    static final String INDEX_PATH = "/sitemap.xml";
    static final String STATIC_PATH = "/sitemap-static.xml";
    static final String MUSIC_PATH = "/sitemap-music.xml";
    static final String PLAYLISTS_PATH = "/sitemap-playlists.xml";
    private static final int MAX_URLS_PER_SITEMAP = 50_000;
    private static final DateTimeFormatter LASTMOD = DateTimeFormatter.ofPattern("yyyy-MM-dd")
            .withZone(ZoneOffset.UTC);

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String siteBase = SiteUrlResolver.resolvePublicSiteBase(request);
        String path = resolveRequestPath(request);
        String xml = switch (path) {
            case STATIC_PATH -> buildStaticXml(siteBase);
            case MUSIC_PATH -> buildMusicXml(siteBase, loadMusicEntries());
            case PLAYLISTS_PATH -> buildPlaylistXml(siteBase, loadPlaylistEntries());
            default -> buildIndexXml(siteBase);
        };

        response.setStatus(HttpStatus.OK_200);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/xml;charset=utf-8");
        response.setHeader("Cache-Control",
                "public, max-age=" + HttpResourceCache.MAX_AGE_ONE_DAY);
        response.getWriter().write(xml);
    }

    private List<MusicEntry> loadMusicEntries() {
        List<MusicEntry> list = new ArrayList<>();
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT id, title, artist, updated_at FROM music ORDER BY updated_at DESC LIMIT ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, MAX_URLS_PER_SITEMAP);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    MusicEntry e = new MusicEntry();
                    e.id = rs.getInt("id");
                    e.title = rs.getString("title");
                    e.artist = rs.getString("artist");
                    Timestamp ts = rs.getTimestamp("updated_at");
                    e.lastmod = ts != null ? LASTMOD.format(ts.toInstant()) : "";
                    list.add(e);
                }
            }
        } catch (Exception e) {
            logger.error("生成 sitemap 时查询音乐失败", e);
        }
        return list;
    }

    private List<MusicEntry> loadPlaylistEntries() {
        List<MusicEntry> list = new ArrayList<>();
        try (Connection conn = Main.getDatabaseManager().getConnection()) {
            String sql = "SELECT id, name, updated_at FROM playlists ORDER BY updated_at DESC LIMIT ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, MAX_URLS_PER_SITEMAP);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    MusicEntry e = new MusicEntry();
                    e.id = rs.getInt("id");
                    e.title = rs.getString("name");
                    Timestamp ts = rs.getTimestamp("updated_at");
                    e.lastmod = ts != null ? LASTMOD.format(ts.toInstant()) : "";
                    list.add(e);
                }
            }
        } catch (Exception e) {
            logger.error("生成 sitemap 时查询歌单失败", e);
        }
        return list;
    }

    static String buildIndexXml(String siteBase) {
        String base = siteBase.endsWith("/") ? siteBase.substring(0, siteBase.length() - 1) : siteBase;
        StringBuilder sb = new StringBuilder(768);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<sitemapindex xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        appendSitemap(sb, base + STATIC_PATH);
        appendSitemap(sb, base + MUSIC_PATH);
        appendSitemap(sb, base + PLAYLISTS_PATH);
        sb.append("</sitemapindex>\n");
        return sb.toString();
    }

    static String buildStaticXml(String siteBase) {
        String base = siteBase.endsWith("/") ? siteBase.substring(0, siteBase.length() - 1) : siteBase;
        StringBuilder sb = new StringBuilder(2048);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        // 固定页没有可靠的更新时间，宁可不写 lastmod，也不要每次请求都伪造“今天更新”。
        appendUrl(sb, base + "/", null, "weekly", "1.0", null);
        appendUrl(sb, base + "/lxmusic", null, "weekly", "0.95", null);
        appendUrl(sb, base + "/tools-lyr", null, "weekly", "0.9", null);
        appendUrl(sb, base + "/search", null, "weekly", "0.9", null);
        appendUrl(sb, base + "/ranking", null, "daily", "0.85", null);
        appendUrl(sb, base + "/latest", null, "daily", "0.85", null);
        appendUrl(sb, base + "/download", null, "monthly", "0.8", null);
        appendUrl(sb, base + "/about", null, "monthly", "0.6", null);
        appendUrl(sb, base + "/privacy", null, "yearly", "0.3", null);

        sb.append("</urlset>\n");
        return sb.toString();
    }

    static String buildMusicXml(String siteBase, List<MusicEntry> musicEntries) {
        String base = siteBase.endsWith("/") ? siteBase.substring(0, siteBase.length() - 1) : siteBase;
        StringBuilder sb = new StringBuilder(musicEntries.size() * 240 + 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"\n");
        sb.append("        xmlns:image=\"http://www.google.com/schemas/sitemap-image/1.1\">\n");

        for (MusicEntry e : musicEntries) {
            MusicImage image = new MusicImage(base + "/media/cover/" + e.id, musicImageTitle(e));
            appendUrl(sb, base + "/detail/" + e.id, e.lastmod, "weekly", "0.8", image);
        }

        sb.append("</urlset>\n");
        return sb.toString();
    }

    static String buildPlaylistXml(String siteBase, List<MusicEntry> playlistEntries) {
        String base = siteBase.endsWith("/") ? siteBase.substring(0, siteBase.length() - 1) : siteBase;
        StringBuilder sb = new StringBuilder(playlistEntries.size() * 160 + 1024);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");

        for (MusicEntry p : playlistEntries) {
            appendUrl(sb, base + "/playlist/" + p.id, p.lastmod, "weekly", "0.6", null);
        }

        sb.append("</urlset>\n");
        return sb.toString();
    }

    private static void appendSitemap(StringBuilder sb, String loc) {
        sb.append("  <sitemap>\n");
        sb.append("    <loc>").append(HtmlEscaper.escape(loc)).append("</loc>\n");
        sb.append("  </sitemap>\n");
    }

    private static void appendUrl(StringBuilder sb, String loc, String lastmod, String changefreq,
                                  String priority, MusicImage image) {
        sb.append("  <url>\n");
        sb.append("    <loc>").append(HtmlEscaper.escape(loc)).append("</loc>\n");
        if (lastmod != null && !lastmod.isBlank()) {
            sb.append("    <lastmod>").append(lastmod).append("</lastmod>\n");
        }
        sb.append("    <changefreq>").append(changefreq).append("</changefreq>\n");
        sb.append("    <priority>").append(priority).append("</priority>\n");
        if (image != null) {
            sb.append("    <image:image>\n");
            sb.append("      <image:loc>").append(HtmlEscaper.escape(image.loc())).append("</image:loc>\n");
            if (image.title() != null && !image.title().isBlank()) {
                sb.append("      <image:title>").append(HtmlEscaper.escape(image.title())).append("</image:title>\n");
            }
            sb.append("    </image:image>\n");
        }
        sb.append("  </url>\n");
    }

    private static String musicImageTitle(MusicEntry entry) {
        String title = entry.title == null ? "" : entry.title.trim();
        String artist = entry.artist == null ? "" : entry.artist.trim();
        if (title.isEmpty()) {
            return artist;
        }
        return artist.isEmpty() ? title : title + " - " + artist;
    }

    private static String resolveRequestPath(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        if (servletPath != null && !servletPath.isBlank()) {
            return servletPath;
        }
        String uri = request.getRequestURI();
        if (uri == null || uri.isBlank()) {
            return INDEX_PATH;
        }
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && uri.startsWith(contextPath)) {
            uri = uri.substring(contextPath.length());
        }
        return uri.isEmpty() ? INDEX_PATH : uri;
    }

    private record MusicImage(String loc, String title) {
    }

    static final class MusicEntry {
        int id;
        String title;
        String artist;
        String lastmod;
    }
}
