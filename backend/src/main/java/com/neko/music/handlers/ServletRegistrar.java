package com.neko.music.handlers;

import com.neko.music.config.ConfigManager;
import com.neko.music.util.ClientReleaseStorage;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHolder;

/**
 * ServletRegistrar —— 集中注册所有 HTTP 路由。
 *
 * <p>原先这段近 270 行的注册代码直接堆在 {@code Main.main()} 里，
 * 让入口方法既做「服务初始化」又做「路由装配」。抽到这里后，
 * Main 只负责编排，路由表独立可读。注册顺序（更具体的路径先注册）
 * 与迁移前完全一致。
 */
public final class ServletRegistrar {

    private ServletRegistrar() {
    }

    /** 把前端静态资源与全部 API/页面 Servlet 注册到 context。 */
    public static void register(ServletContextHandler context, ConfigManager configManager) {
        // 前端静态资源与 Vue History 路由回退；更具体的 API/页面映射会优先匹配。
        ServletHolder siteResourceHolder = new ServletHolder(new SiteResourceHandler());
        context.addServlet(siteResourceHolder, "/");

        // NekoAuth 统一登录：浏览器跳转端点，不经过 /api 门禁。
        // /sso 是 NekoMusic 自有命名空间；旧 /oauth/nekocore/* 入口仅作兼容别名。
        ServletHolder nekoAuthSsoStartHolder = new ServletHolder(new NekoAuthOAuthHandler());
        context.addServlet(nekoAuthSsoStartHolder, "/sso/nekocore/start");
        ServletHolder nekoAuthSsoCallbackHolder = new ServletHolder(new NekoAuthOAuthHandler());
        context.addServlet(nekoAuthSsoCallbackHolder, "/sso/nekocore/callback");
        ServletHolder nekoAuthSsoForgotPasswordHolder = new ServletHolder(new NekoAuthOAuthHandler());
        context.addServlet(nekoAuthSsoForgotPasswordHolder, "/sso/nekocore/forgot-password");

        // 旧入口保留，避免已打开的旧页面或书签失效；新前端不再使用这些路径。
        ServletHolder nekoAuthStartHolder = new ServletHolder(new NekoAuthOAuthHandler());
        context.addServlet(nekoAuthStartHolder, "/oauth/nekocore/start");
        ServletHolder nekoAuthCallbackHolder = new ServletHolder(new NekoAuthOAuthHandler());
        context.addServlet(nekoAuthCallbackHolder, "/oauth/nekocore/callback");
        ServletHolder nekoAuthForgotPasswordHolder = new ServletHolder(new NekoAuthOAuthHandler());
        context.addServlet(nekoAuthForgotPasswordHolder, "/oauth/nekocore/forgot-password");
        ServletHolder nekoAuthSsoClaimHolder = new ServletHolder(new NekoAuthSsoClaimHandler());
        context.addServlet(nekoAuthSsoClaimHolder, "/api/user/sso/claim");
        ServletHolder nekoAuthSsoCookieLoginHolder =
                new ServletHolder(new NekoAuthSsoCookieLoginHandler());
        context.addServlet(nekoAuthSsoCookieLoginHolder, "/api/user/sso/cookie-login");
        ServletHolder nekoAuthSsoAuthorizeHolder = new ServletHolder(new NekoAuthSsoStartHandler());
        context.addServlet(nekoAuthSsoAuthorizeHolder, "/api/user/sso/start");
        ServletHolder nekoAuthSsoLoginEntryHolder = new ServletHolder(new NekoAuthSsoStartHandler());
        context.addServlet(nekoAuthSsoLoginEntryHolder, "/api/user/sso/login-entry");
        ServletHolder nekoAuthSsoPasswordResetHolder = new ServletHolder(new NekoAuthSsoStartHandler());
        context.addServlet(nekoAuthSsoPasswordResetHolder, "/api/user/sso/password-reset-url");
        ServletHolder nekoAuthPortalHolder = new ServletHolder(new NekoAuthPortalHandler());
        context.addServlet(nekoAuthPortalHolder, "/api/user/sso/portal-url");
        
        // 注册搜索音乐API处理器
        ServletHolder searchHolder = new ServletHolder(new MusicSearchHandler());
        context.addServlet(searchHolder, "/api/music/search");

        ServletHolder recognitionHolder = new ServletHolder(new MusicRecognitionHandler());
        jakarta.servlet.MultipartConfigElement recognitionMultipartConfig = new jakarta.servlet.MultipartConfigElement(
                System.getProperty("java.io.tmpdir"),
                configManager.getMusicRecognitionMaxUploadBytes(),
                configManager.getMusicRecognitionMaxUploadBytes() + 1024L * 1024L,
                64 * 1024);
        recognitionHolder.getRegistration().setMultipartConfig(recognitionMultipartConfig);
        context.addServlet(recognitionHolder, "/api/music/recognize");
        
        // 注册音乐管理API处理器 - 使用单一路径处理所有音乐管理请求
        ServletHolder musicManagementHolder = new ServletHolder(new MusicManagementHandler());
        context.addServlet(musicManagementHolder, "/api/music/*");         // 处理所有音乐管理请求
        
        // 注册管理员登录API处理器
        ServletHolder adminLoginHolder = new ServletHolder(new AdminLoginHandler());
        context.addServlet(adminLoginHolder, "/api/admin/login");

        // 注册管理员登出API处理器（服务端吊销 admin_token）
        ServletHolder adminLogoutHolder = new ServletHolder(new AdminLogoutHandler());
        context.addServlet(adminLogoutHolder, "/api/admin/logout");
        
        // 注册管理员当前信息API处理器
        ServletHolder adminCurrentHolder = new ServletHolder(new AdminCurrentHandler());
        context.addServlet(adminCurrentHolder, "/api/admin/current");
        
        // 注册管理员统计API处理器
        ServletHolder adminStatsHolder = new ServletHolder(new AdminStatsHandler());
        context.addServlet(adminStatsHolder, "/api/admin/stats");

        // 注册管理员用户管理API处理器
        ServletHolder adminUserManagementHolder = new ServletHolder(new AdminUserManagementHandler());
        context.addServlet(adminUserManagementHolder, "/api/admin/users/*");

        // 注册管理员创建API处理器
        ServletHolder adminCreateHolder = new ServletHolder(new AdminCreateHandler());
        context.addServlet(adminCreateHolder, "/api/admin/create");

        // 注册管理员创建普通用户API处理器
        ServletHolder adminCreateUserHolder = new ServletHolder(new AdminCreateUserHandler());
        context.addServlet(adminCreateUserHolder, "/api/admin/create-user");
        
        // 注册图表数据API处理器
        ServletHolder chartDataHolder = new ServletHolder(new ChartDataHandler());
        context.addServlet(chartDataHolder, "/api/admin/chart-data");
        
        // 注册文件上传API处理器
        ServletHolder fileUploadHolder = new ServletHolder(new FileUploadHandler());
        jakarta.servlet.MultipartConfigElement multipartConfig = new jakarta.servlet.MultipartConfigElement(System.getProperty("java.io.tmpdir"));
        fileUploadHolder.getRegistration().setMultipartConfig(multipartConfig);
        context.addServlet(fileUploadHolder, "/api/music/upload");
        
        // 注册用户上传API处理器
        ServletHolder userUploadHolder = new ServletHolder(new UserUploadHandler());
        jakarta.servlet.MultipartConfigElement userUploadMultipartConfig = new jakarta.servlet.MultipartConfigElement(System.getProperty("java.io.tmpdir"));
        userUploadHolder.getRegistration().setMultipartConfig(userUploadMultipartConfig);
        context.addServlet(userUploadHolder, "/api/user/upload");
        
        // 注册管理员审核API处理器
        ServletHolder adminUploadAuditHolder = new ServletHolder(new AdminUploadAuditHandler());
        context.addServlet(adminUploadAuditHolder, "/api/admin/audit/*");

        // 注册管理员歌词文件管理API处理器
        ServletHolder adminLyricsFileHolder = new ServletHolder(new AdminLyricsFileHandler());
        context.addServlet(adminLyricsFileHolder, "/api/admin/lyrics-files/*");
        
        // 注册用户上传预览API处理器
        ServletHolder userUploadPreviewHolder = new ServletHolder(new UserUploadPreviewHandler());
        context.addServlet(userUploadPreviewHolder, "/api/user/upload/preview");
        
        // 注册获取用户上传审核通过的音乐API处理器
        ServletHolder getUserUploadedMusicHolder = new ServletHolder(new GetUserUploadedMusicHandler());
        context.addServlet(getUserUploadedMusicHolder, "/api/user/uploaded-music");
        
        // 注册音乐封面API处理器
        ServletHolder musicCoverHolder = new ServletHolder(new MusicCoverHandler());
        context.addServlet(musicCoverHolder, "/api/music/cover/*");
        
        // 注册音乐信息API处理器（无需管理员权限）
        ServletHolder musicInfoHolder = new ServletHolder(new MusicInfoHandler());
        context.addServlet(musicInfoHolder, "/api/music/info/*");

        // /detail/{id} 服务端 HTML（SEO：curl 无 JS 可读 meta）
        ServletHolder musicDetailPageHolder = new ServletHolder(new MusicDetailPageHandler());
        context.addServlet(musicDetailPageHolder, "/detail/*");

        // /playlist/{id} 服务端 HTML（SEO：歌单标题、简介、曲目列表与 JSON-LD）
        ServletHolder playlistDetailPageHolder = new ServletHolder(new PlaylistDetailPageHandler());
        context.addServlet(playlistDetailPageHolder, "/playlist/*");

        // sitemap 索引 + 分类子图：单图不超过协议 50,000 URL 上限
        context.addServlet(new ServletHolder(new SitemapHandler()), SitemapHandler.INDEX_PATH);
        context.addServlet(new ServletHolder(new SitemapHandler()), SitemapHandler.STATIC_PATH);
        context.addServlet(new ServletHolder(new SitemapHandler()), SitemapHandler.MUSIC_PATH);
        context.addServlet(new ServletHolder(new SitemapHandler()), SitemapHandler.PLAYLISTS_PATH);

        ServletHolder versionHolder = new ServletHolder(new VersionJsonHandler());
        context.addServlet(versionHolder, "/version");

        ServletHolder clientReleaseDownloadHolder = new ServletHolder(new ClientReleaseDownloadHandler());
        context.addServlet(clientReleaseDownloadHolder, "/update/*");

        ServletHolder adminClientReleaseHolder = new ServletHolder(new AdminClientReleaseHandler());
        context.addServlet(adminClientReleaseHolder, "/api/admin/releases");

        ServletHolder adminClientReleaseUploadHolder = new ServletHolder(new AdminClientReleaseUploadHandler());
        long releaseMaxBytes = ClientReleaseStorage.MAX_UPLOAD_BYTES + 1024 * 1024;
        jakarta.servlet.MultipartConfigElement releaseMultipartConfig =
                new jakarta.servlet.MultipartConfigElement(
                        System.getProperty("java.io.tmpdir"),
                        releaseMaxBytes,
                        releaseMaxBytes,
                        (int) (ClientReleaseStorage.MAX_UPLOAD_BYTES / 2)
                );
        adminClientReleaseUploadHolder.getRegistration().setMultipartConfig(releaseMultipartConfig);
        context.addServlet(adminClientReleaseUploadHolder, "/api/admin/releases/upload");
        
        // 注册音乐文件API处理器（无需管理员权限）
        ServletHolder musicFileHolder = new ServletHolder(new MusicFileHandler());
        context.addServlet(musicFileHolder, "/api/music/file/*");

        ServletHolder mediaMusicHolder = new ServletHolder(new MediaMusicHandler());
        context.addServlet(mediaMusicHolder, "/media/music/*");

        // 封面同时以公开静态路径提供：SEO 页（og:image / JSON-LD image）与链接预览需要可直接抓取、
        // 可被 CDN 缓存的图片地址，不能指向 /api（/api 对爬虫一律 403）。
        ServletHolder mediaCoverHolder = new ServletHolder(new MusicCoverHandler());
        context.addServlet(mediaCoverHolder, "/media/cover/*");
        
        // 歌曲评论：列表 / 发表 / 回复 / 删除复用同一个端点 /api/comments
        ServletHolder musicCommentHolder = new ServletHolder(new MusicCommentHandler());
        context.addServlet(musicCommentHolder, "/api/comments/*");

        ServletHolder sensitiveWordCheckHolder = new ServletHolder(new SensitiveWordCheckHandler());
        context.addServlet(sensitiveWordCheckHolder, "/api/sensitive-word/check");

        // 注册播放次数排行榜API处理器（无需管理员权限）
        ServletHolder musicRankingHolder = new ServletHolder(new MusicRankingHandler());
        context.addServlet(musicRankingHolder, "/api/music/ranking");

        // 注册最新上传音乐API处理器（无需管理员权限）
        ServletHolder latestMusicHolder = new ServletHolder(new LatestMusicHandler());
        context.addServlet(latestMusicHolder, "/api/music/latest");

        // 注册用户登录API处理器
        ServletHolder userLoginHolder = new ServletHolder(new UserLoginHandler());
        context.addServlet(userLoginHolder, "/api/user/login");
        
        // 注册扫码登录API处理器（PC 生成二维码，手机端 NekoMusic App 扫码确认）
        ServletHolder userQrLoginHolder = new ServletHolder(new UserQrLoginHandler());
        context.addServlet(userQrLoginHolder, "/api/user/qrlogin/*");

        // 注册用户注册API处理器
        ServletHolder userRegisterHolder = new ServletHolder(new UserRegisterHandler());
        context.addServlet(userRegisterHolder, "/api/user/register");

        ServletHolder sliderCaptchaHolder = new ServletHolder(new SliderCaptchaHandler());
        context.addServlet(sliderCaptchaHolder, "/api/captcha/slider");

        ServletHolder sliderCaptchaVerifyHolder = new ServletHolder(new SliderCaptchaVerifyHandler());
        context.addServlet(sliderCaptchaVerifyHolder, "/api/captcha/slider/verify");
        
        // 注册发送验证码API处理器
        ServletHolder sendVerificationHolder = new ServletHolder(new SendVerificationHandler());
        context.addServlet(sendVerificationHolder, "/api/user/send-verification");
        
        // 注册发送重置密码验证码API处理器
        ServletHolder sendResetPasswordCodeHolder = new ServletHolder(new SendResetPasswordCodeHandler());
        context.addServlet(sendResetPasswordCodeHolder, "/api/user/send-reset-code");
        
        // 注册重置密码API处理器
        ServletHolder resetPasswordHolder = new ServletHolder(new ResetPasswordHandler());
        context.addServlet(resetPasswordHolder, "/api/user/reset-password");
        
        // 注册用户头像上传API处理器（更具体的路径先注册）
        ServletHolder userAvatarUploadHolder = new ServletHolder(new UserAvatarUploadHandler());
        jakarta.servlet.MultipartConfigElement avatarMultipartConfig = new jakarta.servlet.MultipartConfigElement(System.getProperty("java.io.tmpdir"));
        userAvatarUploadHolder.getRegistration().setMultipartConfig(avatarMultipartConfig);
        context.addServlet(userAvatarUploadHolder, "/api/user/avatar/upload");
        
        // 注册用户头像API处理器
        ServletHolder userAvatarHolder = new ServletHolder(new UserAvatarHandler());
        context.addServlet(userAvatarHolder, "/api/user/avatar/*");
        
        // 注册用户修改密码API处理器
        ServletHolder userPasswordChangeHolder = new ServletHolder(new UserPasswordChangeHandler());
        context.addServlet(userPasswordChangeHolder, "/api/user/password/change");

        // 注册用户登出API处理器（服务端吊销当前 user token）
        ServletHolder userLogoutHolder = new ServletHolder(new UserLogoutHandler());
        context.addServlet(userLogoutHolder, "/api/user/logout");

        // 注册用户修改昵称API处理器
        ServletHolder userNicknameChangeHolder = new ServletHolder(new UserNicknameChangeHandler());
        context.addServlet(userNicknameChangeHolder, "/api/user/nickname/change");

        // 注册用户信息API处理器（当前登录用户资料，客户端启动时刷新昵称等）
        ServletHolder userInfoHolder = new ServletHolder(new UserInfoHandler());
        context.addServlet(userInfoHolder, "/api/user/info");
        
        // 注册用户收藏API处理器
        ServletHolder userFavoriteHolder = new ServletHolder(new UserFavoriteHandler());
        context.addServlet(userFavoriteHolder, "/api/user/favorites/*");
        
        // 注册用户收藏歌单API处理器
        ServletHolder userFavoritePlaylistHolder = new ServletHolder(new UserFavoritePlaylistHandler());
        context.addServlet(userFavoritePlaylistHolder, "/api/user/favorite-playlists/*");

        // 注册用户每日推荐API处理器
        ServletHolder userDailyRecoHolder = new ServletHolder(new UserDailyRecommendationHandler());
        context.addServlet(userDailyRecoHolder, "/api/user/recommendations/daily");
        
        // 注册用户管理API处理器（管理员权限）
        ServletHolder userManagementHolder = new ServletHolder(new UserManagementHandler());
        context.addServlet(userManagementHolder, "/api/users/*");

        ServletHolder vipPricingPublicHolder = new ServletHolder(new VipPricingPublicHandler());
        context.addServlet(vipPricingPublicHolder, "/api/vip/pricing");

        ServletHolder vipPricingAdminHolder = new ServletHolder(new VipPricingAdminHandler());
        context.addServlet(vipPricingAdminHolder, "/api/admin/vip/pricing");

        // 系统设置（管理员及以上）：/api/admin/settings
        ServletHolder adminSettingsHolder = new ServletHolder(new AdminSettingsHandler());
        context.addServlet(adminSettingsHolder, "/api/admin/settings");

        // 日志（访问日志 / 操作日志）：/api/admin/logs/*
        ServletHolder adminLogHolder = new ServletHolder(new AdminLogHandler());
        context.addServlet(adminLogHolder, "/api/admin/logs/*");

        // 网易云补全扫码登录（管理员及以上）：/api/admin/netease/*
        ServletHolder adminNeteaseLoginHolder = new ServletHolder(new AdminNeteaseLoginHandler());
        context.addServlet(adminNeteaseLoginHolder, "/api/admin/netease/*");

        // QQ 音乐补全扫码登录（管理员及以上）：/api/admin/qq/*
        ServletHolder adminQqLoginHolder = new ServletHolder(new AdminQQLoginHandler());
        context.addServlet(adminQqLoginHolder, "/api/admin/qq/*");

        ServletHolder vipPayCreateHolder = new ServletHolder(new VipPayCreateHandler());
        context.addServlet(vipPayCreateHolder, "/api/vip/pay/create");

        ServletHolder zpayNotifyHolder = new ServletHolder(new ZpayNotifyHandler());
        context.addServlet(zpayNotifyHolder, "/api/payment/zpay/notify");

        ServletHolder videoRenderHolder = new ServletHolder(new VideoRenderHandler());
        context.addServlet(videoRenderHolder, "/api/video/render/*");

        // 注册创建歌单API处理器
        ServletHolder createPlaylistHolder = new ServletHolder(new CreatePlaylistHandler());
        context.addServlet(createPlaylistHolder, "/api/user/playlist/create");

        // 注册获取歌单列表API处理器
        ServletHolder getPlaylistsHolder = new ServletHolder(new GetPlaylistsHandler());
        context.addServlet(getPlaylistsHolder, "/api/user/playlists");

        // 注册获取歌单详情API处理器（无需登录）
        ServletHolder getPlaylistDetailHolder = new ServletHolder(new GetPlaylistDetailHandler());
        context.addServlet(getPlaylistDetailHolder, "/api/playlist/*");

        // 外部音乐接口统一挂在 /loser 下：QQ 为 /loser/qq/*，网易云为 /loser/netease/*
        // QQ 音乐歌单详情代理（兼容 qq-music-api-next 的 getSongListDetail 响应）
        ServletHolder qqMusicSongListDetailHolder = new ServletHolder(new QQMusicSongListDetailHandler());
        context.addServlet(qqMusicSongListDetailHolder, "/loser/qq/getSongListDetail");

        // 酷狗音乐歌单详情代理（返回 {response:{code,listid,name,songnum,songlist[]}}）
        ServletHolder kugouMusicSongListDetailHolder = new ServletHolder(new KugouMusicSongListDetailHandler());
        context.addServlet(kugouMusicSongListDetailHolder, "/loser/kugou/getSongListDetail");

        // QQ / 网易云 / 酷狗 / 汽水歌单导入并加入指定歌单（SSE 进度），需要用户令牌
        ServletHolder neteaseImportHolder = new ServletHolder(new ExternalImportHandler());
        context.addServlet(neteaseImportHolder, "/loser/netease/pull");
        ServletHolder qqImportHolder = new ServletHolder(new ExternalImportHandler());
        context.addServlet(qqImportHolder, "/loser/qq/pull");
        ServletHolder kugouImportHolder = new ServletHolder(new ExternalImportHandler());
        context.addServlet(kugouImportHolder, "/loser/kugou/pull");
        // 汽水歌单导入保留为独立接口；登录和其它汽水页面不再注册
        ServletHolder qishuiImportHolder = new ServletHolder(new ExternalImportHandler());
        context.addServlet(qishuiImportHolder, "/loser/qishui/pull");
        ServletHolder qishuiDetailHolder = new ServletHolder(new QishuiPlaylistDetailHandler());
        context.addServlet(qishuiDetailHolder, "/loser/qishui/getSongListDetail");

        // 网易云常用只读接口（兼容 NeteaseCloudMusicApi 路径），需要用户令牌
        ServletHolder neteaseCloudMusicHolder = new ServletHolder(new NeteaseCloudMusicHandler());
        context.addServlet(neteaseCloudMusicHolder, "/loser/netease/*");

        // 注册搜索歌单API处理器（无需登录）
        ServletHolder searchPlaylistsHolder = new ServletHolder(new SearchPlaylistsHandler());
        context.addServlet(searchPlaylistsHolder, "/api/playlists/search");

        // 注册更新歌单API处理器
        ServletHolder updatePlaylistHolder = new ServletHolder(new UpdatePlaylistHandler());
        context.addServlet(updatePlaylistHolder, "/api/user/playlist/update");

        // 注册删除歌单API处理器
        ServletHolder deletePlaylistHolder = new ServletHolder(new DeletePlaylistHandler());
        context.addServlet(deletePlaylistHolder, "/api/user/playlist/delete");

        // 注册获取歌单音乐列表API处理器
        ServletHolder getPlaylistMusicHolder = new ServletHolder(new GetPlaylistMusicHandler());
        context.addServlet(getPlaylistMusicHolder, "/api/user/playlist/music/*");

        // 注册添加音乐到歌单API处理器
        ServletHolder addMusicToPlaylistHolder = new ServletHolder(new AddMusicToPlaylistHandler());
        context.addServlet(addMusicToPlaylistHolder, "/api/user/playlist/music/add");

        // 注册从歌单中移除音乐API处理器
        ServletHolder removeMusicFromPlaylistHolder = new ServletHolder(new RemoveMusicFromPlaylistHandler());
        context.addServlet(removeMusicFromPlaylistHolder, "/api/user/playlist/music/remove");
        
        // 注册搜索歌手API处理器
        ServletHolder searchArtistsHolder = new ServletHolder(new SearchArtistsHandler());
        context.addServlet(searchArtistsHolder, "/api/artists/search");

        // 注册站内消息API处理器（列表 / 未读数 / 标记已读）
        ServletHolder userNotificationHolder = new ServletHolder(new UserNotificationHandler());
        context.addServlet(userNotificationHolder, "/api/user/notifications/*");
        
    }
}
