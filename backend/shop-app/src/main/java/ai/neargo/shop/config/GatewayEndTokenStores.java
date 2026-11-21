package ai.neargo.shop.config;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.store.RedisTokenStore;
import ai.neargo.shop.gateway.core.end.GatewayEndRuntimes;
import ai.neargo.shop.gateway.core.end.SessionStoreKinds;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/**
 * 一端有自己的 Redis 或数据源时，会话存储用它自己的那一份（ADR-061；开发计划 M3）。
 * 没配、或只装了会话装配而没装网关组件的切片测试里，一律落回调用方给的共用那一份。
 */
final class GatewayEndTokenStores {

    private final GatewayEndRuntimes runtimes;
    private final ObjectMapper mapper;
    private final Environment env;

    GatewayEndTokenStores(GatewayEndRuntimes runtimes, ObjectMapper mapper, Environment env) {
        this.runtimes = runtimes;
        this.mapper = mapper;
        this.env = env;
    }

    JdbcClient jdbc(Realm realm, JdbcClient shared) {
        return runtimes == null ? shared : runtimes.jdbc(realm).orElse(shared);
    }

    /** 这一端的会话配的是 Redis，且它有自己的 Redis 时，给一个用自己连接与键前缀的存储。 */
    Optional<TokenStore> redisStore(Realm realm) {
        if (runtimes == null || mapper == null || !SessionStoreKinds.REDIS.equals(SessionStoreKinds.kindOf(env, realm))) {
            return Optional.empty();
        }
        return runtimes.redis(realm).map(access ->
                new RedisTokenStore(access.template(), mapper, TokenStoreConfig.TTL, access.keyPrefix()));
    }
}
