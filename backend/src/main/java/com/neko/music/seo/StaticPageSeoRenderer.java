package com.neko.music.seo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.neko.music.Main;
import com.neko.music.service.AppReleaseService;
import com.neko.music.util.ClientReleaseStorage;
import com.neko.music.util.HtmlEscaper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 静态路由（首页 / 落雪导入 / 下载 / 关于 / 隐私 / 排行榜 / 最新）的服务端 SEO HTML。
 *
 * <p>
 * 与 {@link MusicDetailPageRenderer} 一样，只在请求来自爬虫 / 链接预览 / AI 抓取器 时由
 * {@code StaticPageSeoFilter} 调用；普通浏览器依旧拿到 SPA 外壳。这样搜索引擎与 生成式引擎在无 JS
 * 环境下也能读到完整、可引用的正文与结构化数据。
 */
public final class StaticPageSeoRenderer {

    private static final Logger logger = LoggerFactory.getLogger(StaticPageSeoRenderer.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String SITE_NAME_ZH = "Neko歌姬计划";
    private static final String SITE_NAME_EN = "Neko Music";
    private static final String ALT_NAMES = "Neko云音乐 / Neko Music";
    private static final String REPO = "https://github.com/FantasyNetworkCN/NekoMusic";
    private static final String REPO_ANDROID = "https://github.com/FantasyNetworkCN/NekoMusicForAndroid";
    private static final String REPO_PC = "https://github.com/FantasyNetworkCN/NekoMusicForPc";
    private static final String REPO_DOCS = "https://github.com/FantasyNetworkCN/NekoMusicDocs";
    private static final String LICENSE_URL = "https://www.gnu.org/licenses/agpl-3.0.html";
    private static final String SITE_ICON_PATH = "/icons/icon-512.png";

    private static final Set<String> STATIC_PAGES = Set.of(
            "/", "/lxmusic", "/download", "/about", "/privacy", "/ranking", "/latest", "/search");

    public static boolean isStaticPage(String path) {
        return STATIC_PAGES.contains(normalizePath(path));
    }

    /**
     * 未知路径返回 null，由过滤器放行到静态站点处理器。
     */
    public String render(String rawPath, String siteBaseUrl) {
        String path = normalizePath(rawPath);
        if (!STATIC_PAGES.contains(path)) {
            return null;
        }
        String base = trimTrailingSlash(siteBaseUrl);
        Page page = buildPage(path, base);
        if (page == null) {
            return null;
        }
        String pageUrl = "/".equals(path) ? base + "/" : base + path;
        return html(page, base, pageUrl, buildJsonLd(page, base, pageUrl));
    }

    private record Section(String heading, List<String> paragraphs, List<String> bullets) {

    }

    private record Faq(String question, String answer) {

    }

    private record MusicRow(int id, String title, String artist, long playCount) {

    }

    private record Page(String path, String title, String description, String keywords,
            String h1, String lede, String breadcrumb,
            List<Section> sections, List<Faq> faqs,
            boolean softwareApp, boolean howTo, String appVersion,
            List<MusicRow> itemList) {

    }

    private Page buildPage(String path, String base) {
        return switch (path) {
            case "/" ->
                homePage();
            case "/lxmusic" ->
                lxmusicPage();
            case "/download" ->
                downloadPage(base);
            case "/about" ->
                aboutPage();
            case "/privacy" ->
                privacyPage();
            case "/ranking" ->
                rankingPage(base);
            case "/latest" ->
                latestPage(base);
            case "/search" ->
                searchPage(base);
            default ->
                null;
        };
    }

    private static Section bullets(String heading, List<String> items) {
        return new Section(heading, List.of(), items);
    }

    private static Section paragraph(String heading, String... paragraphs) {
        return new Section(heading, List.of(paragraphs), List.of());
    }

    private static String linkItem(String label, String url) {
        return label + "：<a href=\"" + esc(url) + "\">下载 Download</a>";
    }

    /**
     * 安装包体积后缀，如 " · 12.3 MB"；文件缺失时返回空串
     */
    private static String sizeSuffix(String fileName) {
        long bytes = ClientReleaseStorage.fileSizeOrZero(fileName);
        if (bytes <= 0) {
            return "";
        }
        double mb = bytes / (1024.0 * 1024.0);
        if (mb >= 1) {
            return " · " + String.format(java.util.Locale.ROOT, "%.1f MB", mb);
        }
        return " · " + Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    private static Optional<AppReleaseService.AppRelease> publishedRelease() {
        try {
            return Main.getAppReleaseService().getPublishedReleaseForClients();
        } catch (Exception e) {
            logger.warn("读取客户端版本失败（静态页 SEO）", e);
            return Optional.empty();
        }
    }

    private static List<MusicRow> queryRanking(int limit) {
        return queryMusic("SELECT id, title, artist, play_count FROM music WHERE play_count > 0 "
                + "ORDER BY play_count DESC LIMIT ?", limit, true);
    }

    private static List<MusicRow> queryLatest(int limit) {
        return queryMusic("SELECT id, title, artist FROM music ORDER BY created_at DESC LIMIT ?", limit, false);
    }

    private static List<MusicRow> queryMusic(String sql, int limit, boolean withCount) {
        List<MusicRow> rows = new ArrayList<>();
        try (Connection conn = Main.getDatabaseManager().getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    rows.add(new MusicRow(
                            rs.getInt("id"),
                            rs.getString("title"),
                            rs.getString("artist"),
                            withCount ? rs.getLong("play_count") : 0));
                }
            }
        } catch (Exception e) {
            logger.error("静态页 SEO 查询音乐失败", e);
        }
        return rows;
    }

