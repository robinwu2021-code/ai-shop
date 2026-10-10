package ai.neargo.shop.scenario;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.RealmRoutingTokenStore;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.DbTokenStore;
import ai.neargo.shop.auth.store.MemoryTokenStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话外置<b>一端一端地切</b>。
 *
 * <h2>为什么必须能分批</h2>
 * <p>{@code shop.auth.token-store} 是一个全局值：从 {@code ehcache} 切到 {@code db}，
 * 三端的会话在同一秒全部失效 —— 全部消费者、全部商家、全部运营一起重新登录。
 * 而这件事本该先在十几个运营账号上跑一天，再推到全量用户。
 *
 * <p><b>此前那个「三批灰度」的计划是写不出来的</b>，因为开关不支持。
 * 这个测试钉的就是新加的那个能力，以及它的前提：不配覆盖时什么都不变。
 */
class StagedSessionCutoverTest {

    /** 分批切换的中间态：运营端进库，C 端与 B 端不动。 */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            // 独立库名：@TestPropertySource 会另起一个上下文，共享 h2:mem:shop 会撞主键
            "spring.datasource.url=jdbc:h2:mem:staged;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "shop.auth.token-store=memory",
            "shop.auth.token-store-by-realm.operator=db",
    })
    @DisplayName("只切运营端")
    class OperatorOnly {

        @Autowired
        TokenStore tokenStore;

        @Test
        @DisplayName("★★ 运营端进库，另外两端仍用原来的共享存储")
        void onlyOperatorGoesToDb() {
            assertThat(tokenStore).isInstanceOf(RealmRoutingTokenStore.class);
            RealmRoutingTokenStore routing = (RealmRoutingTokenStore) tokenStore;

            assertThat(routing.of(Realm.OPERATOR))
                    .as("配了 operator=db，这一端必须进库")
                    .isInstanceOf(DbTokenStore.class);
            assertThat(routing.of(Realm.CONSUMER))
                    .as("C 端没配覆盖，必须还是原来那个共享存储 —— 否则就是全量用户被一起切了")
                    .isInstanceOf(MemoryTokenStore.class);
            assertThat(routing.of(Realm.MERCHANT))
                    .isInstanceOf(MemoryTokenStore.class);
        }

        @Test
        @DisplayName("★ 没切的两端共用同一个实例 —— 不是各拿一份拷贝")
        void unswitchedRealmsShareOneStore() {
            RealmRoutingTokenStore routing = (RealmRoutingTokenStore) tokenStore;
            assertThat(routing.of(Realm.CONSUMER))
                    .as("各一份的话，C 端发的令牌 B 端认不出，而两端此刻共用 ctk_ 池")
                    .isSameAs(routing.of(Realm.MERCHANT));
        }
    }

    /** 什么都不配：**装配路径必须与从前逐字节相同**。这是鉴权，不能顺手重构。 */
    @Nested
    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "spring.datasource.url=jdbc:h2:mem:staged-none;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "shop.auth.token-store=memory",
    })
    @DisplayName("不配覆盖")
    class NoOverride {

        @Autowired
        ApplicationContext ctx;

        @Autowired
        TokenStore tokenStore;

        @Test
        @DisplayName("★★ 一个路由层都不该出现 —— 多包一层就是给鉴权加了一条没人测过的路径")
        void noRoutingLayerAtAll() {
            assertThat(tokenStore).isInstanceOf(MemoryTokenStore.class);
            assertThat(ctx.getBeanNamesForType(RealmRoutingTokenStore.class))
                    .as("没有任何一端配了 db，DbSessionConfig 整个都不该装")
                    .isEmpty();
        }
    }
}
