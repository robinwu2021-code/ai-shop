package ai.neargo.auth.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 会话表读写。三端各跑一遍见 {@link ThreeRealmIsolationTest}，这里只用 C 端验语义。 */
class SessionDaoTest {

    private JdbcClient jdbc;
    private SessionDao dao;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);

    @BeforeEach
    void setUp() {
        jdbc = AuthStoreTestSupport.freshDatabase();
        dao = new SessionDao(jdbc, AuthStoreTestSupport.consumer());
    }

    private String issue(String userNo, LocalDateTime expiresAt) {
        String hash = TokenHash.of("ctk_" + userNo + expiresAt);
        dao.insert(hash, userNo, "USR", now, expiresAt);
        return hash;
    }

    @Test
    @DisplayName("签发 → 取回 → 八列一致")
    void insertThenFind() {
        String hash = issue("U1", now.plusDays(30));

        SessionRow row = dao.findByHash(hash).orElseThrow();
        assertEquals("U1", row.userNo());
        assertEquals(now, row.issuedAt());
        assertEquals(now.plusDays(30), row.expiresAt());
        assertNull(row.revokedAt());
        assertTrue(row.isLive(now));
    }

    @Test
    @DisplayName("★ 库里只有哈希，明文令牌不出现在任何列")
    void plaintextTokenNeverHitsTheDatabase() {
        String plain = "ctk_0123456789abcdef0123456789abcdef";
        dao.insert(TokenHash.of(plain), "U1", "USR", now, now.plusDays(30));

        String dump = jdbc.sql("SELECT token_hash || '|' || user_no || '|' || "
                        + "COALESCE(revoke_reason,'') FROM usr_session")
                .query(String.class).single();
        assertFalse(dump.contains(plain), "明文令牌进库了 —— 库被拖走就等于所有会话被拿走");
        assertEquals(64, TokenHash.of(plain).length(), "SHA-256 十六进制应为 64 字符");
    }

    @Test
    @DisplayName("过期用应用时钟判定，不依赖数据库的 NOW()")
    void expiryUsesApplicationClock() {
        String hash = issue("U1", now.minusSeconds(1));
        SessionRow row = dao.findByHash(hash).orElseThrow();

        assertFalse(row.isLive(now), "已过期");
        assertTrue(row.isLive(now.minusMinutes(1)), "一分钟前还没过期");
    }

    @Test
    @DisplayName("撤销后行还在（软撤销），但不再有效")
    void revokeIsSoft() {
        String hash = issue("U1", now.plusDays(30));

        assertTrue(dao.revoke(hash, RevokeReason.LOGOUT, now));

        SessionRow row = dao.findByHash(hash).orElseThrow();
        assertNotNull(row.revokedAt(), "行必须还在 —— 「我为什么突然被登出」要查得到");
        assertEquals("LOGOUT", row.revokeReason());
        assertFalse(row.isLive(now));

        assertFalse(dao.revoke(hash, RevokeReason.LOGOUT, now), "重复撤销应报告「没做什么」");
    }

    @Test
    @DisplayName("★ revokeUser 一条 UPDATE 踢掉全部会话，并返回踢掉几条")
    void revokeUserKicksAllSessions() {
        issue("U1", now.plusDays(30));
        issue("U1", now.plusDays(29));
        issue("U1", now.plusDays(28));
        String other = issue("U2", now.plusDays(30));

        assertEquals(3, dao.revokeByUser("U1", RevokeReason.DISABLED, now));

        assertEquals(0, dao.liveCountOf("U1", now));
        assertEquals(1, dao.liveCountOf("U2", now), "不能误伤别人");
        assertTrue(dao.findByHash(other).orElseThrow().isLive(now));
    }

    @Test
    @DisplayName("★ 撤销轮询只取「上次之后新撤销的」，不是全表")
    void findRevokedSinceReturnsOnlyTheNewOnes() {
        String a = issue("U1", now.plusDays(30));
        String b = issue("U2", now.plusDays(30));
        dao.revoke(a, RevokeReason.LOGOUT, now.minusMinutes(10));

        List<String> fresh = dao.findRevokedSince(now.minusMinutes(1), 100);
        assertTrue(fresh.isEmpty(), "十分钟前撤销的不该出现在「最近一分钟」里");

        dao.revoke(b, RevokeReason.FORCED_OUT, now);
        assertEquals(List.of(b), dao.findRevokedSince(now.minusMinutes(1), 100),
                "只该拿到新撤销的那一条 —— 拿全表会让踢一个人变成清空所有人的缓存");
    }

    @Test
    @DisplayName("清理只删过期很久的，不按撤销删")
    void purgeKeepsRevokedRowsForAudit() {
        String expired = issue("U1", now.minusDays(40));
        String revoked = issue("U2", now.plusDays(30));
        dao.revoke(revoked, RevokeReason.DISABLED, now);

        int deleted = dao.purgeExpiredBefore(now.minusDays(30), 100);

        assertEquals(1, deleted);
        assertTrue(dao.findByHash(expired).isEmpty());
        assertTrue(dao.findByHash(revoked).isPresent(),
                "软撤销的行是审计资料，不能被清理顺手带走");
    }

    @Test
    @DisplayName("touch 更新活跃时间（节流由调用方做，DAO 只管写）")
    void touchUpdatesLastSeen() {
        String hash = issue("U1", now.plusDays(30));
        dao.touch(hash, now.plusHours(2));
        assertEquals(now.plusHours(2), dao.findByHash(hash).orElseThrow().lastSeenAt());
    }

    @Test
    @DisplayName("★★★ subject_kind 落库并取得回来 —— 它是「这个号去哪张表查」的唯一依据")
    void subjectKindRoundTrips() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 28, 19, 0);
        dao.insert("h-mch", "SF-M0001", "MCH", now, now.plusDays(1));
        dao.insert("h-usr", "U202608181350550001913", "USR", now, now.plusDays(1));

        assertEquals("MCH", dao.findByHash("h-mch").orElseThrow().subjectKind());
        assertEquals("USR", dao.findByHash("h-usr").orElseThrow().subjectKind());
    }

    @Test
    @DisplayName("★ 同一张表里两种主体可以共存 —— B 端池就是这个形态")
    void bothKindsCoexistInOnePool() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 28, 19, 0);
        // 店员（没有 C 端账号）与还没开店的人，会话都落在 B 端池里
        dao.insert("h-a", "SF-M0002", "MCH", now, now.plusDays(1));
        dao.insert("h-b", "U202608221744550003915", "USR", now, now.plusDays(1));

        assertEquals("SF-M0002", dao.findByHash("h-a").orElseThrow().userNo());
        assertEquals("MCH", dao.findByHash("h-a").orElseThrow().subjectKind());
        assertEquals("USR", dao.findByHash("h-b").orElseThrow().subjectKind());
        // 靠号段形状区分是不行的：那是数据长成这样，不是任何生成器保证的
    }
}