    private static List<String> musicBullets(String base, List<MusicRow> rows) {
        List<String> items = new ArrayList<>();
        int rank = 1;
        for (MusicRow row : rows) {
            String title = row.title() == null || row.title().isBlank() ? "未知歌曲" : row.title();
            String artist = row.artist() == null || row.artist().isBlank() ? "未知艺术家" : row.artist();
            StringBuilder item = new StringBuilder();
            if (row.playCount() > 0) {
                item.append(rank).append(". ");
            }
            item.append("<a href=\"").append(esc(base + "/detail/" + row.id())).append("\">")
                    .append(esc(title)).append("</a> - ").append(esc(artist));
            if (row.playCount() > 0) {
                item.append("（").append(row.playCount()).append(" 次播放）");
            }
            items.add(item.toString());
            rank++;
        }
        if (items.isEmpty()) {
            items.add("暂无数据，请稍后刷新或打开 Web 播放器查看实时内容。");
        }
        return items;
    }

    private Page homePage() {
        List<Section> sections = List.of(
                bullets("核心功能", List.of(
                        "在线搜索与播放：无需安装即可收听站内曲库。",
                        "收藏与歌单：登录后同步收藏、创建个人歌单。",
                        "高品质音频：支持高码率流媒体播放。",
                        "歌词与分享：歌词同步，一键分享播放链接。",
                        "多端一致：Web、Android、Windows、Linux、macOS 体验一致。",
                        "落雪歌单导入：Web 可直接上传落雪音乐 LX Music 导出的 LXMC / .lxmc 文件并匹配导入歌单。",
                        "其它歌单迁入：Android / PC 客户端可从网易云音乐、QQ 音乐、酷狗音乐导入歌单。")),
                bullets("支持的平台", List.of(
                        "Web 播放器（无需安装）",
                        "Android 客户端（.apk）",
                        "Windows 客户端（.exe）",
                        "Linux 客户端（.deb / AUR）",
                        "macOS 客户端（.pkg）")),
                paragraph("完全免费与开源",
                        "Neko歌姬计划是独立开发的开源项目，遵循 AGPL-3.0 协议，代码托管在 GitHub。",
                        "平台不收费、无强制订阅、无广告弹窗；客户端行为可自行查阅源码。"),
                paragraph("落雪音乐 LX Music 歌单导入",
                        "登录 Web 播放器后进入「我的歌单」，点击「导入落雪歌单」，上传落雪音乐（LX Music）导出的 LXMC / .lxmc 文件。"
                        + "Neko 会自动解析文件内的歌单，在服务器后台按曲名与歌手匹配曲库并导入到目标歌单。",
                        "落雪歌单迁移支持多歌单文件选择、来源顺序保留和后台处理；导入结果也会写入站内消息。"),
                paragraph("从其它音乐平台迁入歌单",
                        "在 Android 或桌面客户端内使用「导入外部歌单」：粘贴网易云音乐 / QQ 音乐 / 酷狗音乐的歌单分享链接或歌单 ID，"
                        + "客户端会拉取曲目列表并在 Neko 曲库中按歌名与歌手匹配后导入。详细步骤见下载页。"));
        List<Faq> faqs = List.of(
                new Faq("Neko歌姬计划是免费的吗？",
                        "是的。Neko歌姬计划完全免费，没有强制订阅费用，也不含广告弹窗；项目为开源软件。"),
                new Faq("如何导入落雪音乐 LX Music 的歌单？",
                        "登录 Web 播放器，在首页点击「立即导入 LXMC」，或进入「我的歌单」点击「导入落雪歌单」，"
                        + "上传落雪音乐导出的 LXMC / .lxmc 文件，然后选择文件内歌单和目标歌单即可。"),
                new Faq("如何从网易云音乐、QQ 音乐或酷狗音乐导入歌单？",
                        "在 Android 或桌面客户端内选择「导入外部歌单」，粘贴歌单分享链接或歌单 ID，客户端会拉取曲目并匹配站内曲库后导入。"),
                new Faq("支持哪些平台？",
                        "提供 Web 播放器，以及 Android、Windows、Linux、macOS 客户端。"),
                new Faq("需要注册账号吗？",
                        "浏览和搜索无需登录；收藏、歌单与多端同步需要注册并登录账号。"),
                new Faq("Neko歌姬计划是开源的吗？",
                        "是。源码托管在 GitHub（FantasyNetworkCN/NekoMusic 等仓库），遵循 AGPL-3.0 协议。"));
        String androidVer = publishedRelease().map(AppReleaseService.AppRelease::androidVer).orElse("");
        return new Page("/",
                "Neko歌姬计划（原Neko云音乐）- 落雪音乐 LXMC 歌单导入与免费在线音乐 | Neko Music",
                "Neko歌姬计划是免费、开源、无广告的在线音乐播放平台；支持落雪音乐 LX Music 导出的 LXMC / .lxmc 文件歌单导入，"
                + "也可从网易云、QQ 音乐、酷狗迁入歌单，多端同步。",
                "Neko歌姬计划,Neko云音乐,Neko Music,免费音乐,在线音乐,免费听歌,开源音乐,无广告音乐,"
                + "落雪音乐,LX Music,LxMusic,LXMC,lxmc,落雪歌单导入,落雪音乐歌单迁移,"
                + "网易云歌单迁移,QQ音乐歌单迁移,酷狗歌单迁移",
                "Neko歌姬计划 - 免费开源的在线音乐播放平台",
                "完全免费、开源、无广告：搜索、播放、收藏、歌单与多端同步；"
                + "Web 支持导入落雪音乐 LX Music 的 LXMC / .lxmc 歌单文件，客户端支持网易云、QQ 音乐、酷狗歌单迁入。",
                "首页 Home", sections, faqs, true, false, androidVer, List.of());
    }

