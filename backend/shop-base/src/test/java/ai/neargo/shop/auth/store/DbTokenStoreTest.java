package ai.neargo.shop.auth.store;

import ai.neargo.auth.store.*;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/** {@link DbTokenStore}：会话进库之后，多实例与撤销传播才成立。 */
class DbTokenStoreTest {

    /** 可拨动的时钟 —— 过期与节流都靠应用时钟判定，不能靠真等。 */
    private static final class Dial extends Clock {
        private Instant now = Instant.parse("2026-08-26T12:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
    }

    /** 可编程的用户表：记下被读了几次，用来验缓存真的省了回源。 */
    private static final class FakeUsers implements IdentityLoader<LoginUser> {
        final Map<String, LoginUser> users = new ConcurrentHashMap<>();
        final List<String> loads = new ArrayList<>();

        @Override
        public synchronized Optional<LoginUser> load(String userNo) {
            loads.add(userNo);
            return Optional.ofNullable(users.get(userNo));
        }
    }

    private Dial clock;
    private JdbcClient jdbc;
    private SessionProfile profile;
    private FakeUsers users;
    private final List<AutoCloseable> closeables = new ArrayList<>();

    @BeforeEach
    void setUp() {
        clock = new Dial();
        jdbc = AuthStoreTestSupport.freshDatabase();
        profile = AuthStoreTestSupport.consumer();
        users = new FakeUsers();
        users.users.put("U1", LoginUser.consumer("U1", "小王"));
    }

    @AfterEach
    void tearDown() {
        closeables.forEach(c -> {
            try {
                c.close();
            } catch (Exception ignored) {
                // 测试收尾，关不掉也不影响断言
            }
        });
    }

    /**
     * 身份缓存用**真实的极短 TTL**，不用上面那个假时钟。
     *
     * <p>Ehcache 的过期走**真实墙钟**，拨 {@link Dial} 不会让它的条目失效 ——
     * 第一版这么写的两条用例红了，而红的原因与被测行为无关。
     * 会话过期那条仍然用假时钟，因为那是**我们自己**用 {@code clock} 判的。
     */
    private DbTokenStore instanceWithLiveIdentity() {
        SessionProfile fast = new SessionProfile(
                profile.poolName(), profile.sessionTable(), profile.loginLogTable(),
                profile.tokenPrefix(), profile.sessionTtl(), profile.cacheTtl(),
                Duration.ofMillis(1),          // 身份缓存立刻过期
                profile.revokePoll(), profile.lastSeenThrottle(),
                profile.asyncLoginLog(), profile.logRetentionDays());
        AuthCache<String, DbTokenStore.CachedSession> sc = new AuthCache<>(
                "s" + System.nanoTime(), String.class, DbTokenStore.CachedSession.class,
                fast.cacheTtl(), 1000);
        AuthCache<String, LoginUser> ic = new AuthCache<>(
                "i" + System.nanoTime(), String.class, LoginUser.class,
                fast.identityTtl(), 1000);
        closeables.add(sc);
        closeables.add(ic);
        return new DbTokenStore(Realm.CONSUMER, fast, new SessionDao(jdbc, fast),
                users, sc, ic, auditWriter(fast), clock);
    }

    private static void letIdentityCacheExpire() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 造一个「实例」：共享同一个库，但**自己的两级缓存**。 */
    private DbTokenStore instance() {
        AuthCache<String, DbTokenStore.CachedSession> sc = new AuthCache<>(
                "s" + System.nanoTime(), String.class, DbTokenStore.CachedSession.class,
                profile.cacheTtl(), 1000);
        AuthCache<String, LoginUser> ic = new AuthCache<>(
                "i" + System.nanoTime(), String.class, LoginUser.class,
                profile.identityTtl(), 1000);
        closeables.add(sc);
        closeables.add(ic);
        return new DbTokenStore(Realm.CONSUMER, profile, new SessionDao(jdbc, profile),
                users, sc, ic, auditWriter(profile), clock);
    }

