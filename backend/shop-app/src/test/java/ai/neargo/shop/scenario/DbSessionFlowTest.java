package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.RealmRoutingTokenStore;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.support.TestLogin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * 会话进库之后，C 端与运营端**端到端**还成立吗。
 *
 * <p>前面那些单元测试都是手工 new 出来的，装配错了一条都不会红 ——
 * 而这次改的正是装配：三个池、三份缓存、一个路由门面。所以这一组必须走真实上下文。
 */
@SpringBootTest
@ActiveProfiles("test")
/*
 * ⚠️ **第二个 Spring 上下文必须换一个 H2 库名。**
 *
 * 只写 token-store 那一条的话，这个类会造出第二个上下文，而 H2 是**同一个内存库**
 * （`jdbc:h2:mem:shop;DB_CLOSE_DELAY=-1`）—— 建表脚本跑第二遍，整套测试成片挂在
 * `sys_industry` 主键冲突上，而报错与本类要测的东西毫无关系。
 *
 * 这个坑仓库里写过（见 StoreAndStaffFlowTest 的类注释），正解见 InventoryDualWriteTest：
 * 给第二个上下文自己的库名。**我照样踩了一次** —— 那条注释写在另一个测试文件里，
 * 而没有任何机制会在你写下 @TestPropertySource 的那一刻提醒你。
 */
@TestPropertySource(properties = {
        "shop.auth.token-store=db",
        "spring.datasource.url=jdbc:h2:mem:shop_authdb;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
})
@DisplayName("会话进库 · C 端与运营端")
class DbSessionFlowTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ai.neargo.shop.common.OtpStore otpStore;

    @Autowired
    private TokenStore tokenStore;

    @Autowired
    private TokenStores tokenStores;

    private MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers
                        .springSecurity())
                .build();
    }

    @Test
    @DisplayName("★ 装配起来了：注入的是按池分发的门面，三个池都在")
    void routingStoreIsWired() {
        assertThat(tokenStore).isInstanceOf(RealmRoutingTokenStore.class);
        for (Realm r : Realm.values()) {
            assertThat(tokenStores.of(r))
                    .as("%s 没有会话存储 —— 那一端会全员登不上", r)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("★ 三个池各用各的存储 —— 共用等于把刚分开的边界又合上")
    void everyRealmHasItsOwnStore() {
        assertThat(tokenStores.of(Realm.CONSUMER)).isNotSameAs(tokenStores.of(Realm.OPERATOR));
        assertThat(tokenStores.of(Realm.CONSUMER)).isNotSameAs(tokenStores.of(Realm.MERCHANT));
        assertThat(tokenStores.of(Realm.MERCHANT)).isNotSameAs(tokenStores.of(Realm.OPERATOR));
    }

    @Test
    @DisplayName("★ 运营端：登录 → 令牌带 otk_ → 带着它访问通得过")
    void operatorLoginAndCall() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        assertThat(token).startsWith("otk_");

        mvc().perform(get("/ops/staffs?page=1&size=1").header("Authorization", "Bearer " + token))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("★★ 运营端：踢人之后立刻不通 —— 会话在库里，不是进程内")
    void operatorRevokeTakesEffect() throws Exception {
        String token = TestLogin.admin(mvc(), json);
        String staffNo = tokenStore.get(token).orElseThrow().user().userNo();

        int kicked = tokenStores.of(Realm.OPERATOR).revokeUser(staffNo);
        assertThat(kicked).isPositive();

        assertThat(tokenStore.get(token))
                .as("踢了还能取到会话 = 「停用后立即无法操作」这条契约没兑现")
                .isEmpty();
    }

    @Test
    @DisplayName("★ C 端：登录 → 令牌带 ctk_ → 身份从 usr_account 现读")
    void consumerLoginAndIdentity() throws Exception {
        String token = TestLogin.consumer(mvc(), json, otpStore, "13900001111");
        assertThat(token).startsWith("ctk_");

        var user = tokenStore.get(token).orElseThrow().user();
        assertThat(user.realm()).isEqualTo(Realm.CONSUMER);
        assertThat(user.userNo()).isNotBlank();
    }

    @Test
    @DisplayName("★ 跨池：运营令牌取不到 C 端会话（前缀不符，连库都不查）")
    void crossPoolTokenResolvesToNothing() throws Exception {
        String ops = TestLogin.admin(mvc(), json);

        assertThat(tokenStores.of(Realm.CONSUMER).get(ops))
                .as("C 端池认了运营端的令牌 = 端隔离的第一道漏了")
                .isEmpty();
    }

    @Test
    @DisplayName("revokeUser 不指明池就拒绝 —— 而不是在所有池里乱踢")
    void revokeUserWithoutRealmIsRejected() {
        assertThrows(UnsupportedOperationException.class, () -> tokenStore.revokeUser("whoever"));
    }
}
