package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import ai.neargo.shop.auth.store.RedisTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * 运营端的会话放在它自己的 Redis 上，而那个 Redis 连不上：只有运营端受影响（ADR-061；方案 AC26 后半）。
 *
 * <p>地址指向本机 1 号端口，必然连不上；另外两端仍用进程内的共用存储。
 */
@SpringBootTest(properties = {
        "shop.auth.token-store-by-realm.operator=redis",
        "shop.gateway.ops.redis.host=127.0.0.1",
        "shop.gateway.ops.redis.port=1",
        "shop.gateway.ops.redis.timeout=300ms"})
@ActiveProfiles("test")
@DisplayName("网关 · 一端自己的 Redis 挂了，只影响那一端")
class GatewayEndDedicatedRedisTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    TokenStore tokenStore;
    @Autowired
    TokenStores tokenStores;

    @Test
    @DisplayName("★★★ 运营端用自己的 Redis 存储，另两端仍是共用的；运营端 Redis 不通时 C 端照常")
    void onlyTheEndWithBrokenRedisIsAffected() throws Exception {
        assertThat(tokenStores.of(Realm.OPERATOR)).isInstanceOf(RedisTokenStore.class);
        assertThat(tokenStores.of(Realm.CONSUMER)).isInstanceOf(MemoryTokenStore.class);
        assertThat(tokenStores.of(Realm.MERCHANT)).isSameAs(tokenStores.of(Realm.CONSUMER));

        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String consumer = tokenStore.issue(TokenStore.SessionData.of(LoginUser.consumer("ISO-C", "iso")));
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/mp/after-sale")
                        .header("Authorization", "Bearer " + consumer)).andReturn().getResponse().getStatus())
                .as("C 端的会话不经过运营端的 Redis").isEqualTo(200);
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/ops/admission/policies"))
                        .andReturn().getResponse().getStatus())
                .as("运营端不带令牌：还没碰到 Redis，照常 401").isEqualTo(401);

        // 运营端带令牌：要去它自己的 Redis 查会话，连不上 —— 回 503（AC26 后半），不是 401，也不是未处理的异常
        var broken = mvc.perform(MockMvcRequestBuilders.get("/ops/admission/policies")
                .header("Authorization", "Bearer otk_isolation-probe")).andReturn().getResponse();
        assertThat(broken.getStatus()).isEqualTo(503);
        assertThat(broken.getHeader("Retry-After")).isEqualTo("5");
        assertThat(broken.getContentAsString()).contains("\"code\":10500");
        // 同一时刻 C 端带令牌的请求仍然正常
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/mp/after-sale")
                        .header("Authorization", "Bearer " + consumer)).andReturn().getResponse().getStatus())
                .isEqualTo(200);
    }
}
