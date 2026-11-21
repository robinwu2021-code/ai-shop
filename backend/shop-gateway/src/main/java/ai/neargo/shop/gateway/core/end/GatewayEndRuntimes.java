package ai.neargo.shop.gateway.core.end;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.gateway.core.config.GatewayEndProperties;
import ai.neargo.shop.gateway.core.config.GatewayProperties;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 三端各自的 Redis 与数据源（ADR-061；TDD-网关层与统一权限 §2.15）。
 *
 * <p>每端自己建连接，不用全局的那一份：一端把连接池打满或它的 Redis 挂了，另外两端不受影响。
 * 哪一端没配自己的，就没有那一份，调用方落回共用的 —— 所以默认配置下这里什么都不建，行为与之前相同。
 *
 * <p>不依赖「是不是 Web 应用」：会话存储在批量任务进程里同样要装，所以它是一个普通组件，
 * 配置直接从环境里绑定，不经过只在 Web 应用里才有的装配入口。
 */
@Component
public class GatewayEndRuntimes implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(GatewayEndRuntimes.class);
    private static final List<String> ENDS = List.of("mp", "biz", "ops");

    /** 一端自己的 Redis：连接与会话键前缀。 */
    public record RedisAccess(StringRedisTemplate template, String keyPrefix, String target) {
    }

    private final Map<Realm, RedisAccess> redis = new EnumMap<>(Realm.class);
    private final Map<Realm, JdbcClient> jdbc = new EnumMap<>(Realm.class);
    private final Map<Realm, String> jdbcTargets = new EnumMap<>(Realm.class);
    private final List<LettuceConnectionFactory> factories = new ArrayList<>();
    private final List<HikariDataSource> pools = new ArrayList<>();

    public GatewayEndRuntimes(Environment environment) {
        GatewayProperties properties = Binder.get(environment)
                .bind("shop.gateway", GatewayProperties.class).orElseGet(GatewayProperties::new);
        List<GatewayIsolationCheck.EndTarget> targets = new ArrayList<>();
        for (String end : ENDS) {
            Realm realm = SessionStoreKinds.realmOf(end);
            GatewayEndProperties mine = properties.end(end);
            if (mine.getRedis().configured()) {
                redis.put(realm, openRedis(end, mine.getRedis()));
            }
            if (mine.getDatasource().configured()) {
                jdbc.put(realm, openDatasource(end, mine.getDatasource()));
                jdbcTargets.put(realm, mine.getDatasource().getUrl());
            }
            String kind = SessionStoreKinds.kindOf(environment, realm);
            String target = SessionStoreKinds.REDIS.equals(kind)
                    ? Optional.ofNullable(redis.get(realm)).map(RedisAccess::target).orElse(null)
                    : SessionStoreKinds.DB.equals(kind) ? jdbcTargets.get(realm) : null;
            targets.add(new GatewayIsolationCheck.EndTarget(end, kind, target));
        }
        List<String> overlaps = GatewayIsolationCheck.overlaps(targets);
        if ("strict".equalsIgnoreCase(properties.getIsolation()) && !overlaps.isEmpty()) {
            throw new IllegalStateException("shop.gateway.isolation=strict，但三端的会话存储有重合：" + overlaps
                    + "。给每一端配自己的 shop.gateway.<端>.redis 或 .datasource，或改回 relaxed。");
        }
        if (!redis.isEmpty() || !jdbc.isEmpty()) {
            log.info("网关三端的专属资源：Redis {}，数据源 {}", redis.keySet(), jdbc.keySet());
        }
    }

    /** 这一端自己的 Redis；没配返回空。 */
    public Optional<RedisAccess> redis(Realm realm) {
        return Optional.ofNullable(redis.get(realm));
    }

    /** 这一端自己的数据源；没配返回空，调用方用主库。 */
    public Optional<JdbcClient> jdbc(Realm realm) {
        return Optional.ofNullable(jdbc.get(realm));
    }

    private RedisAccess openRedis(String end, GatewayEndProperties.Redis config) {
        RedisStandaloneConfiguration server = new RedisStandaloneConfiguration(config.getHost(), config.getPort());
        server.setDatabase(config.getDatabase());
        if (config.getPassword() != null && !config.getPassword().isBlank()) {
            server.setPassword(RedisPassword.of(config.getPassword()));
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory(server,
                LettuceClientConfiguration.builder().commandTimeout(config.getTimeout()).build());
        factory.afterPropertiesSet();
        factories.add(factory);
        String prefix = config.getKeyPrefix() == null || config.getKeyPrefix().isBlank()
                ? "gw:" + end + ":" : config.getKeyPrefix();
        String target = config.getHost() + ":" + config.getPort() + "/" + config.getDatabase();
        return new RedisAccess(new StringRedisTemplate(factory), prefix, target);
    }

    private JdbcClient openDatasource(String end, GatewayEndProperties.Datasource config) {
        HikariDataSource pool = new HikariDataSource();
        pool.setPoolName("gateway-" + end);
        pool.setJdbcUrl(config.getUrl());
        pool.setUsername(config.getUsername());
        pool.setPassword(config.getPassword());
        pool.setMaximumPoolSize(config.getMaxPoolSize());
        pools.add(pool);
        return JdbcClient.create(pool);
    }

    @Override
    public void destroy() {
        factories.forEach(LettuceConnectionFactory::destroy);
        pools.forEach(HikariDataSource::close);
    }
}