    /** 审计写同一个库，用同步档位 —— 测试里不要引入异步的时序。 */
    private LoginLogWriter auditWriter(SessionProfile p) {
        SessionProfile sync = new SessionProfile(
                p.poolName(), p.sessionTable(), p.loginLogTable(), p.tokenPrefix(),
                p.sessionTtl(), p.cacheTtl(), p.identityTtl(), p.revokePoll(),
                p.lastSeenThrottle(), false, p.logRetentionDays());
        LoginLogWriter w = new LoginLogWriter(new LoginLogDao(jdbc, sync), sync);
        closeables.add(w);
        return w;
    }

    @Test
    @DisplayName("★ 登录与登出都落审计 —— 不必改任何登录代码，三端的登录都会走到签发这一步")
    void loginAndLogoutAreAudited() {
        DbTokenStore store = instance();
        String token = store.issue(TokenStore.SessionData.of(users.users.get("U1")));
        store.revoke(token);

        var rows = new LoginLogDao(jdbc, profile).findByUser("U1", 10, 0);
        assertEquals(2, rows.size());
        assertEquals("LOGOUT", rows.get(0).event(), "最新的在前");
        assertEquals("LOGIN", rows.get(1).event());
        assertTrue(rows.get(1).success());
    }

    @Test
    @DisplayName("★ 孤儿会话落成失败事件 —— 那是数据不一致，不是「没登录」")
    void orphanSessionIsAudited() {
        DbTokenStore store = instanceWithLiveIdentity();
        String token = store.issue(TokenStore.SessionData.of(users.users.get("U1")));
        users.users.remove("U1");
        letIdentityCacheExpire();

        assertTrue(store.get(token).isEmpty());

        var rows = new LoginLogDao(jdbc, profile).recentFailures(
                java.time.LocalDateTime.now(clock).minusHours(1), 10);
        assertEquals(1, rows.size());
        assertEquals("ORPHAN_SESSION", rows.get(0).event());
        assertEquals("USER_NOT_FOUND", rows.get(0).reason());
    }

    @Test
    @DisplayName("签发 → 取回 → 身份对得上")
    void issueThenGet() {
        DbTokenStore store = instance();
        String token = store.issue(TokenStore.SessionData.of(users.users.get("U1")));

        assertTrue(token.startsWith("ctk_"), "令牌要带本池前缀");
        LoginUser back = store.get(token).orElseThrow().user();
        assertEquals("U1", back.userNo());
        assertEquals("小王", back.nickname());
    }

    @Test
    @DisplayName("★★ 两个实例共享一个库：A 踢人之后 B 拒绝 —— 这是会话进库的全部理由")
    void revokeOnOneInstanceIsSeenByTheOther() {
        DbTokenStore a = instance();
        DbTokenStore b = instance();
        String token = a.issue(TokenStore.SessionData.of(users.users.get("U1")));

        assertTrue(b.get(token).isPresent(), "B 应当认得 A 签发的会话 —— 这就是多实例共享");
        assertTrue(a.get(token).isPresent());

        a.revokeUser("U1");                 // 停用账号：A 上执行

        assertTrue(a.get(token).isEmpty(), "A 上必须立刻失效");
        b.pollRevocations();                // B 的下一轮撤销轮询
        assertTrue(b.get(token).isEmpty(),
                "B 还认这个令牌 —— 按下「停用」的人以为立刻生效了，而另一台上他照常操作");
    }

    @Test
    @DisplayName("★ 撤销轮询只剔被撤的那几条，不清空整个缓存")
    void revocationEvictsOnlyTheRevokedOnes() {
        DbTokenStore a = instance();
        DbTokenStore b = instance();
        users.users.put("U2", LoginUser.consumer("U2", "小李"));
        String t1 = a.issue(TokenStore.SessionData.of(users.users.get("U1")));
        String t2 = a.issue(TokenStore.SessionData.of(users.users.get("U2")));
        b.get(t1);
        b.get(t2);

        a.revokeUser("U1");
        int evicted = b.pollRevocations();

        assertEquals(1, evicted, "只该剔一条 —— 清空会把一次撤销放大成库上的尖峰");
        assertTrue(b.get(t2).isPresent(), "没被踢的人不该受影响");
    }

