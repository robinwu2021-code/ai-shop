package ai.neargo.auth.store;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>三端隔离 —— 本模块存在的第一个理由。</b>
 *
 * <p>今天 B 端用的是 C 端的令牌池：{@code MerchantStaffServiceImpl} 签发
 * {@code LoginUser.consumer(mchAccountNo)}，于是 {@code userNo} 这一个字段里
 * C 端塞 {@code usr_account.user_no}、B 端塞 {@code mch_account.mch_account_no}。
 * 生产上号段恰好不撞（{@code U2026…} vs {@code SF-…}），
 * <b>但那是约定不是结构保证</b>；撞上的表现是「拿商家的令牌读到某个消费者的数据」，
 * 而它不会以报错的形式出现。
 *
 * <p>这个类断言的就是：分池之后，那种撞车在结构上不可能发生。
 */
class ThreeRealmIsolationTest {

    private JdbcClient jdbc;
    private SessionDao consumer;
    private SessionDao merchant;
    private SessionDao operator;
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 26, 12, 0);

    @BeforeEach
    void setUp() {
        jdbc = AuthStoreTestSupport.freshDatabase();
        consumer = new SessionDao(jdbc, AuthStoreTestSupport.consumer());
        merchant = new SessionDao(jdbc, AuthStoreTestSupport.merchant());
        operator = new SessionDao(jdbc, AuthStoreTestSupport.operator());
    }

    @Test
    @DisplayName("★ 同一个 user_no 在三端各存一条，互相看不见")
    void sameUserNoInThreePoolsNeverCollides() {
        // 最坏情况：三端的号段真的撞了 —— 分池之后它也只是三条无关的行
        String collided = "SAME-NO-0001";
        consumer.insert(TokenHash.of("ctk_a"), collided, now, now.plusDays(30));
        merchant.insert(TokenHash.of("btk_b"), collided, now, now.plusDays(30));
        operator.insert(TokenHash.of("otk_c"), collided, now, now.plusDays(30));

        assertEquals(1, consumer.liveCountOf(collided, now));
        assertEquals(1, merchant.liveCountOf(collided, now));
        assertEquals(1, operator.liveCountOf(collided, now));

        // C 端的令牌在 B 端查不到 —— 这正是今天做不到的那一条
        assertTrue(merchant.findByHash(TokenHash.of("ctk_a")).isEmpty(),
                "C 端令牌能在 B 端查到 = 拿商家令牌读消费者数据的那条路还开着");
        assertTrue(operator.findByHash(TokenHash.of("ctk_a")).isEmpty());
        assertTrue(consumer.findByHash(TokenHash.of("otk_c")).isEmpty());
    }

    @Test
    @DisplayName("★ 踢掉 C 端的人，不能顺手把同号的商家和运营也踢了")
    void revokeInOnePoolDoesNotTouchOthers() {
        String collided = "SAME-NO-0001";
        consumer.insert(TokenHash.of("ctk_a"), collided, now, now.plusDays(30));
        merchant.insert(TokenHash.of("btk_b"), collided, now, now.plusDays(30));
        operator.insert(TokenHash.of("otk_c"), collided, now, now.plusDays(30));

        assertEquals(1, consumer.revokeByUser(collided, RevokeReason.DISABLED, now));

        assertEquals(0, consumer.liveCountOf(collided, now));
        assertEquals(1, merchant.liveCountOf(collided, now), "停用一个消费者不该殃及商家");
        assertEquals(1, operator.liveCountOf(collided, now), "更不该殃及运营账号");
    }

    @Test
    @DisplayName("撤销轮询各查各的表，不会互相污染")
    void revocationPollsAreIndependent() {
        consumer.insert(TokenHash.of("ctk_a"), "U1", now, now.plusDays(30));
        merchant.insert(TokenHash.of("btk_b"), "M1", now, now.plusDays(30));

        consumer.revoke(TokenHash.of("ctk_a"), RevokeReason.LOGOUT, now);

        assertEquals(List.of(TokenHash.of("ctk_a")), consumer.findRevokedSince(now.minusMinutes(1), 100));
        assertTrue(merchant.findRevokedSince(now.minusMinutes(1), 100).isEmpty(),
                "B 端的轮询扫到了 C 端的撤销 = 两个池子的缓存会互相剔");
    }

    @Test
    @DisplayName("三端前缀各不相同 —— 前缀是隔离的第一道（不查库就能拒）")
    void prefixesAreDistinct() {
        String c = AuthStoreTestSupport.consumer().tokenPrefix();
        String b = AuthStoreTestSupport.merchant().tokenPrefix();
        String o = AuthStoreTestSupport.operator().tokenPrefix();
        assertEquals(3, java.util.Set.of(c, b, o).size(), "前缀撞了，第一道闸就没了");
        assertEquals("ctk_", c);
        assertEquals("btk_", b);
        assertEquals("otk_", o);
    }

    @Test
    @DisplayName("三端用的是同一个 SessionDao 类 —— 一套代码，三次装配")
    void oneImplementationThreeInstances() {
        assertSame(consumer.getClass(), merchant.getClass());
        assertSame(merchant.getClass(), operator.getClass());
    }
}
