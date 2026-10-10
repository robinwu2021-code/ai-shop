package ai.neargo.auth.store;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 会话表的读写。**一个类，三端各实例化一次**（表名来自 {@link SessionProfile}）。
 *
 * <h2>关于把表名拼进 SQL</h2>
 * 表名来自 {@link SessionProfile} 的常量，并在那里用正则校验过形状 ——
 * <b>它永远不来自请求</b>。这是「一套代码三张表」在 JDBC 下唯一的实现方式
 * （SQL 的表名位置不接受占位符）。校验放在 profile 的构造器里而不是这里，
 * 是因为**构造一次 vs 每次查询**：错误要在启动时炸，不要在第一次查询时炸。
 *
 * <h2>时间一律用应用时钟</h2>
 * 过期判断由调用方拿 {@code now} 传进来，SQL 里**不出现 {@code NOW()}**。
 * 多实例 + 数据库时区不一致时，「有的实例认为过期了、有的没有」这种不一致
 * 在日志里看不出来，而它会表现为随机的 401。
 */
public class SessionDao {

    private final JdbcClient jdbc;
    private final String table;

    public SessionDao(JdbcClient jdbc, SessionProfile profile) {
        this.jdbc = jdbc;
        this.table = profile.sessionTable();
    }

    private static final String COLS =
            "id, token_hash, user_no, subject_kind, issued_at, expires_at, last_seen_at,"
                    + " revoked_at, revoke_reason";

    private static SessionRow map(ResultSet rs, int rowNum) throws SQLException {
        return new SessionRow(
                rs.getLong("id"),
                rs.getString("token_hash"),
                rs.getString("user_no"),
                rs.getString("subject_kind"),
                rs.getObject("issued_at", LocalDateTime.class),
                rs.getObject("expires_at", LocalDateTime.class),
                rs.getObject("last_seen_at", LocalDateTime.class),
                rs.getObject("revoked_at", LocalDateTime.class),
                rs.getString("revoke_reason"));
    }

    /**
     * @param subjectKind {@code userNo} 属于哪张表。**必传** —— 让它有默认值等于把
     *                    「这个号该去哪查」重新变回约定，而那正是这一列要消灭的东西
     */
    public void insert(String tokenHash, String userNo, String subjectKind,
                       LocalDateTime issuedAt, LocalDateTime expiresAt) {
        jdbc.sql(("INSERT INTO " + table
                  + " (token_hash, user_no, subject_kind, issued_at, expires_at, last_seen_at)"
                  + " VALUES (:h, :u, :k, :i, :e, :i)"))
                .param("h", tokenHash).param("u", userNo).param("k", subjectKind)
                .param("i", issuedAt).param("e", expiresAt)
                .update();
    }

    /**
     * 按哈希取一行。**已撤销的也取回来**，由调用方判定 ——
     * 这样「被踢了」与「没这行」在上层是两件可区分的事，
     * 而后者要记 {@code ORPHAN_SESSION} 日志（那意味着数据不一致，值得被看见）。
     */
    public Optional<SessionRow> findByHash(String tokenHash) {
        return jdbc.sql("SELECT " + COLS + " FROM " + table + " WHERE token_hash = :h")
                .param("h", tokenHash)
                .query(SessionDao::map).optional();
    }

    /**
     * 撤销一条。
     *
     * @return 是否真的撤销了（false = 本来就不在或已撤销，调用方不必再做什么）
     */
    public boolean revoke(String tokenHash, RevokeReason reason, LocalDateTime at) {
        return jdbc.sql("UPDATE " + table + " SET revoked_at = :at, revoke_reason = :r"
                        + " WHERE token_hash = :h AND revoked_at IS NULL")
                .param("at", at).param("r", reason.name()).param("h", tokenHash)
                .update() > 0;
    }

    /**
     * 踢掉某个主体的**全部**在线会话，一条 UPDATE。
     *
     * <p>今天的 Ehcache 实现要遍历本地缓存才能做到，而且**只覆盖本进程** ——
     * 这正是多实例下最危险的一处：按下「停用」的那个人以为立刻生效了。
     *
     * @return 踢掉的会话数，即接口约定的返回值
     */
    public int revokeByUser(String userNo, RevokeReason reason, LocalDateTime at) {
        return jdbc.sql("UPDATE " + table + " SET revoked_at = :at, revoke_reason = :r"
                        + " WHERE user_no = :u AND revoked_at IS NULL")
                .param("at", at).param("r", reason.name()).param("u", userNo)
                .update();
    }