    private Page lxmusicPage() {
        List<Section> sections = List.of(
                paragraph("落雪音乐 LX Music 歌单导入",
                        "落雪音乐（LX Music）可以将用户整理好的歌单导出为 LXMC / .lxmc 文件。Neko歌姬计划提供网页端直接导入："
                        + "上传文件后自动识别其中的歌单，选择目标歌单，再由服务器匹配站内曲库并完成迁移。",
                        "整个过程无需安装 Neko 客户端，也无需把 LXMC 文件手动转换成 CSV、文本列表或逐首复制歌曲信息。"),
                bullets("支持能力", List.of(
                        "读取落雪音乐 LX Music 导出的 LXMC / .lxmc 歌单文件。",
                        "文件包含多个歌单时，可先选择需要迁移的那一个。",
                        "按曲名与歌手自动匹配 Neko 曲库，并尝试补全未命中的曲目。",
                        "保留成功曲目的来源顺序，写入已有目标歌单或新建歌单。",
                        "导入在服务器后台执行，离开进度页面后任务仍可继续。")),
                bullets("导入步骤", List.of(
                        "在落雪音乐中导出需要迁移的歌单，得到 .lxmc 文件。",
                        "登录 Neko歌姬计划，打开本页或「我的歌单」。",
                        "点击「导入落雪歌单」并上传 LXMC 文件。",
                        "选择文件内歌单及目标歌单，等待后台匹配完成。")),
                paragraph("兼容说明",
                        "导入结果取决于站内曲库以及服务端能否补全对应音频；未找到的曲目会被标记为失败，但不会中断其余曲目导入。",
                        "Neko歌姬计划是独立开源音乐平台，与落雪音乐官方无隶属关系，仅提供对 LXMC 导出文件的兼容导入能力。"));
        List<Faq> faqs = List.of(
                new Faq("LXMC 文件是什么？",
                        "LXMC 是落雪音乐（LX Music）用于导出歌单的文件格式，通常带有 .lxmc 扩展名。Neko歌姬计划可以直接读取并解析其中的歌单。"),
                new Faq("如何把落雪音乐歌单导入 Neko歌姬计划？",
                        "登录 Web 播放器后打开「我的歌单」，点击「导入落雪歌单」，上传 LXMC 文件，选择文件内歌单和目标歌单后开始匹配导入。"),
                new Faq("导入落雪歌单需要安装客户端吗？",
                        "不需要。落雪 LXMC 歌单导入直接在 Web 端完成；Android 与 PC 客户端主要用于其它外部音乐平台的歌单迁移。"),
                new Faq("为什么 LXMC 歌单里有些歌曲没有导入？",
                        "站内曲库没有对应音频且服务端无法补全时，该曲目会显示失败。导入任务会继续处理剩余曲目。"),
                new Faq("Neko歌姬计划与落雪音乐是什么关系？",
                        "两者没有隶属或合作关系。Neko歌姬计划是独立开源音乐平台，仅支持导入落雪音乐导出的歌单文件。"));
        return new Page("/lxmusic",
                "落雪音乐 LX Music 歌单导入 - LXMC 文件迁移 | Neko歌姬计划",
                "在线导入落雪音乐（LX Music）导出的 LXMC / .lxmc 歌单文件。上传后自动解析文件内歌单，选择目标歌单，"
                        + "由 Neko 服务器匹配曲库并后台完成落雪歌单迁移。免费、无需安装客户端。",
                "落雪音乐,落雪音乐歌单导入,落雪歌单迁移,LX Music,LxMusic,LXMC,lxmc,lxmc文件,"
                        + "落雪音乐LXMC,落雪音乐怎么导入歌单,LX Music歌单导入,歌单迁移,Neko歌姬计划",
                "落雪音乐 LX Music 歌单导入",
                "上传落雪音乐导出的 LXMC / .lxmc 文件，自动识别歌单、匹配曲库并在后台完成迁移。",
                "落雪导入 LX Music", sections, faqs, false, true, "", List.of());
    }

    private Page searchPage(String base) {
        List<Section> sections = List.of(
                bullets("搜索建议", List.of(
                        "输入歌名、歌手或专辑关键词即可搜索，支持中文与英文。",
                        "可用空格组合多个关键词，例如「歌手 歌名」。",
                        "登录后可收藏搜索结果、加入歌单并多端同步。")),
                bullets("热门入口", List.of(
                        "<a href=\"" + esc(base + "/ranking") + "\">热门音乐排行榜</a>",
                        "<a href=\"" + esc(base + "/latest") + "\">最新上架音乐</a>",
                        "<a href=\"" + esc(base + "/download") + "\">下载 Android / PC 客户端</a>")));
        List<Faq> faqs = List.of(
                new Faq("搜索音乐需要付费吗？", "不需要，搜索与在线播放均完全免费。"),
                new Faq("可以搜索哪些内容？", "支持按歌名、歌手与专辑关键词搜索站内曲库。"));
        return new Page("/search",
                "搜索音乐 - Neko歌姬计划 | 免费音乐搜索",
                "在 Neko歌姬计划免费搜索您喜爱的音乐，按歌名、歌手或专辑关键词查找并在线播放。完全免费，无需付费。",
                "音乐搜索,免费音乐搜索,在线搜索,免费听歌,Neko歌姬计划",
                "搜索音乐",
                "在 Neko歌姬计划按歌名、歌手或专辑关键词搜索，免费在线播放。",
                "搜索 Search", sections, faqs, false, false, "", List.of());
    }

