package com.neko.music.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.Optional;

/**
 * 认证 / 验证码类接口的来源 IP 请求限流（CWE-799）。
 *
 * <p>背景：登录、注册、发码、滑块这些入口除了「失败计数锁定」之外，还需要一道与账号无关的
 * <b>请求速率</b>闸门。否则脚本可以用**不同的账号 / 邮箱**把请求打满：每个账号都没失败几次，
 * 失败锁定不触发，于是「无限速」依旧成立。</p>
 *
 * <p>计数落在 Redis 的固定窗口里，因此**多实例部署下也共享同一份额度**——这一点很关键：若只用
 * 进程内计数，请求被负载均衡分到 N 个实例上时，实际额度会被放大到 N 倍，等于没有限流。
 * Redis 不可用时放行（只记 WARN），与 {@link IPRateLimitService} 一致：
 * 限流不可用不应把登录整条链路打死。</p>
 *
 * <p>阈值是类常量，不新增配置项。</p>
 */
public class AuthAbuseLimiter {

    private static final Logger logger = LoggerFactory.getLogger(AuthAbuseLimiter.class);

    private static final String KEY_PREFIX = "auth_rate:";

    /** 接口类别：不同入口的合理请求速率不同，各自独立计数。 */
    public enum Scope {
        /** 登录：定向爆破由失败锁定兜底，这里限制「高速尝试」本身。 */
        LOGIN(12, 60),
        /** 注册：正常用户一次注册几乎不会连发。 */
        REGISTER(6, 60),
        /** 发送邮箱验证码（注册 / 重置）：叠加同邮箱冷却与邮箱槽位。 */
        SEND_CODE(6, 60),
        /** 滑块挑战 / 校验：正常用户可能重试几次，阈值放宽但不得无限刷。 */
        CAPTCHA(40, 60);

        final int maxRequests;
        final int windowSeconds;

        Scope(int maxRequests, int windowSeconds) {
            this.maxRequests = maxRequests;
            this.windowSeconds = windowSeconds;
        }
    }

    private final Store store;

    public AuthAbuseLimiter(Store store) {
        this.store = store;
    }

    /**
     * 消耗一个请求额度。
     *
     * @return empty 表示放行；非 empty 为建议等待秒数（{@code Retry-After}）
     */
    public Optional<Long> tryAcquire(Scope scope, String clientIp) {
        String key = KEY_PREFIX + scope.name().toLowerCase(Locale.ROOT) + ':' + normalizeIp(clientIp);

        long count = store.incrementInWindow(key, scope.windowSeconds);
        if (count < 0) {
            logger.warn("认证限流计数不可用（Redis 无响应），本次放行: key={}", key);
            return Optional.empty();
        }
        if (count <= scope.maxRequests) {
            return Optional.empty();
        }

        long ttl = store.ttlSeconds(key);
        long retryAfter = ttl > 0 ? ttl : scope.windowSeconds;
        logger.warn("认证入口来源请求过快，拒绝该次请求: scope={} 窗口内已 {} 次", scope, count);
        return Optional.of(retryAfter);
    }

    private static String normalizeIp(String clientIp) {
        return clientIp == null || clientIp.isBlank()
                ? "unknown"
                : clientIp.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 计数存储抽象：生产实现是 Redis（{@link RedisStore}），测试注入内存实现，
     * 单元测试因此不依赖真实 Redis。
     */
    public interface Store {

        /** 窗口内原子自增：首次写入时设置过期，返回自增后的值；存储不可用返回 {@code -1}。 */
        long incrementInWindow(String key, int windowSeconds);

        /** 读剩余 TTL（秒）；无键 / 已过期 / 存储不可用均返回 {@code 0}。 */
        long ttlSeconds(String key);
    }

    /** 生产实现：自增 + 首次设过期合并为一次 Lua，避免「先自增后过期」之间进程重启留下永久键。 */
    public static final class RedisStore implements Store {

        private static final String INCREMENT_SCRIPT =
                "local count = redis.call('INCR', KEYS[1]) "
                        + "if count == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end "
                        + "return count";

        private final RedisService redisService;

        public RedisStore(RedisService redisService) {
            this.redisService = redisService;
        }

        @Override
        public long incrementInWindow(String key, int windowSeconds) {
            if (redisService == null) {
                return -1;
            }
            Object result = redisService.eval(INCREMENT_SCRIPT,
                    new String[]{key}, new String[]{String.valueOf(windowSeconds)});
            return result instanceof Number number ? number.longValue() : -1;
        }

        @Override
        public long ttlSeconds(String key) {
            if (redisService == null) {
                return 0;
            }
            long ttl = redisService.ttl(key);
            return ttl > 0 ? ttl : 0;
        }
    }
}
