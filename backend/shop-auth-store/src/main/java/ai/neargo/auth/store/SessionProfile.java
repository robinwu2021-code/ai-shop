package ai.neargo.auth.store;

import java.time.Duration;

/**
 * 一个令牌池的全部参数。**三端各一份，是「一套代码三次装配」里的那个「份」。**
 *
 * <p>本模块刻意不认识 {@code Realm}：它只需要知道「往哪张表写、认什么前缀、各种时限多长」。
 * realm 是上层概念，留在 shop-base —— 少知道一件事，就少一条耦合。
 *
 * <p><b>两个时限旋钮必须分开理解，这是 C 端能轻的全部原因：</b>
 * <ul>
 *   <li>{@link #cacheTtl} 大 → 回源少（<b>轻</b>）</li>
 *   <li>{@link #revokePoll} 小 → 踢人快（<b>安全</b>）</li>
 * </ul>
 * 两者互不影响。把它们当成一个旋钮（「缓存久 = 踢人慢」）是最常见的误解，
 * 而按那个误解设计，C 端只能在「贵」和「不安全」之间二选一。
 *
 * @param poolName          日志与指标里的名字，如 {@code consumer}
 * @param sessionTable      会话表名。**只能来自本类的常量，不接受外部输入**（见 SessionDao）
 * @param loginLogTable     登录日志表名，同上
 * @param tokenPrefix       令牌前缀。前缀不符时直接 401 且**不查库**，是端隔离的第一道
 * @param sessionTtl        会话有效期
 * @param cacheTtl          会话缓存（token → user_no）的存活时间：会话内永不变，可以长
 * @param identityTtl       身份缓存（user_no → 身份）的存活时间：随用户表变，要短
 * @param revokePoll        撤销轮询间隔，决定跨实例踢人的延迟上界
 * @param lastSeenThrottle  {@code last_seen_at} 的写回节流；每请求写会让这张表成为全库写最频繁的表
 * @param asyncLoginLog     成功登录是否异步落日志（失败**永远同步**，见 LoginLogDao）
 * @param logRetentionDays  登录日志保留天数
 */
public record SessionProfile(
        String poolName,
        String sessionTable,
        String loginLogTable,
        String tokenPrefix,
        Duration sessionTtl,
        Duration cacheTtl,
        Duration identityTtl,
        Duration revokePoll,
        Duration lastSeenThrottle,
        boolean asyncLoginLog,
        int logRetentionDays) {

    /** 表名只允许这个形状。拼进 SQL 之前过一道，见 {@link SessionDao} 的类注释。 */
    private static final java.util.regex.Pattern SAFE_TABLE =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]{2,63}");

    public SessionProfile {
        requireTable(sessionTable, "sessionTable");
        requireTable(loginLogTable, "loginLogTable");
        if (tokenPrefix == null || tokenPrefix.isBlank()) {
            throw new IllegalArgumentException("tokenPrefix 不能为空：它是端隔离的第一道");
        }
        requirePositive(sessionTtl, "sessionTtl");
        requirePositive(cacheTtl, "cacheTtl");
        requirePositive(identityTtl, "identityTtl");
        requirePositive(revokePoll, "revokePoll");
        if (logRetentionDays <= 0) {
            throw new IllegalArgumentException("logRetentionDays 必须为正");
        }
        // 会话缓存比撤销轮询短的话，轮询就没有意义了（TTL 先一步把它清掉）——
        // 不是错误，但多半是配错了，值得当场喊出来
        if (cacheTtl.compareTo(revokePoll) < 0) {
            throw new IllegalArgumentException(
                    "cacheTtl(%s) 比 revokePoll(%s) 还短，撤销轮询形同虚设：%s"
                            .formatted(cacheTtl, revokePoll, poolName));
        }
    }

    private static void requireTable(String v, String field) {
        if (v == null || !SAFE_TABLE.matcher(v).matches()) {
            throw new IllegalArgumentException(field + " 必须是合法表名（小写字母数字下划线）：" + v);
        }
    }

    private static void requirePositive(Duration d, String field) {
        if (d == null || d.isZero() || d.isNegative()) {
            throw new IllegalArgumentException(field + " 必须为正");
        }
    }
}
