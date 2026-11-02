package com.neko.music.service;

import com.neko.music.Main;
import com.neko.music.model.User;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 访问日志里的「已登录用户是谁」：用户 ID → 昵称，带进程内缓存。
 *
 * <p>请求侧已经解析出本地用户 ID（{@link UserAuthService#findUserIdByToken}），但昵称的权威源在
 * NekoAuth。每个已登录请求都远程查询一次会放大 Auth 压力，因此这里做一层只读缓存：命中直接返回，
 * 未命中才解析一次，并在 TTL 内记住结果。</p>
 *
 * <p>取舍与约束：</p>
 * <ul>
 *   <li>缓存只放 **ID → 昵称**，不缓存令牌；令牌不进内存映射，避免日志组件成为凭证副本。</li>
 *   <li>昵称是**快照**：日志记下当时看到的昵称，用户之后改名不回溯改历史记录（日志本就该留住当时事实）。</li>
 *   <li>查不到（已注销 / 库不可用）也缓存一小段负结果，避免账被同一个 ID 反复打到主库。</li>
 *   <li>缓存失效只影响日志里的显示名，不影响鉴权与业务，所以失败一律降级为「只有 ID」。</li>
 * </ul>
 */
public final class AccessLogUserResolver {

    /** 命中结果缓存时长（秒）。 */
    static final int POSITIVE_TTL_SECONDS = 300;

    /** 未命中的缓存时长（秒）：比命中短，账号重建后日志能尽快显示新昵称。 */
    static final int NEGATIVE_TTL_SECONDS = 60;

    /** 缓存条目上限：超过即整体清空，防止被海量用户 ID 撑爆内存。 */
    static final int MAX_ENTRIES = 50_000;

    /** 昵称列宽上限，与 {@code access_log.user_name} 一致。 */
    private static final int MAX_NAME_LENGTH = 255;

    /** 昵称加载器：生产实现读主库，单元测试可替换。 */
    interface Loader {

        /** 返回昵称；用户不存在 / 查询失败返回 {@code null}。 */
        String nicknameOf(int userId);
    }

    private static final Map<Integer, Entry> CACHE = new ConcurrentHashMap<>();

    private static volatile Loader loader = AccessLogUserResolver::loadFromDatabase;

    private static volatile LongSupplier clock = System::currentTimeMillis;

    private AccessLogUserResolver() {
    }

    /**
     * 解析用户昵称：缓存命中即返回，否则查一次主库并记住结果。
     *
     * @return 昵称；ID 非法、用户不存在或查询失败时为 {@code null}（调用方只展示 ID）
     */
    public static String nicknameOf(Integer userId) {
        if (userId == null || userId <= 0) {
            return null;
        }
        long now = clock.getAsLong();
        Entry cached = CACHE.get(userId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.nickname();
        }
        if (CACHE.size() > MAX_ENTRIES) {
            CACHE.clear();
        }
        String nickname = truncate(loader.nicknameOf(userId));
        long ttl = (nickname == null ? NEGATIVE_TTL_SECONDS : POSITIVE_TTL_SECONDS) * 1000L;
        CACHE.put(userId, new Entry(nickname, now + ttl));
        return nickname;
    }

    /** 生产实现：按本地用户 ID 从 NekoAuth 解析；服务未就绪或查询异常都按「不知道」处理。 */
    private static String loadFromDatabase(int userId) {
        UserAuthService authService = Main.getUserAuthService();
        if (authService == null) {
            return null;
        }
        try {
            Optional<User> user = authService.findUserById(userId);
            return user.map(User::nickname).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= MAX_NAME_LENGTH ? trimmed : trimmed.substring(0, MAX_NAME_LENGTH);
    }

    /** 仅供单元测试替换加载实现；生产代码请勿调用（传 {@code null} 恢复主库实现）。 */
    static void useLoader(Loader replacement) {
        loader = replacement == null ? AccessLogUserResolver::loadFromDatabase : replacement;
        CACHE.clear();
    }

    /** 测试用：替换时钟。 */
    static void useClock(LongSupplier supplier) {
        clock = supplier == null ? System::currentTimeMillis : supplier;
    }

    /** 测试用：清空缓存。 */
    static void reset() {
        CACHE.clear();
        loader = AccessLogUserResolver::loadFromDatabase;
        clock = System::currentTimeMillis;
    }

    /** 一条缓存：昵称（可为 null，表示「查过但没有」）与到期时刻。 */
    private record Entry(String nickname, long expiresAt) {
    }
}
