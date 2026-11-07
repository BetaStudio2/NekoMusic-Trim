package com.neko.music.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * 登录失败限流与锁定的通用实现：按「提交的账号」与「来源 IP」两个维度对**每一次登录尝试**计数，
 * 任一维度超过阈值即拒绝该次尝试并给出等待秒数（CWE-307）。
 *
 * <p>本类只实现通用机制，键前缀与阈值由 {@link AdminLoginThrottleService} 给定：
 * 管理端复用同一套经过验证的原子计数逻辑，
 * 各自的阈值仍可独立调整，也不必复制两份存储脚本。</p>
 *
 * <p>计数只看请求本身、与账号是否存在无关：不存在的账号同样会被累计与锁定，因此「是否被限流」
 * 不会反过来变成账号存在性信号。</p>
 *
 * <p><b>为什么是「先自增再判定」</b>：若先读计数判断是否超限、等口令校验失败后再自增
 * （load-then-increment），并发突发时所有请求都会读到「未超限」而全部放行，阈值形同虚设
 * （TOCTOU 竞态，CWE-362）。这里把「检查」与「计数」合并为一次原子自增（Redis {@code INCR}），
 * 每个请求拿到互不相同的计数，超过阈值的那些请求在口令校验之前就被拒掉——即便同一瞬间涌入任意多
 * 请求，实际进入口令校验的也不会超过阈值。</p>
 *
 * <p><b>账号维度为什么按「解析后的账号身份」分桶</b>：账号查找走数据库排序规则比较
 * （MySQL/MariaDB 默认 {@code *_ci} / UCA 排序规则），它既不区分大小写，也做 Unicode 兼容折叠，
 * 还会（在 {@code *_ai_} 排序规则下）忽略重音——{@code Admin}、{@code ａｄｍｉｎ}（全角）、
 * {@code ᵃdmin}（修饰符）、{@code 𝐚𝐝𝐦𝐢𝐧}（数学字母）、{@code admín}（预组合重音）在多数部署下都
 * 与 {@code admin} 判为同一个账号。应用层若自己拼一个「归一化字符串」当计数键，就必须逐字复刻
 * 数据库的整个等价类；任何一处没对齐（大小写 → NFKC → 重音），攻击者就能用同一真实账号的大量
 * 变体各开一个空桶，把「每账号 N 次」成倍放大（CWE-178）。这条「字符串归一化」路线在本项目已
 * 先后被绕过两次，因此不再作为账号维度的安全边界。</p>
 *
 * <p>本类的账号维度改为<b>服务端可判定的账号身份</b>：调用方先按与认证完全相同的路径解析账号
 * （{@code findAdminByUsername} / {@code findUserIdByEmail}），命中则用账号 ID 分桶，该 ID 在库内
 * 唯一、与排序规则无关，提交值怎么变形都落到同一桶；未命中的提交退回归一化后的字符串桶，仍与
 * 已解析账号的桶完全隔离，且「是否被限流」不暴露账号存在性。构造桶标识见 {@link #accountBucket}。
 * 字符串归一化（{@link #normalizeKeyPart}）仅保留为未命中路径的兜底与来源 IP 的稳定化，属纵深
 * 防御，不再是「每账号」口径的唯一实现。</p>
 *
 * <p>管理员用户名仍应在创建接口收敛为 ASCII（见 {@code AdminCreateHandler}），从源头减少多形态
 * 变体；但登录侧不再依赖「归一化能够穷尽等价类」这一前提。</p>
 *
 * <p>存储不可用时放行（只记 WARN）：限流不可用不应把登录整条链路打死。</p>
 */
public class LoginThrottleService {

    private static final Logger logger = LoggerFactory.getLogger(LoginThrottleService.class);

    private final Store store;
    private final String keyPrefix;
    private final int maxFailuresPerAccount;
    private final int maxFailuresPerSource;
    private final int windowSeconds;
    private final int fallbackLockSeconds;

    protected LoginThrottleService(Store store, String keyPrefix,
                                   int maxFailuresPerAccount, int maxFailuresPerSource,
                                   int windowSeconds, int fallbackLockSeconds) {
        this.store = store;
        this.keyPrefix = keyPrefix;
        this.maxFailuresPerAccount = maxFailuresPerAccount;
        this.maxFailuresPerSource = maxFailuresPerSource;
        this.windowSeconds = windowSeconds;
        this.fallbackLockSeconds = fallbackLockSeconds;
    }

    /** 已解析账号的桶前缀（账号 ID 全局唯一、与排序规则无关）。 */
    private static final String RESOLVED_PREFIX = "id:";

    /** 未命中账号的桶前缀（退回提交值归一化），与已解析桶前缀互不冒充。 */
    private static final String UNRESOLVED_PREFIX = "in:";

    /**
     * 账号维度桶标识：优先用服务端解析出的账号 ID，未命中时退回提交值的归一化字符串。
     *
     * <p>调用方应先按与认证完全相同的路径解析账号（管理端 {@code findAdminByUsername}、用户端
     * {@code findUserIdByEmail}），命中传账号 ID、未命中传 {@code empty}。这样不要求应用层复刻
     * 数据库排序规则的等价类：同一个真实账号在库内只有一个 ID，任何变形（大小写 / 全角 / 兼容 /
     * 重音）都会解析到同一 ID，因而共享同一计数桶。</p>
     *
     * <p>已解析与未命中使用不同前缀（{@code id:} / {@code in:}），因此提交一个恰好叫
     * {@code "id:1"} 的未命中标识只会落到 {@code "in:id:1"}，不会冒充真实账号 1 的桶。</p>
     *
     * @param resolvedAccountId  解析命中的账号 ID；未命中为 {@link Optional#empty()}
     * @param submittedAccount   客户端提交的原始账号（仅未命中时用作兜底桶）
     */
    public static String accountBucket(Optional<Integer> resolvedAccountId, String submittedAccount) {
        return resolvedAccountId
                .map(id -> RESOLVED_PREFIX + id)
                .orElseGet(() -> UNRESOLVED_PREFIX + normalizeKeyPart(submittedAccount));
    }

    /**
     * 记为一次登录尝试（**必须在口令校验之前调用**），原子自增两个维度的计数。
     *
     * @param account 账号维度桶标识，应由 {@link #accountBucket} 生成（也可直接传账号 ID 字符串）
     * @return 非空表示本次尝试应立即拒绝，值为建议等待秒数；空表示放行，可继续校验口令。
     */
    public Optional<Long> beginAttempt(String account, String sourceIp) {
        String accountFailureKey = accountKey(account);
        long byAccount = store.incrementInWindow(accountFailureKey, windowSeconds);
        if (byAccount > maxFailuresPerAccount) {
            return Optional.of(cooldown(accountFailureKey, "账号", byAccount));
        }

        String sourceFailureKey = sourceKey(sourceIp);
        long bySource = store.incrementInWindow(sourceFailureKey, windowSeconds);
        if (bySource > maxFailuresPerSource) {
            return Optional.of(cooldown(sourceFailureKey, "来源", bySource));
        }

        return Optional.empty();
    }

    /**
     * 登录成功：清掉该账号的失败计数，并退回本次成功尝试占用的来源计数格。
     *
     * <p>来源维度只退回一格而不是清零：成功登录本来就不该被算作失败，但也别让正常用户反复登录
     * 之外的任何东西被洗白——清零会让「有可用账号的人」把爆破来源的计数整段抹掉。</p>
     *
     * @param account 必须与 {@link #beginAttempt} 传入的账号桶标识一致（{@link #accountBucket}）
     */
    public void recordSuccess(String account, String sourceIp) {
        store.delete(accountKey(account));
        store.release(sourceKey(sourceIp));
    }

    /** 取冷却剩余秒数（计数键 TTL），取不到时兜底 {@link #fallbackLockSeconds}。 */
    private long cooldown(String key, String dimension, long count) {
        long ttl = store.ttlSeconds(key);
        long remaining = ttl > 0 ? ttl : fallbackLockSeconds;
        logger.warn("登录失败次数过多，拒绝该{}的尝试: 窗口内已 {} 次", dimension, count);
        return remaining;
    }

    private String accountKey(String account) {
        return keyPrefix + "user:" + accountKeyPart(account);
    }

    private String sourceKey(String sourceIp) {
        return keyPrefix + "ip:" + sourceKeyPart(sourceIp);
    }

    /**
     * 账号键片段：{@link #accountBucket} 生成的桶标识再走一次归一化（幂等）。
     *
     * <p>已解析账号的桶标识是 {@code id:<数字>}，归一化不会改动它；未命中桶已经是归一化后的
     * 字符串。这里刻意只做稳定化处理，<b>不</b>承担「把同一账号的变体折叠到一起」的职责——那由
     * 账号 ID 保证。</p>
     */
    private static String accountKeyPart(String account) {
        return normalizeKeyPart(account);
    }

    /** 来源 IP 键片段：NFKD + 去重音 + 去空白 + 转小写（IPv6 十六进制大小写无关）。 */
    private static String sourceKeyPart(String sourceIp) {
        return normalizeKeyPart(sourceIp);
    }

    /**
     * 键片段归一化（未命中账号的兜底 / 来源 IP 的稳定化）：NFKD 兼容分解 → 去掉组合记号 → 重组 →
     * 去空白 → 转小写。
     *
     * <p>{@link Normalizer.Form#NFKD} 覆盖全角、数学字母、上下标等兼容折叠，并把预组合字符分解为
     * 「本体 + 组合记号」；随后删除 Unicode 类别 {@code Mn}/{@code Mc}/{@code Me} 的记号即剥离重音
     * （{@code admín} → {@code admin}），贴近数据库 {@code *_ai_} 排序规则。空串用于「无账号 /
     * 无来源」的兜底桶。</p>
     *
     * <p>顺序必须是「先分解去记号、再 trim」：NFKD 会把不换行空格等折叠成普通空格，先归一化才能
     * 一并去掉。</p>
     *
     * <p><b>这不是账号维度的安全边界</b>：NFKD 去重音仍无法穷尽排序规则的全部等价类（如字母对
     * 展开 {@code ß}/{@code ss}、连字等），因此账号维度一律以 {@link #accountBucket} 的账号 ID 为准，
     * 本函数只用于未命中路径与来源 IP。</p>
     */
    private static String normalizeKeyPart(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value, Normalizer.Form.NFKD);
        String stripped = stripCombiningMarks(decomposed);
        return Normalizer.normalize(stripped, Normalizer.Form.NFKC)
                .trim()
                .toLowerCase(Locale.ROOT);
    }

    /** 删除组合记号（Unicode 类别 {@code Mn} / {@code Mc} / {@code Me}），用于剥离重音等附加符号。 */
    private static String stripCombiningMarks(String value) {
        StringBuilder out = new StringBuilder(value.length());
        value.codePoints().forEach(codePoint -> {
            int type = Character.getType(codePoint);
            if (type != Character.NON_SPACING_MARK
                    && type != Character.COMBINING_SPACING_MARK
                    && type != Character.ENCLOSING_MARK) {
                out.appendCodePoint(codePoint);
            }
        });
        return out.toString();
    }

    /**
     * 计数与锁定的存储抽象：生产实现是 Redis（{@link RedisStore}），测试注入内存实现，
     * 从而让单元测试完全不依赖真实 Redis。
     */
    public interface Store {

        /** 读剩余 TTL（秒）；无键 / 已过期 / 存储不可用均返回 {@code 0}。 */
        long ttlSeconds(String key);

        /** 窗口内原子自增：首次写入时设置过期，返回自增后的值；存储不可用返回 {@code -1}。 */
        long incrementInWindow(String key, int windowSeconds);

        /** 原子退回一格（成功尝试不该占用失败配额）；计数降到 0 即删除；存储不可用无副作用。 */
        void release(String key);

        /** 删除键。 */
        void delete(String key);
    }

    /** 生产实现：计数走单条 Lua（自增 + 首次设过期），成功时同样走 Lua 退回（降到 0 即删）。 */
    public static final class RedisStore implements Store {

        private static final String INCREMENT_SCRIPT =
                "local count = redis.call('INCR', KEYS[1]) "
                        + "if count == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[1])) end "
                        + "return count";

        private static final String RELEASE_SCRIPT =
                "if redis.call('EXISTS', KEYS[1]) == 0 then return 0 end "
                        + "local value = redis.call('DECR', KEYS[1]) "
                        + "if value <= 0 then redis.call('DEL', KEYS[1]) return 0 end "
                        + "return value";

        private final RedisService redisService;

        public RedisStore(RedisService redisService) {
            this.redisService = redisService;
        }

        @Override
        public long ttlSeconds(String key) {
            if (redisService == null) {
                return 0;
            }
            long ttl = redisService.ttl(key);
            return ttl > 0 ? ttl : 0;
        }

        @Override
        public long incrementInWindow(String key, int windowSeconds) {
            if (redisService == null) {
                return -1;
            }
            Object result = redisService.eval(INCREMENT_SCRIPT,
                    new String[]{key}, new String[]{String.valueOf(windowSeconds)});
            if (result instanceof Number number) {
                return number.longValue();
            }
            logger.warn("登录限流计数不可用（Redis 无响应），本次放行: key={}", key);
            return -1;
        }

        @Override
        public void release(String key) {
            if (redisService != null) {
                redisService.eval(RELEASE_SCRIPT, new String[]{key}, new String[0]);
            }
        }

        @Override
        public void delete(String key) {
            if (redisService != null) {
                redisService.del(key);
            }
        }
    }
}
