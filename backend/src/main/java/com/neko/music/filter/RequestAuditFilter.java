package com.neko.music.filter;

import com.neko.music.Main;
import com.neko.music.config.ConfigManager;
import com.neko.music.model.Admin;
import com.neko.music.model.AccessLogRecord;
import com.neko.music.model.OperationLogRecord;
import com.neko.music.model.ThreatAssessment;
import com.neko.music.security.ThreatDetectionService;
import com.neko.music.service.AccessLogService;
import com.neko.music.service.AccessLogUserResolver;
import com.neko.music.service.OperationLogService;
import com.neko.music.service.UserAuthService;
import com.neko.music.util.ClientIpResolver;
import com.neko.music.util.OperationAudit;
import com.neko.music.util.RequestAuthUtil;
import com.neko.music.util.RequestCategory;
import com.neko.music.util.RequestPath;
import com.neko.music.util.RequestScope;
import com.neko.music.util.SeoAudit;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/**
 * 访问日志 + 操作日志过滤器。
 *
 * <p>必须注册在过滤器链**最外层**（{@code Main} 里第一个 {@code addFilter}）：这样它能观察到
 * 经过后面所有门禁（限流 / 封禁）之后真实的响应状态码与总耗时——被 403、409、429
 * 挡下的请求同样要留痕，否则「谁在试探」这部分恰好缺失。</p>
 *
 * <ul>
 *   <li><b>访问日志</b>：每个请求一条，落 `access_log`（`logging.access_enabled` 控制），
 *       带已登录用户身份（`user_id` / `user_name`）与管理员身份（`admin_id` / `admin_username`），
 *       被 403 / 409 / 429 挡下的请求同样留痕。</li>
 *   <li><b>操作日志</b>：`/api/admin/*` 上的写操作（POST/PUT/PATCH/DELETE）各一条，落
 *       `operation_log`（`logging.operation_enabled` 控制）。</li>
 * </ul>
 *
 * <p>两者都只做一次入队（异步批量落库），不参与请求关键路径；日志库不可用时为 no-op。</p>
 */
public class RequestAuditFilter implements Filter {

    private static final Logger logger = LoggerFactory.getLogger(RequestAuditFilter.class);

    /** 产生「操作日志」的方法：只有会改变服务端状态的方法才算操作。 */
    private static final List<String> MUTATING_METHODS = List.of("POST", "PUT", "PATCH", "DELETE");

    /**
     * 查询串里这些参数的值不落库。
     *
     * <p>浏览器 {@code EventSource} 无法带请求头，SSE 类接口只能把令牌放在查询串里；照原样写进日志
     * 等于把一次性凭证长期留在日志库（一个导出、一次错配的备份就泄露了）。因此只脱敏「值」，
     * 参数名与其余查询串保持原样，日志的可检索性不受影响。</p>
     */
    private static final Set<String> SENSITIVE_QUERY_PARAMS = Set.of(
            "token", "access_token", "authorization", "password", "passwd", "secret", "code");

    /**
     * 一条语义化操作规则：命中「最长路径前缀 + 方法」的一边，决定操作日志里写什么模块与动作。
     *
     * @param pathPrefix 路径前缀
     * @param methods    适用的 HTTP 方法（大写）
     * @param module     业务模块，如「歌词管理」
     * @param action     动作，如「修改歌词」
     */
    record OperationRule(String pathPrefix, Set<String> methods, String module, String action) {
    }

    /**
     * 语义化操作规则表：把「接口做了什么」写成人看得懂的短语（修改系统设置 / 审核通过 / 上传音乐 /
     * 修改歌词……），而不是一律记成「提交 / 更新 / 删除」。
     *
     * <p>规则只决定「怎么写」，不决定「要不要记」：后台写操作即使没有命中规则也会留痕，动作退化为
     * 方法名对应的通用说法。反过来要注意，管理员的音乐上传 / 修改 / 删除挂在 {@code /api/music/*}
     * 而不是 {@code /api/admin/*}，不在这里显式登记就会整类漏记。</p>
     */
    /**
     * 与管理员音乐接口共用 {@code /api/music} 前缀、但不属于后台操作的路径。
     *
     * <p>前缀规则分不出「管理员的音乐增删改」和「对公众开放的写接口」，必须显式排除：听歌识曲
     * {@code /api/music/recognize} 就是 POST，被前缀规则吞掉就会在操作日志里记成「新增音乐」。
     * 搜索是 GET-only，一并排除，免得错误方法在日志里变成一次并不存在的操作。</p>
     */
    private static final Set<String> NON_OPERATION_PATHS = Set.of(
            "/api/music/recognize",
            "/api/music/search");

