package ai.neargo.shop.config;

import ai.neargo.shop.auth.Realm;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 按端开关能不能<b>用环境变量</b>配。
 *
 * <h2>为什么单独钉这一条</h2>
 * <p>生产改配置走的是 {@code /opt/ai-shop/shop-app.env} 里的环境变量，不是 yml。
 * 而「写了一行环境变量，但没有任何代码在读它」是一种<b>零报错的失败</b> ——
 * 2026-08-28 早上刚踩过：{@code job.env} 里写着
 * {@code JOB_WORKER_START_DISABLED=true}，而 {@code application.yml} 里没有占位符
 * 引用它，那一行等于注释，新任务照样带着 enabled=1 进库。
 *
 * <p>会话切换比那个严重得多：配错了不是「多跑一个任务」，而是
 * <b>以为只切了运营端，实际三端全切</b> —— 全量用户在同一秒掉线。
 *
 * <p>所以这里不测「属性能不能读」（那是 Spring 的事），只测<b>那个具体的
 * 大写下划线名字</b>能不能落到 {@link TokenStoreSelection} 上。
 * 名字写在这里，运维照抄。
 */
class TokenStoreSelectionEnvTest {

    /** <b>生产要写进 shop-app.env 的就是这个名字。</b> */
    private static final String ENV_OPERATOR = "SHOP_AUTH_TOKEN_STORE_BY_REALM_OPERATOR";

    private static StandardEnvironment envWith(Map<String, Object> vars) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(
                new SystemEnvironmentPropertySource("systemEnvironment", vars));
        return env;
    }

    @Test
    @DisplayName("★★★ 大写下划线的环境变量必须能切单独一端 —— 名字写错就是零报错的失败")
    void envVarSwitchesOneRealmOnly() {
        var env = envWith(Map.of(
                "SHOP_AUTH_TOKEN_STORE", "ehcache",
                ENV_OPERATOR, "db"));

        assertEquals("db", TokenStoreSelection.kindOf(env, Realm.OPERATOR),
                ENV_OPERATOR + " 没被读到 —— 那一行就等于注释");
        assertEquals("ehcache", TokenStoreSelection.kindOf(env, Realm.CONSUMER),
                "C 端不该跟着动，否则就是全量用户一起掉线");
        assertEquals("ehcache", TokenStoreSelection.kindOf(env, Realm.MERCHANT));
    }

    @Test
    @DisplayName("★ 不配覆盖时三端都跟全局走")
    void withoutOverrideAllRealmsFollowGlobal() {
        var env = envWith(Map.of("SHOP_AUTH_TOKEN_STORE", "ehcache"));
        for (Realm r : Realm.values()) {
            assertEquals("ehcache", TokenStoreSelection.kindOf(env, r));
        }
    }

    @Test
    @DisplayName("★ 全局也没配时默认 memory —— 与 TokenStoreConfig 的 matchIfMissing 一致")
    void defaultsToMemory() {
        var env = envWith(Map.of());
        assertEquals("memory", TokenStoreSelection.kindOf(env, Realm.CONSUMER),
                "两处默认值对不上的话，容器里会同时出现两种存储的装配意图");
    }

    @Test
    @DisplayName("空串按没配处理 —— 运维把值删空但留着那一行是常见操作")
    void blankOverrideIsIgnored() {
        var env = envWith(Map.of(
                "SHOP_AUTH_TOKEN_STORE", "ehcache",
                ENV_OPERATOR, "   "));
        assertEquals("ehcache", TokenStoreSelection.kindOf(env, Realm.OPERATOR));
    }
}
