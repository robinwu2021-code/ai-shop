package ai.neargo.shop.gateway.core.end;

import ai.neargo.shop.auth.Realm;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * 三端专属资源的健康状况，每端单独一项。
 *
 * <p>整体状态始终是 UP：一端的 Redis 挂了只该影响那一端，如果把整个实例报成 DOWN，
 * 负载均衡会把另外两端的流量也一起摘走。哪一端有问题看明细。
 */
@Component("gatewayEnds")
public class GatewayEndHealthIndicator implements HealthIndicator {

    private final GatewayEndRuntimes runtimes;

    public GatewayEndHealthIndicator(GatewayEndRuntimes runtimes) {
        this.runtimes = runtimes;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        for (Realm realm : Realm.values()) {
            String end = SessionStoreKinds.endOf(realm);
            health.withDetail(end + ".redis", runtimes.redis(realm).map(this::ping).orElse("共用"));
            health.withDetail(end + ".datasource", runtimes.jdbc(realm)
                    .map(jdbc -> probe(() -> jdbc.sql("SELECT 1").query(Integer.class).single())).orElse("主库"));
        }
        return health.build();
    }

    private String ping(GatewayEndRuntimes.RedisAccess access) {
        return probe(() -> access.template().execute(connection -> connection.ping(), true));
    }

    private static String probe(java.util.function.Supplier<Object> call) {
        try {
            call.get();
            return "UP";
        } catch (RuntimeException e) {
            return "DOWN: " + e.getClass().getSimpleName();
        }
    }
}