    private static final List<OperationRule> OPERATION_RULES = List.of(
            // 系统设置
            new OperationRule("/api/admin/settings", Set.of("PUT"), "系统设置", "修改系统设置"),
            // 审核（用户上传的音乐）
            new OperationRule("/api/admin/audit/approve", Set.of("POST"), "审核管理", "审核通过"),
            new OperationRule("/api/admin/audit/reject", Set.of("POST"), "审核管理", "审核驳回"),
            // 歌词
            new OperationRule("/api/admin/lyrics-files", Set.of("PUT"), "歌词管理", "修改歌词"),
            new OperationRule("/api/admin/lyrics-files", Set.of("DELETE"), "歌词管理", "删除歌词"),
            new OperationRule("/api/admin/lyrics-reports", Set.of("POST"), "歌词报错", "审核歌词报错"),
            // 音乐：/api/music 同时承载公开接口，这里只登记写方法；听歌识曲 /api/music/recognize 不在其列
            new OperationRule("/api/music/upload", Set.of("POST"), "音乐管理", "上传音乐"),
            new OperationRule("/api/music/upload", Set.of("PUT"), "音乐管理", "修改音乐"),
            new OperationRule("/api/music", Set.of("POST"), "音乐管理", "新增音乐"),
            new OperationRule("/api/music", Set.of("PUT"), "音乐管理", "修改音乐"),
            new OperationRule("/api/music", Set.of("DELETE"), "音乐管理", "删除音乐"),
            // 管理员账号
            new OperationRule("/api/admin/create", Set.of("POST"), "管理员管理", "创建管理员账号"),
            // 客户端更新
            new OperationRule("/api/admin/releases/upload", Set.of("POST"), "客户端更新", "上传客户端安装包"),
            new OperationRule("/api/admin/releases", Set.of("PUT"), "客户端更新", "更新客户端版本号"),
            // VIP 价目
            new OperationRule("/api/admin/vip/pricing", Set.of("PUT"), "VIP 定价", "修改 VIP 价目"),
            // 外站补全扫码登录
            new OperationRule("/api/admin/netease/qr/key", Set.of("POST"), "外站补全", "申请网易云扫码登录"),
            new OperationRule("/api/admin/netease/logout", Set.of("POST"), "外站补全", "退出网易云登录"),
            new OperationRule("/api/admin/qq/qr/key", Set.of("POST"), "外站补全", "申请 QQ 音乐扫码登录"),
            new OperationRule("/api/admin/qq/logout", Set.of("POST"), "外站补全", "退出 QQ 音乐登录"),
            // 管理员会话
            new OperationRule("/api/admin/login", Set.of("POST"), "管理员登录", "登录"),
            new OperationRule("/api/admin/logout", Set.of("POST"), "管理员登录", "登出"));

    /** 模块兜底表：没有命中操作规则的后台路径按它归类（只用于模块名）。 */
    private static final List<String[]> ADMIN_MODULES = List.of(
            new String[] { "/api/admin/lyrics-files", "歌词管理" },
            new String[] { "/api/admin/lyrics-reports", "歌词报错" },
            new String[] { "/api/admin/settings", "系统设置" },
            new String[] { "/api/admin/releases", "客户端更新" },
            new String[] { "/api/admin/netease", "外站补全" },
            new String[] { "/api/admin/qq", "外站补全" },
            new String[] { "/api/admin/vip/pricing", "VIP 定价" },
            new String[] { "/api/admin/music", "音乐管理" },
            new String[] { "/api/admin/audit", "审核管理" },
            new String[] { "/api/admin/create", "管理员管理" },
            new String[] { "/api/admin/login", "管理员登录" },
            new String[] { "/api/admin/logout", "管理员登录" },
            new String[] { "/api/admin/current", "管理员会话" },
            new String[] { "/api/admin/stats", "数据统计" },
            new String[] { "/api/admin/chart-data", "数据统计" },
            new String[] { "/api/music", "音乐管理" });

    private ConfigManager configManager;

    @Override
    public void init(FilterConfig filterConfig) {
        this.configManager = (ConfigManager) filterConfig.getServletContext().getAttribute("configManager");
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!(request instanceof HttpServletRequest httpRequest)
                || !(response instanceof HttpServletResponse httpResponse)
                || httpRequest.getDispatcherType() != DispatcherType.REQUEST) {
            chain.doFilter(request, response);
            return;
        }

