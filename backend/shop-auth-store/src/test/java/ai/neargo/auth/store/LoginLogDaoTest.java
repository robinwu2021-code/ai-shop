package ai.neargo.auth.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 登录审计。 */
class LoginLogDaoTest {

    private JdbcClient jdbc;
    private LoginLogDao logs;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);

    @BeforeEach
    void setUp() {
        jdbc = AuthStoreTestSupport.freshDatabase();
        logs = new LoginLogDao(jdbc, AuthStoreTestSupport.consumer());
    }

    @Test
    @DisplayName("成功与失败都记，倒序取回")
    void appendAndQuery() {
        logs.append(now.minusMinutes(2), LoginEvent.LOGIN_FAILED, "U1", false,
                "BAD_PASSWORD", "10.0.0.1", "okhttp/4");
        logs.append(now, LoginEvent.LOGIN, "U1", true, null, "10.0.0.1", "okhttp/4");

        List<LoginLogRow> rows = logs.findByUser("U1", 10, 0);
        assertEquals(2, rows.size());
        assertEquals("LOGIN", rows.get(0).event(), "最新的在前");
        assertTrue(rows.get(0).success());
        assertEquals("BAD_PASSWORD", rows.get(1).reason());
    }

    @Test
    @DisplayName("最近失败可单独查 —— 「他说登不上」的第一手材料")
    void recentFailures() {
        logs.append(now.minusDays(2), LoginEvent.LOGIN_FAILED, "U1", false, "BAD_PASSWORD", null, null);
        logs.append(now, LoginEvent.LOGIN_FAILED, "U2", false, "BAD_CODE", null, null);
        logs.append(now, LoginEvent.LOGIN, "U3", true, null, null, null);

        List<LoginLogRow> fails = logs.recentFailures(now.minusHours(1), 50);
        assertEquals(1, fails.size());
        assertEquals("U2", fails.get(0).userNo());
    }

    @Test
    @DisplayName("孤儿会话要能记 —— 令牌有效但用户查不到，那是数据不一致，必须看得见")
    void orphanSessionIsRecordable() {
        logs.append(now, LoginEvent.ORPHAN_SESSION, "U-GONE", false, "USER_NOT_FOUND", null, null);
        assertEquals("ORPHAN_SESSION", logs.findByUser("U-GONE", 1, 0).get(0).event());
    }

    @Test
    @DisplayName("按保留期清理")
    void purgeByRetention() {
        logs.append(now.minusDays(100), LoginEvent.LOGIN, "U1", true, null, null, null);
        logs.append(now.minusDays(10), LoginEvent.LOGIN, "U1", true, null, null, null);

        assertEquals(1, logs.purgeBefore(now.minusDays(90), 1000));
        assertEquals(1, logs.findByUser("U1", 10, 0).size());
    }

    @Test
    @DisplayName("三端各写各的日志表")
    void logTablesAreIsolated() {
        LoginLogDao merchantLogs = new LoginLogDao(jdbc, AuthStoreTestSupport.merchant());
        logs.append(now, LoginEvent.LOGIN, "SAME-NO", true, null, null, null);

        assertTrue(merchantLogs.findByUser("SAME-NO", 10, 0).isEmpty(),
                "C 端的登录记录出现在 B 端的审计里 = 两端的审计边界没分开");
    }
}