    private Page downloadPage(String base) {
        Optional<AppReleaseService.AppRelease> release = publishedRelease();
        String androidVer = release.map(AppReleaseService.AppRelease::androidVer).orElse("");
        String pcVer = release.map(AppReleaseService.AppRelease::pcVer).orElse("");

        List<String> packages = new ArrayList<>();
        if (!androidVer.isBlank()) {
            String apk = ClientReleaseStorage.androidApkFileName(androidVer);
            packages.add(linkItem("Android 客户端 v" + esc(androidVer) + "（.apk" + sizeSuffix(apk) + "）",
                    ClientReleaseStorage.publicDownloadUrl(base, apk)));
        }
        if (!pcVer.isBlank()) {
            String exe = ClientReleaseStorage.windowsExeFileName(pcVer);
            packages.add(linkItem("Windows 客户端 v" + esc(pcVer) + "（.exe" + sizeSuffix(exe) + "）",
                    ClientReleaseStorage.publicDownloadUrl(base, exe)));
            String deb = ClientReleaseStorage.linuxDebFileName(pcVer);
            packages.add(linkItem("Linux 客户端 v" + esc(pcVer) + "（.deb，Debian 系" + sizeSuffix(deb) + "）",
                    ClientReleaseStorage.publicDownloadUrl(base, deb)));
            String pkg = ClientReleaseStorage.macPkgFileName(pcVer);
            packages.add(linkItem("macOS 客户端 v" + esc(pcVer) + "（.pkg" + sizeSuffix(pkg) + "）",
                    ClientReleaseStorage.publicDownloadUrl(base, pkg)));
        }
        if (packages.isEmpty()) {
            packages.add("安装包正在准备中，请稍后刷新本页获取最新版本；也可先使用 Web 播放器。");
        }

        List<Section> sections = List.of(
                bullets("安装包下载", packages),
                bullets("歌单迁入步骤", List.of(
                        "落雪音乐用户：在 Web 端「我的歌单」上传 LXMC / .lxmc 文件，选择目标歌单后匹配导入。",
                        "在网易云音乐 / QQ 音乐 / 酷狗音乐复制歌单分享链接，或记下歌单 ID。",
                        "安装并打开本页提供的 Android / Windows / Linux / macOS 客户端。",
                        "在客户端内找到「导入外部歌单」，粘贴链接或 ID，选择目标歌单并开始匹配导入。",
                        "等待匹配完成，导入的曲目会出现在你指定的歌单中。")),
                bullets("系统要求", List.of(
                        "Android：Android 7.0 及以上，安装 .apk。",
                        "Windows：Windows 10 / 11（64 位），安装 .exe。",
                        "Linux：Debian 系使用 .deb；Arch 系使用 AUR（<code>yay -S neko-cloud-music</code>）。",
                        "macOS：macOS 11 及以上，安装 .pkg。")),
                paragraph("安装提示",
                        "若安装时被系统安全策略拦截，请在系统设置中允许来自该开发者的应用。",
                        "请确认安装包来源为本站，避免从第三方渠道下载被篡改的安装包。"),
                bullets("为什么选择 Neko 客户端", List.of(
                        "完全免费、开源透明。",
                        "支持从网易云音乐 / QQ 音乐 / 酷狗音乐迁入歌单。",
                        "与 Web 端一致的搜索、播放、收藏与歌单体验。",
                        "客户端可播放本机音频并具备更强的离线能力。")));
        List<Faq> faqs = List.of(
                new Faq("下载需要付费吗？", "不需要，Neko歌姬计划所有客户端均完全免费，且不含广告。"),
                new Faq("支持哪些系统？",
                        "提供 Android、Windows、Linux（Debian 系 / Arch 系）与 macOS 客户端，另有免安装的 Web 播放器。"),
                new Faq("如何导入网易云 / QQ 音乐 / 酷狗歌单？",
                        "在客户端内使用「导入外部歌单」，粘贴歌单分享链接或歌单 ID，客户端会按歌名与歌手匹配站内曲库后导入。"),
                new Faq("如何导入落雪音乐 LX Music 的 LXMC 歌单？",
                        "落雪 LXMC 歌单在 Web 端导入：登录后进入「我的歌单」，点击「导入落雪歌单」，上传 .lxmc 文件并选择目标歌单。"),
                new Faq("安装时被系统拦截怎么办？",
                        "在系统设置中允许来自该开发者的应用；请务必从本站下载安装包。"),
                new Faq("客户端是开源的吗？",
                        "是，源码托管在 GitHub 的 FantasyNetworkCN/NekoMusicForAndroid 与 NekoMusicForPc 仓库。"));
        return new Page("/download",
                "下载客户端 - Neko歌姬计划 | Android / Windows / Linux / macOS 免费音乐应用",
                "下载 Neko 云音乐 Android / Windows / Linux / macOS 客户端：完全免费、开源透明，"
                + "Web 支持落雪音乐 LX Music LXMC / .lxmc 歌单导入，客户端支持从网易云音乐、QQ 音乐、酷狗音乐迁入歌单。",
                "Neko歌姬计划下载,APP下载,免费音乐APP,Android音乐,PC下载,桌面音乐,"
                + "落雪音乐,LX Music,LXMC,lxmc,落雪歌单导入,网易云导入歌单,QQ音乐导入歌单,酷狗导入歌单,Linux音乐播放器,macOS音乐",
                "下载 Neko 云音乐客户端",
                "选择你的平台，一键获取安装包：完全免费、开源透明，Android / PC 客户端支持从网易云音乐、QQ 音乐、酷狗音乐迁入歌单。",
                "下载 Download", sections, faqs, true, true, pcVer, List.of());
    }

    private Page aboutPage() {
        List<Section> sections = List.of(
                paragraph("项目介绍",
                        "Neko歌姬计划是一个完全免费的在线音乐播放平台，致力于为用户提供高品质的音乐体验。",
                        "平台支持在线播放、音乐搜索、个人收藏、歌单创建等功能，无需付费，永久免费。"),
                bullets("主要特性", List.of(
                        "高品质音频播放",
                        "强大的音乐搜索功能",
                        "个人收藏与歌单管理",
                        "Android / PC 客户端支持从网易云、QQ 音乐、酷狗音乐迁入歌单",
                        "移动端与桌面端一致体验",
                        "完全免费，无需付费")),
                paragraph("开源协议",
                        "本项目遵循 AGPL-3.0 开源协议，代码托管在 GitHub，欢迎贡献代码、提出建议和反馈问题。"),
                bullets("联系我们", List.of(
                        "邮箱：<a href=\"mailto:support@nekocore.cn\">support@nekocore.cn</a>",
                        "QQ 群：932258919")),
                paragraph("致谢",
                        "感谢所有使用 Neko歌姬计划的用户，以及所有为项目做出贡献的开发者。")
        );
        List<Faq> faqs = List.of(
                new Faq("Neko歌姬计划是谁开发的？", "由 FantasyNetworkCN 及社区贡献者共同开发维护，是独立开源项目。"),
                new Faq("如何反馈问题或参与贡献？", "可通过 GitHub 仓库提交 Issue 或 PR，也可通过邮箱 support@nekocore.cn 联系。"));
        return new Page("/about",
                "关于我们 - Neko歌姬计划 | 免费开源音乐平台",
                "了解 Neko歌姬计划：一个完全免费、开源的在线音乐播放平台，支持搜索、播放、收藏、歌单与多端同步。",
                "关于我们,Neko歌姬计划介绍,免费音乐平台,开源音乐项目",
                "关于 Neko歌姬计划",
                "完全免费的在线音乐播放平台，支持在线播放、音乐搜索、个人收藏与歌单管理。",
                "关于 About", sections, faqs, false, false, "", List.of());
    }

