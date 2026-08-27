package ai.neargo.shop.config;

import ai.neargo.auth.store.SessionProfile;

import java.time.Duration;

/**
 * 三端的会话档位。**数值差异集中在这一个文件里**，别散到各处。
 *
 * <h2>两个旋钮分开拧</h2>
 * {@code cacheTtl} 大 → 回源少（<b>轻</b>）；{@code revokePoll} 小 → 踢人快（<b>安全</b>）。
 * 两者互不影响。把它们当成一个旋钮（「缓存久 = 踢人慢」）是最常见的误解，
 * 而按那个误解设计，C 端只能在「贵」和「不安全」之间二选一。
 */
public final class SessionProfiles {

    /** 30 天：与 C 端「一次登录长期有效」的体感一致；运营端由前端主动登出控制。 */
    private static final Duration TTL = Duration.ofDays(30);

    private SessionProfiles() {
    }

    /**
     * C 端：**最轻的一档**。会话量可能比另外两端高三个数量级，
     * 所以回源频率（cacheTtl）与写回频率（lastSeenThrottle）都放到最宽，
     * 而撤销延迟（revokePoll）只放宽一点点 —— 封禁必须生效。
     */
    public static final SessionProfile CONSUMER = new SessionProfile(
            "consumer", "usr_session", "usr_login_log", "ctk_",
            TTL,
            Duration.ofMinutes(5),    // cacheTtl：回源少
            Duration.ofSeconds(60),   // identityTtl
            Duration.ofSeconds(10),   // revokePoll：踢人仍然快
            Duration.ofHours(24),     // lastSeenThrottle
            true, 90);

    public static final SessionProfile MERCHANT = new SessionProfile(
            "merchant", "mch_session", "mch_login_log", "btk_",
            TTL,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Duration.ofHours(1),
            true, 180);

    /** 运营端：登录稀少、审计要求最高，日志同步写，保留两年。 */
    public static final SessionProfile OPERATOR = new SessionProfile(
            "operator", "ops_session", "ops_login_log", "otk_",
            TTL,
            Duration.ofSeconds(60),
            Duration.ofSeconds(30),
            Duration.ofSeconds(5),
            Duration.ofHours(1),
            false, 730);

    /** 缓存条目上限。C 端给大 —— 打满之后是**静默 LRU 淘汰**，表现只是「查库变多」。 */
    public static int cacheEntries(SessionProfile p) {
        return "consumer".equals(p.poolName()) ? 50_000 : 2_000;
    }
}