    /**
     * 上次轮询之后新撤销的会话。**撤销传播的主力**。
     *
     * <p>只取新增的那几条，**不是全表**，调用方也只剔这几条的缓存 ——
     * 清空整个本地缓存会在踢一个人时让所有在线用户的下一次请求一起回源，
     * 把一次撤销放大成库上的尖峰。
     *
     * <p><b>边界是闭区间，这一条是被测试逼出来的。</b>用 {@code >} 的话，
     * 恰好发生在上一轮水位线那一刻的撤销会被**永久漏掉** ——
     * 它既不在上一轮（那时还没写库），也不在下一轮（时间戳不大于水位线）。
     * 症状是「偶尔有人被踢了却还能用」，而且**不可复现**：
     * 它只在撤销与轮询落在同一时刻时发生。
     *
     * <p>闭区间的代价是同一条可能被连续两轮各剔一次 —— 而剔除是幂等的，
     * 重复一次没有任何后果。<b>用「可能白做一次」换「绝不漏一条」，在这里是明显划算的。</b>
     */
    public List<String> findRevokedSince(LocalDateTime since, int limit) {
        return jdbc.sql("SELECT token_hash FROM " + table
                        + " WHERE revoked_at >= :since ORDER BY revoked_at LIMIT :limit")
                .param("since", since).param("limit", limit)
                .query(String.class).list();
    }

    /**
     * 记一次活跃，**并把有效期往后推**。
     *
     * <p>由调用方按 {@link SessionProfile#lastSeenThrottle} 节流 ——
     * 不要每个请求都调，那会把这张表变成全库写入最频繁的表。
     *
     * <h2>为什么续期挂在这一句上</h2>
     * 在这之前 {@code expires_at} 是**签发那一刻定死的**，用得再勤也不延长 ——
     * 于是每个用户在登录整 30 天那一刻被踢，而 C 端有静默登录会自愈、
     * <b>B 端与运营端没有</b>：401 的动作是清掉登录态 + 跳回登录页，
     * 商家要重新收一次短信才能回来，且他正干着的那一页没了。
     *
     * <p>挂在这里而不是另开一个续期端点，是因为续期端点需要**一个还活着的
     * 令牌**，而 401 的前提正是令牌已经死了 —— 它在最需要它的那一刻用不了。
     * 而 touch 本来就在跑，多写一列不增加任何一次写。
     *
     * <p><b>{@code expires_at} 由调用方算好传进来</b>，不在 SQL 里做日期运算：
     * 测试跑 H2、生产跑 MariaDB，两边的日期函数不是一回事。
     *
     * <p>{@code revoked_at IS NULL} 那一条不是多余的：撤销与缓存之间有个
     * {@code revokePoll} 的窗口，窗口里这个实例仍可能 touch 一个已被踢掉的会话。
     * 推它的有效期不会让它复活（{@code isLive} 还看 revoked_at），
     * 但一个已经吊销的会话不该再被记成「刚活跃过」。
     */
    public void touch(String tokenHash, LocalDateTime at, LocalDateTime expiresAt) {
        jdbc.sql("UPDATE " + table
                        + " SET last_seen_at = :at, expires_at = :e"
                        + " WHERE token_hash = :h AND revoked_at IS NULL")
                .param("at", at).param("e", expiresAt).param("h", tokenHash)
                .update();
    }

    /**
     * 物理清理：**只删过期很久的**。软撤销的行本身是审计资料，
     * 「我为什么被登出」要查得到，所以不按 {@code revoked_at} 删。
     *
     * <p>分批删而不是一条 DELETE 删干净：一次删几十万行会长时间持锁，
     * 而这张表同时正被登录写入。
     */
    public int purgeExpiredBefore(LocalDateTime before, int batchSize) {
        return jdbc.sql("DELETE FROM " + table + " WHERE expires_at < :before LIMIT :batch")
                .param("before", before).param("batch", batchSize)
                .update();
    }

    /** 某个主体当前在线的会话数。运营端「他在几台设备上登录着」用得上。 */
    public int liveCountOf(String userNo, LocalDateTime now) {
        Integer n = jdbc.sql("SELECT COUNT(*) FROM " + table
                        + " WHERE user_no = :u AND revoked_at IS NULL AND expires_at > :now")
                .param("u", userNo).param("now", now)
                .query(Integer.class).single();
        return n == null ? 0 : n;
    }
}