    private Page privacyPage() {
        List<Section> sections = List.of(
                paragraph("总则",
                        "本页概述 Neko歌姬计划如何收集、使用与保护用户信息。",
                        "完整条款以 Web 播放器内的隐私政策页面为准。"),
                bullets("我们收集的信息", List.of(
                        "注册信息：用户名、邮箱等用于账号体系的必要信息。",
                        "使用数据：播放、收藏、歌单等用于提供服务的记录。",
                        "技术日志：为安全与稳定性记录必要的访问日志。")),
                bullets("信息的使用与保护", List.of(
                        "仅用于提供、维护与改进音乐服务。",
                        "不向第三方出售个人信息。",
                        "采取访问控制等措施保护数据安全。")),
                bullets("你的权利", List.of(
                        "可查看与修改账号资料。",
                        "可申请删除账号及相关数据。",
                        "可通过 support@nekocore.cn 联系我们行使权利。"))
        );
        return new Page("/privacy",
                "隐私政策 - Neko歌姬计划",
                "了解 Neko歌姬计划如何收集、使用、保存和保护用户信息。",
                "隐私政策,个人信息保护,Neko歌姬计划",
                "隐私政策",
                "我们如何收集、使用与保护你的信息。",
                "隐私政策 Privacy", sections, List.of(), false, false, "", List.of());
    }

    private Page rankingPage(String base) {
        List<MusicRow> rows = queryRanking(50);
        List<Section> sections = List.of(
                paragraph("热门音乐排行榜",
                        "以下为按站内播放次数排序的热门音乐快照，点击曲目即可免费在线播放。",
                        "实时榜单请打开 Web 播放器或客户端查看。"),
                bullets("当前热门曲目", musicBullets(base, rows)));
        List<Faq> faqs = List.of(
                new Faq("排行榜是如何排序的？", "按站内播放次数从高到低排序，数据随播放实时变化。"),
                new Faq("如何播放榜单中的歌曲？", "点击任意曲目进入详情页即可免费在线播放。"));
        return new Page("/ranking",
                "热门音乐排行榜 - Neko歌姬计划 | 免费音乐排行",
                "查看基于播放次数排序的热门音乐排行榜，发现最受欢迎的免费音乐。完全免费，无需付费。",
                "热门音乐,音乐排行榜,免费音乐排行,热门排行,Neko歌姬计划",
                "热门音乐排行榜",
                "按站内播放次数排序的热门曲目，点击即可免费在线播放。",
                "排行榜 Ranking", sections, faqs, false, false, "", rows);
    }

    private Page latestPage(String base) {
        List<MusicRow> rows = queryLatest(50);
        List<Section> sections = List.of(
                paragraph("最新音乐",
                        "以下为最近上传到站内的音乐快照，点击曲目即可免费在线播放。",
                        "实时列表请打开 Web 播放器或客户端查看。"),
                bullets("最近上新", musicBullets(base, rows)));
        List<Faq> faqs = List.of(
                new Faq("最新音乐多久更新？", "页面快照按上传时间排序，新上传的曲目会出现在列表顶部。"),
                new Faq("如何上传音乐？", "登录后进入「上传音乐」页面即可分享你的作品。"));
        return new Page("/latest",
                "最新音乐 - Neko歌姬计划 | 免费新歌",
                "查看刚刚上传的最新音乐，发现最新的免费音乐资源。完全免费，无需付费。",
                "最新音乐,新歌上线,免费新歌,音乐上新,Neko歌姬计划",
                "最新音乐",
                "最近上传到站内的曲目，点击即可免费在线播放。",
                "最新 Latest", sections, faqs, false, false, "", rows);
    }

    private String buildJsonLd(Page page, String base, String pageUrl) {
        try {
            ArrayNode graph = JSON.createArrayNode();

            ObjectNode org = JSON.createObjectNode();
            org.put("@type", "Organization");
            org.put("@id", base + "/#organization");
            org.put("name", SITE_NAME_ZH);
            org.put("alternateName", ALT_NAMES);
            org.put("url", base + "/");
            ObjectNode logo = org.putObject("logo");
            logo.put("@type", "ImageObject");
            logo.put("url", base + SITE_ICON_PATH);
            logo.put("width", 512);
            logo.put("height", 512);
            ArrayNode sameAs = org.putArray("sameAs");
            sameAs.add(REPO);
            sameAs.add(REPO_ANDROID);
            sameAs.add(REPO_PC);
            sameAs.add(REPO_DOCS);
            graph.add(org);

            ObjectNode site = JSON.createObjectNode();
            site.put("@type", "WebSite");
            site.put("@id", base + "/#website");
            site.put("url", base + "/");
            site.put("name", SITE_NAME_ZH);
            site.put("alternateName", SITE_NAME_EN);
            site.put("publisher", ref(base + "/#organization"));
            site.set("inLanguage", langs());
            ObjectNode searchAction = site.putObject("potentialAction");
            searchAction.put("@type", "SearchAction");
            ObjectNode target = searchAction.putObject("target");
            target.put("@type", "EntryPoint");
            target.put("urlTemplate", base + "/search?q={search_term_string}");
            searchAction.put("query-input", "required name=search_term_string");
            graph.add(site);

            ObjectNode breadcrumb = JSON.createObjectNode();
            breadcrumb.put("@type", "BreadcrumbList");
            breadcrumb.put("@id", pageUrl + "#breadcrumb");
            ArrayNode items = breadcrumb.putArray("itemListElement");
            items.add(breadcrumbItem(1, base + "/", "首页 Home"));
            if (!"/".equals(page.path())) {
                items.add(breadcrumbItem(2, pageUrl, page.breadcrumb()));
            }
            graph.add(breadcrumb);

            ObjectNode webPage = JSON.createObjectNode();
            webPage.put("@type", page.itemList().isEmpty() ? "WebPage" : "CollectionPage");
            webPage.put("@id", pageUrl + "#webpage");
            webPage.put("url", pageUrl);
            webPage.put("name", page.title());
            webPage.put("description", page.description());
            webPage.put("isPartOf", ref(base + "/#website"));
            webPage.put("breadcrumb", ref(pageUrl + "#breadcrumb"));
            ObjectNode primaryImage = webPage.putObject("primaryImageOfPage");
            primaryImage.put("@type", "ImageObject");
            primaryImage.put("url", base + "/og-image.jpg");
            primaryImage.put("width", 1024);
            primaryImage.put("height", 559);
            webPage.set("inLanguage", langs());
            if (!page.itemList().isEmpty()) {
                String itemListId = pageUrl + "#itemlist";
                webPage.put("mainEntity", ref(itemListId));
                graph.add(itemListNode(page, base, itemListId));
            }
            graph.add(webPage);

            if (page.softwareApp()) {
                graph.add(softwareAppNode(page, base));
            }
            if (page.howTo()) {
                graph.add(howToNode(page, base));
            }
            if (!page.faqs().isEmpty()) {
                graph.add(faqNode(page, pageUrl));
            }

            ObjectNode root = JSON.createObjectNode();
            root.put("@context", "https://schema.org");
            root.set("@graph", graph);
            return JsonLd.scriptSafe(JSON.writeValueAsString(root));
        } catch (Exception e) {
            logger.warn("生成静态页 JSON-LD 失败", e);
            return "{}";
        }
    }

