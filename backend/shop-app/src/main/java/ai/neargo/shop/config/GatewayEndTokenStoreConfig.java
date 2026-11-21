package ai.neargo.shop.config;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.RealmRoutingTokenStore;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.gateway.core.end.GatewayEndRuntimes;
import ai.neargo.shop.gateway.core.end.SessionStoreKinds;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import tools.jackson.databind.ObjectMapper;

/**
 * 没有任何一端的会话落库、但有一端配了自己的 Redis 时，按端分发会话存储（ADR-061；开发计划 M3）。
 *
 * <p>有端落库时这件事由 {@link DbSessionConfig} 一并做了，本类不装；
 * 三端都没有自己的 Redis 时也不装，容器里仍然只有那一个共用存储，与之前完全相同。
 */
@Configuration
@Conditional(GatewayEndTokenStoreConfig.DedicatedRedisWithoutDb.class)
public class GatewayEndTokenStoreConfig {

    @Bean
    @Primary
    RealmRoutingTokenStore gatewayEndRoutingTokenStore(
            @Qualifier(TokenStoreConfig.SHARED) TokenStore shared, GatewayEndRuntimes runtimes,
            ObjectMapper mapper, Environment env) {
        GatewayEndTokenStores dedicated = new GatewayEndTokenStores(runtimes, mapper, env);
        Map<Realm, TokenStore> byRealm = new EnumMap<>(Realm.class);
        for (Realm realm : Realm.values()) {
            byRealm.put(realm, dedicated.redisStore(realm).orElse(shared));
        }
        return new RealmRoutingTokenStore(byRealm);
    }

    static class DedicatedRedisWithoutDb implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            Environment env = context.getEnvironment();
            boolean anyDedicated = false;
            for (Realm realm : Realm.values()) {
                if (TokenStoreSelection.usesDb(env, realm)) {
                    return false;
                }
                anyDedicated |= SessionStoreKinds.hasDedicatedRedis(env, realm);
            }
            return anyDedicated;
        }
    }
}
