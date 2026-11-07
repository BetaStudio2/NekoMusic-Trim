package com.neko.music.util;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 单次请求内的「只算一次」缓存（ThreadLocal，随请求开始 / 结束由过滤器维护）。
 *
 * <p>为什么需要：一个动态请求要穿过访问日志、语言、安全头、限流、来源封禁等
 * 七八道门禁，每道门禁都要拿同一批输入做判定——规范化路径、客户端 IP、User-Agent 分类、语言、
 * 令牌对应的用户。这些取值在一次请求内是常量，重复计算只是把 CPU 花在同样的字符串上：</p>
 * <ul>
 *   <li>{@link RequestPath#of} 被每个过滤器各调一次；</li>
 *   <li>{@link ClientIpResolver#clientIp} 会解析转发头链、做地址合法性校验（含 {@code InetAddress} 解析）；</li>
 *   <li>{@link com.neko.music.seo.UserAgentClassifier} 用十来条正则扫 UA；</li>
 *   <li>令牌 → 用户 ID 的 Redis 查询会被访问日志与处理器各做一次，处理器还要再查一次 TTL 决定是否续期。</li>
 * </ul>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>只在请求线程内可见；{@link #begin()} / {@link #end()} 必须成对（过滤器里用 {@code try/finally}），
 *       否则 Jetty 复用线程时会把上一个请求的结果串给下一个请求。</li>
 *   <li>没有活动作用域时（单测直接调工具类、定时任务线程）所有读写都是 no-op，调用方照常计算，
 *       因此不改变任何行为，只是少了这层缓存。</li>
 *   <li>缓存值只允许是「由请求本身决定」的纯函数结果，不能是随时间变化的业务数据。</li>
 * </ul>
 */
public final class RequestScope {

    /** 值可能合法地为 {@code null}（例如「这个 UA 不是爬虫」的否定结果），用哨兵区分「没算过」。 */
    private static final Object NULL = new Object();

    private static final ThreadLocal<Map<String, Object>> CURRENT = new ThreadLocal<>();

    private RequestScope() {
    }

    /** 请求进入时调用（过滤器链最外层）。 */
    public static void begin() {
        CURRENT.set(new HashMap<>(16));
    }

    /** 请求结束时调用（必须放在 {@code finally} 里）。 */
    public static void end() {
        CURRENT.remove();
    }

    /** 是否存在活动作用域；为 false 时 {@link #get} / {@link #put} 都是 no-op。 */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    /** 读取缓存值；没有缓存（或没有作用域）返回 {@code null}。 */
    @SuppressWarnings("unchecked")
    public static <T> T get(String key) {
        Map<String, Object> values = CURRENT.get();
        if (values == null) {
            return null;
        }
        Object value = values.get(key);
        if (value == null || value == NULL) {
            return null;
        }
        return (T) value;
    }

    /** 写入缓存值；没有作用域时什么都不做。 */
    public static void put(String key, Object value) {
        Map<String, Object> values = CURRENT.get();
        if (values == null) {
            return;
        }
        values.put(key, value == null ? NULL : value);
    }

    /**
     * 「没有就计算并记住」：命中直接返回；没有作用域时直接调用 {@code loader}，不做缓存。
     *
     * <p>{@code loader} 返回 {@code null} 也会被记住（否定结果同样值得缓存，例如令牌无效），
     * 因此同一个无效令牌在一次请求里只会查一次 Redis。</p>
     */
    public static <T> T computeIfAbsent(String key, Supplier<T> loader) {
        Map<String, Object> values = CURRENT.get();
        if (values == null) {
            return loader.get();
        }
        Object cached = values.get(key);
        if (cached != null) {
            return cached == NULL ? null : (T) cached;
        }
        T value = loader.get();
        values.put(key, value == null ? NULL : value);
        return value;
    }
}
