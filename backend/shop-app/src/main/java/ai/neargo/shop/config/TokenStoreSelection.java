package ai.neargo.shop.config;

import ai.neargo.shop.auth.Realm;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.EnumMap;
import java.util.Map;

/**
 * 「哪一端的会话存在哪里」的唯一判定处。
 *
 * <h2>为什么需要按端选，而不是一个全局开关</h2>
 * <p>{@code shop.auth.token-store} 是<b>一个全局值</b>：从 {@code ehcache} 切到
 * {@code db}，三端的会话同时失效 —— 全部消费者、全部商家、全部运营在同一秒重新登录。
 * 而会话外置这件事本该分批做：先切运营端（十几个人，出问题影响可控），
 * 观察一天，再切 B 端，最后才是 C 端全量用户。
 *
 * <p><b>此前那个分批计划是写不出来的</b>，因为开关不支持。这里补上：
 * {@code shop.auth.token-store-by-realm.operator=db} 只切运营端，其余两端不动。
 *
 * <h2>不配任何覆盖时，装配路径与从前逐字节相同</h2>
 * <p>这是鉴权 —— 「顺手重构一下」的代价是全员登不上。所以覆盖为空且全局不是
 * {@code db} 时，{@code DbSessionConfig} 整个不装，容器里还是那一个
 * {@code TokenStore} bean，没有任何路由层。
 */
final class TokenStoreSelection {

    static final String GLOBAL = "shop.auth.token-store";
    static final String BY_REALM = "shop.auth.token-store-by-realm.";
    static final String DB = "db";

    private TokenStoreSelection() {
    }

    /** 这一端用哪种存储。按端的值优先，没配就用全局值。 */
    static String kindOf(Environment env, Realm realm) {
        String per = env.getProperty(BY_REALM + realm.name().toLowerCase());
        if (per != null && !per.isBlank()) {
            return per.trim();
        }
        // 与 TokenStoreConfig 的 matchIfMissing=true 保持一致：默认 memory
        return env.getProperty(GLOBAL, "memory").trim();
    }

    static Map<Realm, String> all(Environment env) {
        Map<Realm, String> m = new EnumMap<>(Realm.class);
        for (Realm r : Realm.values()) {
            m.put(r, kindOf(env, r));
        }
        return m;
    }

    static boolean usesDb(Environment env, Realm realm) {
        return DB.equals(kindOf(env, realm));
    }

    /**
     * 只要有<b>任意一端</b>要进库，{@code DbSessionConfig} 就得装 ——
     * 哪怕另外两端还在 ehcache 上。这正是分批切换的形态。
     */
    static class AnyRealmUsesDb implements Condition {
        @Override
        public boolean matches(ConditionContext ctx, AnnotatedTypeMetadata metadata) {
            Environment env = ctx.getEnvironment();
            for (Realm r : Realm.values()) {
                if (usesDb(env, r)) {
                    return true;
                }
            }
            return false;
        }
    }
}