        long startedAt = System.currentTimeMillis();
        long startedAtNanos = System.nanoTime();
        // 单请求作用域：路径 / 客户端 IP / UA 判定 / 令牌解析在整条过滤器链里只算一次。
        // 必须在这里开始（本过滤器是链上最外层），并在 finally 里结束——Jetty 线程会复用。
        RequestScope.begin();
        try {
            chain.doFilter(request, response);
        } finally {
            try {
                // 耗时改用纳秒计时：currentTimeMillis 只有毫秒精度，亚毫秒的请求会被记成 0ms。
                capture(httpRequest, httpResponse, startedAt, System.nanoTime() - startedAtNanos);
            } catch (Exception e) {
                // 日志失败绝不能影响响应已经写出的内容
                logger.debug("记录请求日志失败: {}", e.getMessage());
            } finally {
                RequestScope.end();
            }
        }
    }

    private void capture(HttpServletRequest request, HttpServletResponse response,
                         long startedAt, long elapsedNanos) {
        boolean accessEnabled = configManager == null || configManager.isLoggingAccessEnabled();
        boolean operationEnabled = configManager == null || configManager.isLoggingOperationEnabled();

        String path = RequestPath.of(request);
        String category = RequestCategory.of(path);
        int status = response.getStatus();
        long durationUs = Math.max(0L, elapsedNanos / 1000L);
        String clientIp = resolveClientIp(request);
        String userAgent = request.getHeader("User-Agent");
        String token = RequestAuthUtil.extractToken(request);
        boolean authenticated = token != null;
        // 威胁判定也使用已登录用户身份：CGNAT 下同一公网 IP 可能承载大量真实用户，
        // 只有把用户 / 安装实例纳入来源键，才能避免按裸 IP 把邻居一起判定。
        Integer userId = authenticated ? resolveUserId(token) : null;
        ThreatAssessment threat = assessThreat(request, path, category, status, clientIp,
                userAgent, userId);
        logThreat(request, path, status, clientIp, userAgent, threat);

        // 威胁判定独立于日志库开关：即使访问日志关闭，应用日志仍应能留下攻击痕迹。
        if (!accessEnabled && !operationEnabled) {
            return;
        }
        boolean isAdminPath = RequestCategory.ADMIN.equals(category);
        String method = request.getMethod();
        boolean mutating = MUTATING_METHODS.contains(method.toUpperCase(java.util.Locale.ROOT));
        OperationRule rule = mutating ? resolveRule(path, method) : null;
        // 后台写操作一律留痕（将来新增的后台接口不必再改这里）；后台之外的路径只有登记了规则才算
        // 「操作」——管理员的音乐上传 / 修改 / 删除挂在 /api/music/*，正是靠规则表纳进来的。
        boolean logOperation = operationEnabled && mutating && (isAdminPath || rule != null);

        boolean adminContext = isAdminPath || rule != null;
        Admin admin = (adminContext && (logOperation || accessEnabled)) ? resolveAdmin(token) : null;

        // 管理员另有 admin_* 两列；普通用户在这里落 user_id / user_name，两者互不覆盖。
        String userName = accessEnabled && userId != null
                ? AccessLogUserResolver.nicknameOf(userId) : null;

        if (accessEnabled) {
            AccessLogService.record(buildAccessRecord(request, response, path, category, method, status,
                    startedAt, durationUs, authenticated, userId, userName, admin, threat));
        }
        if (logOperation) {
            OperationLogService.record(buildOperationRecord(request, path, method, status, startedAt,
                    durationUs, admin, resolveClientIp(request), rule));
        }
    }

    private AccessLogRecord buildAccessRecord(HttpServletRequest request, HttpServletResponse response,
                                              String path, String category, String method, int status,
                                              long occurredAt, long durationUs,
                                              boolean authenticated, Integer userId, String userName,
                                              Admin admin, ThreatAssessment threat) {
        String userAgent = request.getHeader("User-Agent");
        String clientIp = resolveClientIp(request);
        return new AccessLogRecord(
                occurredAt,
                method,
                path,
                redactQuery(request.getQueryString()),
                category,
                status,
                durationUs,
                responseBytes(response),
                clientIp,
                ipSubnet(clientIp),
                userAgent,
                request.getHeader("Referer"),
                request.getHeader("Origin"),
                request.getHeader("Accept-Language"),
                authenticated,
                userId,
                userName,
                admin == null ? null : admin.id(),
                admin == null ? null : admin.username(),
                request.getHeader("Host"),
                request.getScheme(),
                request.getProtocol(),
                Thread.currentThread().getName(),
                // 同一 URL 的两种表示（SEO HTML / SPA 壳）由渲染方挂在请求属性上（见 SeoAudit）
                SeoAudit.seoRender(request),
                threat.level(),
                threat.score(),
                threat.reasons(),
                threat.engineVersion());
    }

    private static ThreatAssessment assessThreat(HttpServletRequest request, String path, String category,
                                                  int status, String clientIp, String userAgent,
                                                  Integer userId) {
        try {
            return ThreatDetectionService.shared().assess(new ThreatDetectionService.RequestFacts(
                    System.currentTimeMillis(), request.getMethod(), path, request.getQueryString(),
                    category, status, clientIp, userAgent, userId));
        } catch (Exception e) {
            logger.debug("威胁判定失败，按正常处理: {} {}", request.getMethod(), path);
            return ThreatAssessment.normal();
        }
    }

    /** 应用日志只在有风险时输出：正常请求由访问日志表的 threat_level 标记，避免刷屏。 */
    private static void logThreat(HttpServletRequest request, String path, int status,
                                  String clientIp, String userAgent,
                                  ThreatAssessment threat) {
        String level = threat.level();
        if (ThreatAssessment.NORMAL.equals(level)) {
            if (logger.isDebugEnabled()) {
                logger.debug("[安全审计][正常] method={} path={} status={} ip={} score={}",
                        request.getMethod(), safeLog(path, 300), status, clientIp, threat.score());
            }
            return;
        }
        String message = "[安全审计][" + (threat.isAttack() ? "攻击" : "疑似攻击") + "]"
                + " method={} path={} status={} ip={} score={} ua={} reasons={}";
        Object[] args = {request.getMethod(), safeLog(path, 300), status, clientIp,
                threat.score(), safeLog(userAgent, 160), safeLog(threat.reasons(), 400)};
        if (threat.isAttack()) {
            logger.warn(message, args);
        } else {
            logger.info(message, args);
        }
    }

    /** 日志字段去控制字符，避免 UA / 路径里的换行伪造日志行（CWE-117）。 */
    private static String safeLog(String value, int max) {
        if (value == null || value.isBlank()) {
            return "-";
        }
        StringBuilder out = new StringBuilder(Math.min(value.length(), max));
        for (int i = 0; i < value.length() && out.length() < max; i++) {
            char c = value.charAt(i);
            out.append(Character.isISOControl(c) ? '_' : c);
        }
        return out.toString();
    }

    private OperationLogRecord buildOperationRecord(HttpServletRequest request, String path, String method,
                                                    int status, long occurredAt, long durationUs,
                                                    Admin admin, String clientIp, OperationRule rule) {
        String userAgent = request.getHeader("User-Agent");
        return new OperationLogRecord(
                occurredAt,
                admin == null ? null : admin.id(),
                admin == null ? null : admin.username(),
                admin == null ? null : admin.role(),
                moduleFor(path, rule),
                actionFor(path, method, rule),
                method,
                path,
                redactQuery(request.getQueryString()),
                status,
                status >= 200 && status < 400,
                durationUs,
                clientIp,
                userAgent,
                operationDetail(OperationAudit.changes(request), path));
    }

    /** IPv4 取 /24，IPv6 取前 3 段 /48；无法判定时返回 null。 */
    private static String ipSubnet(String ip) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        if (ip.indexOf(':') >= 0) {
            String[] parts = ip.split(":");
            StringBuilder builder = new StringBuilder();
            int taken = 0;
            for (String part : parts) {
                if (part.isEmpty() || taken == 3) {
                    break;
                }
                if (taken > 0) {
                    builder.append(':');
                }
                builder.append(part);
                taken++;
            }
            return taken == 3 ? builder + "::/48" : null;
        }
        String[] octets = ip.split("\\.");
        return octets.length == 4
                ? octets[0] + "." + octets[1] + "." + octets[2] + ".0/24"
                : null;
    }

    /** 查询串脱敏：把敏感参数的值换成 {@code ***}，其余按原样（含分隔符）保留。 */
    static String redactQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) {
            return rawQuery;
        }
        String[] pairs = rawQuery.split("&", -1);
        StringBuilder out = new StringBuilder(rawQuery.length());
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                out.append('&');
            }
            out.append(redactPair(pairs[i]));
        }
        return out.toString();
    }

    private static String redactPair(String pair) {
        int equals = pair.indexOf('=');
        if (equals < 0) {
            return pair;
        }
        String name = pair.substring(0, equals);
        return SENSITIVE_QUERY_PARAMS.contains(name.trim().toLowerCase(java.util.Locale.ROOT))
                ? name + "=***"
                : pair;
    }

    /** 命中「最长路径前缀 + 方法」的操作规则；没有登记规则的路径返回 null。 */
    static OperationRule resolveRule(String path, String method) {
        if (path == null || NON_OPERATION_PATHS.contains(path)) {
            return null;
        }
        String upper = method == null ? "" : method.toUpperCase(java.util.Locale.ROOT);
        OperationRule best = null;
        for (OperationRule rule : OPERATION_RULES) {
            if (!rule.methods().contains(upper) || !path.startsWith(rule.pathPrefix())) {
                continue;
            }
            if (best == null || rule.pathPrefix().length() > best.pathPrefix().length()) {
                best = rule;
            }
        }
        return best;
    }

    /** 业务模块：优先用操作规则，其次按兜底表的最长前缀归类。 */
    static String moduleFor(String path, OperationRule rule) {
        return rule != null ? rule.module() : fallbackModule(path);
    }

    static String fallbackModule(String path) {
        if (path == null) {
            return "管理后台";
        }
        String[] best = null;
        for (String[] entry : ADMIN_MODULES) {
            if (path.startsWith(entry[0]) && (best == null || entry[0].length() > best[0].length())) {
                best = entry;
            }
        }
        return best == null ? "管理后台" : best[1];
    }

    /** 动作：优先用操作规则，其余按 HTTP 方法归纳。 */
    static String actionFor(String path, String method, OperationRule rule) {
        if (rule != null) {
            return rule.action();
        }
        return switch (method == null ? "" : method.toUpperCase(java.util.Locale.ROOT)) {
            case "POST" -> "新增";
            case "PUT" -> "更新";
            case "PATCH" -> "更新";
            case "DELETE" -> "删除";
            default -> method;
        };
    }

    /**
     * 操作说明：优先用处理器挂上来的变更明细（「标签：原值 → 新值」，见 {@link OperationAudit}），
     * 没有明细时退回路径里的目标编号（{@code #148}）。
     *
     * <p>明细为空是常态——读取类接口、以及没有新旧值可比的删除类操作，路径里的编号就是最有用的一句
     * 话；明细存在时它更具体（改的是哪个字段、从什么变成什么），所以优先。</p>
     */
    static String operationDetail(List<OperationAudit.Change> changes, String path) {
        String detail = OperationAudit.describe(changes);
        return detail == null || detail.isBlank() ? targetDetail(path) : detail;
    }

    /**
     * 操作目标：取路径里最后一段「纯数字」的片段（{@code /api/music/148}、
     * {@code /api/admin/audit/approve/37} 都能取到），
     * 让日志能看出改的是哪一条记录；取不到返回 null。
     */
    static String targetDetail(String path) {
        if (path == null) {
            return null;
        }
        String[] segments = path.split("/");
        for (int i = segments.length - 1; i >= 0; i--) {
            String segment = segments[i];
            if (segment.isEmpty()) {
                continue;
            }
            int dot = segment.indexOf('.');
            String candidate = dot > 0 ? segment.substring(0, dot) : segment;
            if (!candidate.isEmpty() && candidate.chars().allMatch(Character::isDigit)) {
                return "#" + candidate;
            }
        }
        return null;
    }

    /**
     * 已登录用户 ID：解析令牌对应的用户，解析不出（未登录 / 令牌失效 / Redis 不可用）返回 null。
     *
     * <p>入参是 {@link RequestAuthUtil#extractToken} 归一化后的令牌，因此裸 token、{@code Bearer}
     * 前缀、{@code ?token=} 三种形态都能解析。用只读入口 {@link UserAuthService#findUserIdByToken}，
     * 不走认证路径的 {@code validateToken}：后者在令牌临近过期时会顺手续期，日志组件不该有
     * 「记一条日志 = 会话延长」这种副作用。</p>
     */
    private static Integer resolveUserId(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            UserAuthService authService = Main.getUserAuthService();
            if (authService == null) {
                return null;
            }
            return authService.findUserIdByToken(token).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static Admin resolveAdmin(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            if (Main.getAdminAuthService() == null) {
                return null;
            }
            return Main.getAdminAuthService().getAdminByToken(token);
        } catch (Exception e) {
            return null;
        }
    }

    private static String resolveClientIp(HttpServletRequest request) {
        try {
            return ClientIpResolver.clientIp(request);
        } catch (Exception e) {
            return "";
        }
    }

    /** 响应体大小：优先取 Content-Length，取不到（chunked / 流式）记 0，不去包装响应流（会破坏 SSE 与媒体流）。 */
    private static long responseBytes(HttpServletResponse response) {
        String header = response.getHeader("Content-Length");
        if (header == null || header.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