    @Test
    @DisplayName("★ 身份现读现算：改了昵称/角色，下一个请求就生效，不必踢人")
    void identityIsResolvedLive() {
        DbTokenStore store = instanceWithLiveIdentity();
        String token = store.issue(TokenStore.SessionData.of(users.users.get("U1")));
        assertEquals("小王", store.get(token).orElseThrow().user().nickname());

        users.users.put("U1", LoginUser.consumer("U1", "改过的名字"));
        letIdentityCacheExpire();

        assertEquals("改过的名字", store.get(token).orElseThrow().user().nickname(),
                "身份是从用户表现读的，会话里不该有第二份快照");
    }

    @Test
    @DisplayName("★ 停用账号：用户表读不到就 401，不给幽灵身份放行")
    void disabledAccountIsRejectedEvenWithAValidToken() {
        DbTokenStore store = instanceWithLiveIdentity();
        String token = store.issue(TokenStore.SessionData.of(users.users.get("U1")));

        users.users.remove("U1");                               // 停用/注销
        letIdentityCacheExpire();

        assertTrue(store.get(token).isEmpty(),
                "给一个空身份放行的话，那是没有任何权限的幽灵身份在系统里游走 —— "
                + "多数接口会挡住它所以不报错，直到碰上一个只判「登录了没」的接口");
    }

    @Test
    @DisplayName("★ 跨池令牌直接拒，且**不查库**")
    void foreignPoolTokenIsRejectedWithoutTouchingTheDatabase() {
        DbTokenStore store = instance();
        store.issue(TokenStore.SessionData.of(users.users.get("U1")));
        int loadsBefore = users.loads.size();

        assertTrue(store.get("otk_deadbeef").isEmpty(), "运营端令牌不该被 C 端 store 认");
        assertTrue(store.get("btk_deadbeef").isEmpty(), "商家令牌同理");
        assertEquals(loadsBefore, users.loads.size(), "前缀不符时连身份都不该去加载");
    }

    @Test
    @DisplayName("★ 不做负缓存：A 刚登录，B 立刻就能认")
    void noNegativeCaching() {
        DbTokenStore a = instance();
        DbTokenStore b = instance();

        String probe = "ctk_" + "0".repeat(32);
        assertTrue(b.get(probe).isEmpty(), "还没签发，当然认不出");

        // 现在把这个令牌真的写进库（模拟 A 实例完成登录）
        new SessionDao(jdbc, profile).insert(TokenHash.of(probe), "U1",
                java.time.LocalDateTime.now(clock), java.time.LocalDateTime.now(clock).plusDays(30));

        assertTrue(b.get(probe).isPresent(),
                "缓存了「不存在」的话，用户刚登录后的头几秒会间歇性 401 —— "
                + "而这种错「重试一下就好了」，最容易被当成网络问题");
    }

    @Test
    @DisplayName("过期的会话取不到（应用时钟判定）")
    void expiredSessionIsRejected() {
        DbTokenStore store = instance();
        String token = store.issue(TokenStore.SessionData.of(users.users.get("U1")));

        clock.advance(profile.sessionTtl().plusDays(1));

        assertTrue(store.get(token).isEmpty());
    }

    @Test
    @DisplayName("会话缓存真的省了回源 —— 命中率看得见")
    void sessionCacheAvoidsRepeatedQueries() {
        DbTokenStore store = instance();
        String token = store.issue(TokenStore.SessionData.of(users.users.get("U1")));

        for (int i = 0; i < 10; i++) {
            assertTrue(store.get(token).isPresent());
        }
        assertTrue(store.cacheStats().get(0).hitRate() > 0.8,
                "命中率掉下去通常意味着条目上限太小或 TTL 被改短了，而两者都不报错");
    }

    @Test
    @DisplayName("装配错了要当场炸：把运营端的登录塞进 C 端 store")
    void mismatchedRealmIsRejectedAtIssue() {
        DbTokenStore consumerStore = instance();
        LoginUser operator = LoginUser.operator("S1", "管理员", List.of("SUPER_ADMIN"), List.of());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> consumerStore.issue(TokenStore.SessionData.of(operator)));
        assertTrue(e.getMessage().contains("OPERATOR"), e.getMessage());
    }
}
