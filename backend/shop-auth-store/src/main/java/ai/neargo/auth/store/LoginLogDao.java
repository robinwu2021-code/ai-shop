package ai.neargo.auth.store;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 登录日志的读写。一个类，三端各实例化一次。
 *
 * <h2>这张表不是控制平面</h2>
 * 若将来要「失败 N 次锁定账号」，那个计数<b>单独放</b> ——
 * 现有的 {@code RateLimiter} 就是这么做的（密码尝试 15 分钟 10 次，与任何日志表无关）。
 *
 * <p>理由是两者的可靠性要求相反：审计**可以丢、可以异步、可以采样**，控制平面不行。
 * 合在一张表上，等于让安全策略依赖一条允许丢失的写入 ——
 * 而丢失发生时不会有人发现，只会有人某天发现限流没生效。
 */
public class LoginLogDao {

    private final JdbcClient jdbc;
    private final String table;

    public LoginLogDao(JdbcClient jdbc, SessionProfile profile) {
        this.jdbc = jdbc;
        this.table = profile.loginLogTable();
    }

    private static final String COLS =
            "id, at, event, user_no, success, reason, client_ip, user_agent";

    private static LoginLogRow map(ResultSet rs, int rowNum) throws SQLException {
        return new LoginLogRow(
                rs.getLong("id"),
                rs.getObject("at", LocalDateTime.class),
                rs.getString("event"),
                rs.getString("user_no"),
                rs.getBoolean("success"),
                rs.getString("reason"),
                rs.getString("client_ip"),
                rs.getString("user_agent"));
    }

    /**
     * 追加一条。
     *
     * <p><b>成功登录可以异步，失败登录必须同步</b>（由调用方按
     * {@link SessionProfile#asyncLoginLog} 决定走哪条）：登录是最容易被刷的接口之一，
     * 而失败日志正是被刷时最该留下的证据 —— 那条不能丢。
     */
    public void append(LocalDateTime at, LoginEvent event, String userNo, boolean success,
                       String reason, String clientIp, String userAgent) {
        jdbc.sql("INSERT INTO " + table
                 + " (at, event, user_no, success, reason, client_ip, user_agent)"
                 + " VALUES (:at, :e, :u, :s, :r, :ip, :ua)")
                .param("at", at).param("e", event.name()).param("u", userNo)
                .param("s", success ? 1 : 0).param("r", reason)
                .param("ip", clientIp).param("ua", userAgent)
                .update();
    }

    /** 某个主体的登录历史，倒序分页。「他上次什么时候登的、从哪登的」。 */
    public List<LoginLogRow> findByUser(String userNo, int limit, int offset) {
        return jdbc.sql("SELECT " + COLS + " FROM " + table
                        + " WHERE user_no = :u ORDER BY at DESC, id DESC LIMIT :l OFFSET :o")
                .param("u", userNo).param("l", limit).param("o", offset)
                .query(LoginLogDao::map).list();
    }

    /** 最近的失败记录，排查「他说登不上」时用。 */
    public List<LoginLogRow> recentFailures(LocalDateTime since, int limit) {
        return jdbc.sql("SELECT " + COLS + " FROM " + table
                        + " WHERE success = 0 AND at > :since ORDER BY at DESC LIMIT :l")
                .param("since", since).param("l", limit)
                .query(LoginLogDao::map).list();
    }

    /** 按保留期清理，分批。保留期三端不同（C 90 天 / B 180 天 / 运营 730 天）。 */
    public int purgeBefore(LocalDateTime before, int batchSize) {
        return jdbc.sql("DELETE FROM " + table + " WHERE at < :before LIMIT :batch")
                .param("before", before).param("batch", batchSize)
                .update();
    }
}