    private static ObjectNode softwareAppNode(Page page, String base) {
        ObjectNode app = JSON.createObjectNode();
        app.put("@type", "SoftwareApplication");
        app.put("@id", base + "/#app");
        app.put("name", "Neko歌姬计划（Neko Music）");
        app.put("applicationCategory", "MultimediaApplication");
        app.put("operatingSystem", "Web, Android, Windows, Linux, macOS");
        app.put("isAccessibleForFree", true);
        if (page.appVersion() != null && !page.appVersion().isBlank()) {
            app.put("softwareVersion", page.appVersion());
        }
        app.put("url", base + "/");
        app.put("downloadUrl", base + "/download");
        app.put("installUrl", base + "/download");
        app.put("license", LICENSE_URL);
        app.put("codeRepository", REPO);
        ObjectNode offers = app.putObject("offers");
        offers.put("@type", "Offer");
        offers.put("price", "0");
        offers.put("priceCurrency", "CNY");
        offers.put("description", "Permanently Free / 永久免费");
        ObjectNode publisher = app.putObject("publisher");
        publisher.put("@type", "Organization");
        publisher.put("name", SITE_NAME_ZH);
        publisher.put("url", base + "/");
        return app;
    }

    private static ObjectNode itemListNode(Page page, String base, String itemListId) {
        ObjectNode list = JSON.createObjectNode();
        list.put("@type", "ItemList");
        list.put("@id", itemListId);
        list.put("numberOfItems", page.itemList().size());
        ArrayNode elements = list.putArray("itemListElement");
        for (int i = 0; i < page.itemList().size(); i++) {
            MusicRow row = page.itemList().get(i);
            String detailUrl = base + "/detail/" + row.id();
            ObjectNode item = JSON.createObjectNode();
            item.put("@type", "ListItem");
            item.put("position", i + 1);
            item.put("url", detailUrl);
            item.put("name", row.title() == null ? "" : row.title());
            ObjectNode recording = item.putObject("item");
            recording.put("@type", "MusicRecording");
            recording.put("@id", detailUrl + "#recording");
            recording.put("name", row.title() == null ? "" : row.title());
            recording.put("url", detailUrl);
            ObjectNode byArtist = recording.putObject("byArtist");
            byArtist.put("@type", "MusicGroup");
            byArtist.put("name", row.artist() == null ? "" : row.artist());
            elements.add(item);
        }
        return list;
    }

    private static ObjectNode howToNode(Page page, String base) {
        ObjectNode howTo = JSON.createObjectNode();
        howTo.put("@type", "HowTo");
        boolean lxmusic = "/lxmusic".equals(page.path());
        howTo.put("@id", base + page.path() + "#howto");
        howTo.put("name", lxmusic
                ? "导入落雪音乐 LX Music 的 LXMC 歌单"
                : "从网易云音乐 / QQ 音乐 / 酷狗音乐迁入歌单");
        howTo.put("description", lxmusic
                ? "在 Neko歌姬计划 Web 端上传 LXMC 文件并匹配导入落雪歌单的步骤。"
                : "在 Neko 客户端内导入外部歌单的步骤。");
        String[][] steps = lxmusic
                ? new String[][]{
                    {"导出 LXMC 文件", "在落雪音乐中将需要迁移的歌单导出为 LXMC / .lxmc 文件。"},
                    {"登录并打开我的歌单", "登录 Neko歌姬计划 Web 播放器，进入「我的歌单」。"},
                    {"上传并选择歌单", "点击「导入落雪歌单」，上传文件并选择文件内歌单与目标歌单。"},
                    {"等待后台匹配", "服务器会匹配站内曲库并将会员曲目按来源顺序导入目标歌单。"}
                }
                : new String[][]{
                    {"复制歌单链接或 ID", "在网易云音乐 / QQ 音乐 / 酷狗音乐复制歌单分享链接，或记下歌单 ID。"},
                    {"安装并打开客户端", "安装 Neko歌姬计划提供的 Android / Windows / Linux / macOS 客户端。"},
                    {"使用「导入外部歌单」", "在客户端内选择「导入外部歌单」，粘贴链接或 ID 并选择目标歌单。"},
                    {"等待匹配完成", "客户端会按歌名与歌手匹配站内曲库并导入曲目。"}
                };
        ArrayNode stepArray = howTo.putArray("step");
        for (int i = 0; i < steps.length; i++) {
            ObjectNode step = JSON.createObjectNode();
            step.put("@type", "HowToStep");
            step.put("position", i + 1);
            step.put("name", steps[i][0]);
            step.put("text", steps[i][1]);
            stepArray.add(step);
        }
        return howTo;
    }

    private static ObjectNode faqNode(Page page, String pageUrl) {
        ObjectNode faq = JSON.createObjectNode();
        faq.put("@type", "FAQPage");
        faq.put("@id", pageUrl + "#faq");
        ArrayNode items = faq.putArray("mainEntity");
        for (Faq f : page.faqs()) {
            ObjectNode question = JSON.createObjectNode();
            question.put("@type", "Question");
            question.put("name", f.question());
            ObjectNode answer = question.putObject("acceptedAnswer");
            answer.put("@type", "Answer");
            answer.put("text", f.answer());
            items.add(question);
        }
        return faq;
    }

    private static ObjectNode ref(String id) {
        ObjectNode node = JSON.createObjectNode();
        node.put("@id", id);
        return node;
    }

    private static ArrayNode langs() {
        ArrayNode arr = JSON.createArrayNode();
        arr.add("zh-CN");
        arr.add("en");
        return arr;
    }

    private static ObjectNode breadcrumbItem(int position, String itemUrl, String name) {
        ObjectNode item = JSON.createObjectNode();
        item.put("@type", "ListItem");
        item.put("position", position);
        item.put("name", name);
        item.put("item", itemUrl);
        return item;
    }

    private String html(Page page, String base, String pageUrl, String jsonLd) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("<!DOCTYPE html>\n");
        sb.append("<html lang=\"zh-CN\" prefix=\"og: https://ogp.me/ns#\">\n<head>\n");
        sb.append("<meta charset=\"UTF-8\">\n");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        sb.append("<title>").append(esc(page.title())).append("</title>\n");
        sb.append("<meta name=\"description\" content=\"").append(esc(page.description())).append("\">\n");
        sb.append("<meta name=\"keywords\" content=\"").append(esc(page.keywords())).append("\">\n");
        sb.append("<meta name=\"author\" content=\"").append(SITE_NAME_EN).append("\">\n");
        sb.append("<meta name=\"publisher\" content=\"").append(SITE_NAME_ZH).append(" / ").append(SITE_NAME_EN).append("\">\n");
        sb.append("<meta name=\"robots\" content=\"index, follow, max-image-preview:large, max-snippet:-1, max-video-preview:-1\">\n");
        sb.append("<meta name=\"googlebot\" content=\"index, follow\">\n");
        sb.append("<meta name=\"bingbot\" content=\"index, follow\">\n");
        sb.append("<meta name=\"theme-color\" content=\"#04090b\">\n");
        sb.append("<link rel=\"canonical\" href=\"").append(esc(pageUrl)).append("\">\n");
        sb.append("<link rel=\"alternate\" hreflang=\"zh-CN\" href=\"").append(esc(pageUrl)).append("\">\n");
        sb.append("<link rel=\"alternate\" hreflang=\"en\" href=\"").append(esc(pageUrl)).append("\">\n");
        sb.append("<link rel=\"alternate\" hreflang=\"x-default\" href=\"").append(esc(pageUrl)).append("\">\n");
        sb.append("<link rel=\"icon\" href=\"/favicon.ico\">\n");
        sb.append("<link rel=\"sitemap\" type=\"application/xml\" href=\"/sitemap.xml\">\n");
        sb.append("<link rel=\"alternate\" type=\"text/plain\" href=\"/llms.txt\" title=\"llms.txt\">\n");
        sb.append("<meta property=\"og:type\" content=\"website\">\n");
        sb.append("<meta property=\"og:site_name\" content=\"").append(SITE_NAME_ZH).append(" (").append(SITE_NAME_EN).append(")\">\n");
        sb.append("<meta property=\"og:url\" content=\"").append(esc(pageUrl)).append("\">\n");
        sb.append("<meta property=\"og:title\" content=\"").append(esc(page.title())).append("\">\n");
        sb.append("<meta property=\"og:description\" content=\"").append(esc(page.description())).append("\">\n");
        sb.append("<meta property=\"og:image\" content=\"").append(esc(base)).append("/og-image.jpg\">\n");
        sb.append("<meta property=\"og:image:width\" content=\"1024\">\n");
        sb.append("<meta property=\"og:image:height\" content=\"559\">\n");
        sb.append("<meta property=\"og:image:alt\" content=\"").append(esc(page.title())).append("\">\n");
        sb.append("<meta property=\"og:locale\" content=\"zh_CN\">\n");
        sb.append("<meta property=\"og:locale:alternate\" content=\"en_US\">\n");
        sb.append("<meta name=\"twitter:card\" content=\"summary_large_image\">\n");
        sb.append("<meta name=\"twitter:url\" content=\"").append(esc(pageUrl)).append("\">\n");
        sb.append("<meta name=\"twitter:title\" content=\"").append(esc(page.title())).append("\">\n");
        sb.append("<meta name=\"twitter:description\" content=\"").append(esc(page.description())).append("\">\n");
        sb.append("<meta name=\"twitter:image\" content=\"").append(esc(base)).append("/og-image.jpg\">\n");
        sb.append(STYLE);
        sb.append("<script type=\"application/ld+json\">").append(jsonLd).append("</script>\n");
        sb.append("</head>\n<body>\n");
        sb.append("<a class=\"skip-link\" href=\"#main-content\">Skip to content / 跳到正文</a>\n");
        sb.append("<header>\n<nav aria-label=\"Main navigation\">\n");
        sb.append(link(base + "/", "首页 Home")).append(" | ")
                .append(link(base + "/lxmusic", "落雪导入 LX Music")).append(" | ")
                .append(link(base + "/download", "下载 Download")).append(" | ")
                .append(link(base + "/ranking", "排行榜 Ranking")).append(" | ")
                .append(link(base + "/latest", "最新 Latest")).append(" | ")
                .append(link(base + "/search", "搜索 Search")).append(" | ")
                .append(link(base + "/about", "关于 About")).append(" | ")
                .append("<a href=\"").append(REPO).append("\" rel=\"noopener noreferrer\">GitHub</a>\n");
        sb.append("</nav>\n</header>\n");
        sb.append("<main id=\"main-content\">\n");
        sb.append("<nav aria-label=\"Breadcrumb\"><ol>\n");
        sb.append("<li>").append(link(base + "/", "首页 Home")).append("</li>\n");
        if (!"/".equals(page.path())) {
            sb.append("<li>").append(esc(page.breadcrumb())).append("</li>\n");
        }
        sb.append("</ol></nav>\n");
        sb.append("<h1>").append(esc(page.h1())).append("</h1>\n");
        if (page.lede() != null && !page.lede().isBlank()) {
            sb.append("<p class=\"lede\">").append(esc(page.lede())).append("</p>\n");
        }
        for (Section section : page.sections()) {
            appendSection(sb, section);
        }
        if (!page.faqs().isEmpty()) {
            appendFaq(sb, page.faqs());
        }
        if ("/lxmusic".equals(page.path())) {
            sb.append("<p class=\"actions\">")
                    .append("<a class=\"cta\" href=\"").append(esc(base + "/playlists?import=lxmc"))
                    .append("\">导入落雪歌单 Import LXMC</a> ")
                    .append("<a href=\"").append(esc(base + "/playlists")).append("\">打开我的歌单</a></p>\n");
        } else {
            sb.append("<p class=\"actions\">")
                    .append("<a class=\"cta\" href=\"").append(esc(base + "/download")).append("\">下载客户端 Download</a> ")
                    .append("<a href=\"").append(esc(base + "/")).append("\">打开 Web 播放器</a></p>\n");
        }
        sb.append("</main>\n");
        sb.append("<footer>\n");
        sb.append("<p>© ").append(Year.now().getValue()).append(" ").append(SITE_NAME_ZH)
                .append(" (").append(SITE_NAME_EN).append(")")
                .append(" · 免费开源在线音乐 / Free open-source online music</p>\n");
        sb.append("<p>").append(link(base + "/about", "关于 About")).append(" · ")
                .append(link(base + "/lxmusic", "落雪导入 LX Music")).append(" · ")
                .append(link(base + "/privacy", "隐私政策 Privacy")).append(" · ")
                .append(link(base + "/download", "下载 Download")).append(" · ")
                .append("<a href=\"").append(REPO).append("\" rel=\"noopener noreferrer\">GitHub</a> · ")
                .append("<a href=\"").append(LICENSE_URL).append("\" rel=\"noopener noreferrer\">AGPL-3.0</a></p>\n");
        sb.append("<p><a href=\"/llms.txt\">llms.txt</a> · <a href=\"/sitemap.xml\">sitemap.xml</a></p>\n");
        sb.append("</footer>\n</body>\n</html>\n");
        return sb.toString();
    }

    private static void appendSection(StringBuilder sb, Section section) {
        sb.append("<section>\n<h2>").append(esc(section.heading())).append("</h2>\n");
        for (String p : section.paragraphs()) {
            sb.append("<p>").append(esc(p)).append("</p>\n");
        }
        if (!section.bullets().isEmpty()) {
            sb.append("<ul>\n");
            for (String b : section.bullets()) {
                // 列表项允许承载已转义的内联链接，因此这里不再二次转义
                sb.append("<li>").append(b).append("</li>\n");
            }
            sb.append("</ul>\n");
        }
        sb.append("</section>\n");
    }

    private static void appendFaq(StringBuilder sb, List<Faq> faqs) {
        sb.append("<section>\n<h2>常见问题 FAQ</h2>\n");
        for (Faq faq : faqs) {
            sb.append("<article>\n<h3>").append(esc(faq.question())).append("</h3>\n")
                    .append("<p>").append(esc(faq.answer())).append("</p>\n</article>\n");
        }
        sb.append("</section>\n");
    }

    private static String link(String href, String label) {
        return "<a href=\"" + esc(href) + "\">" + esc(label) + "</a>";
    }

    private static String esc(String value) {
        return HtmlEscaper.escape(value);
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isBlank()) {
            return "/";
        }
        String path = raw.trim();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String trimTrailingSlash(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static final String STYLE = """
    <style>
        .skip-link{position:absolute;left:-9999px;top:0;z-index:999;padding:8px 16px;background:#0d3b44;color:#eaf8fb}
        .skip-link:focus{left:8px;top:8px}
        body{font-family:system-ui,-apple-system,'PingFang SC','Microsoft YaHei',sans-serif;line-height:1.7;max-width:52rem;margin:0 auto;padding:1.25rem;color:#1c2b2f}
        header nav,footer nav{display:flex;flex-wrap:wrap;gap:.5rem;margin-bottom:1rem;font-size:.95rem}
        header nav a,footer a{color:#186d7d}
        h1{font-size:1.75rem;margin:.25rem 0 .5rem}
        h2{font-size:1.2rem;margin:1.5rem 0 .5rem}
        h3{font-size:1rem;margin:.75rem 0 .25rem}
        .lede{color:#41595f;font-size:1.05rem}
        section{margin:1.25rem 0;padding:1rem 1.125rem;border:1px solid #dbe8ea;border-radius:12px}
        ul{padding-left:1.25rem}
        li{margin:.3rem 0}
        article{background:#f4fafb;border-radius:10px;padding:.75rem 1rem;margin:.5rem 0}
        article h3{margin-top:0}
        .actions{margin:1.75rem 0}
        .cta{display:inline-block;padding:.6rem 1.1rem;background:#0d3b44;color:#fff;border-radius:10px;text-decoration:none;font-weight:600}
        footer{margin-top:2rem;padding-top:1rem;border-top:1px solid #dbe8ea;color:#5a7378;font-size:.9rem}
    </style>
""";

    private StaticPageSeoRenderer() {
    }

    public static StaticPageSeoRenderer create() {
        return new StaticPageSeoRenderer();
    }
}
