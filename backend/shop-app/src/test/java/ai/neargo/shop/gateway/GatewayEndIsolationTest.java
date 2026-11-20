package ai.neargo.shop.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.store.RedisTokenStore;
import ai.neargo.shop.gateway.core.end.GatewayEndRuntimes;
import ai.neargo.shop.gateway.core.end.GatewayIsolationCheck;
import ai.neargo.shop.gateway.core.end.GatewayIsolationCheck.EndTarget;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.ObjectMapper;

/** 三端的会话有没有落在同一处，以及一端自己的 Redis 用自己的键前缀（ADR-061；开发计划 M3）。 */
class GatewayEndIsolationTest {

    @Test
    @DisplayName("只比用同一种存储的两端：都用 Redis 且都没配自己的，就是同一处")
    void overlapsOnlyAmongSameKind() {
        assertThat(GatewayIsolationCheck.overlaps(List.of(
                new EndTarget("mp", "redis", null), new EndTarget("biz", "redis", null),
                new EndTarget("ops", "db", null))))
                .hasSize(1).first().asString().contains("mp").contains("biz");
        assertThat(GatewayIsolationCheck.overlaps(List.of(
                new EndTarget("mp", "redis", "a:6379/0"), new EndTarget("biz", "redis", "b:6379/0"),
                new EndTarget("ops", "db", null))))
                .isEmpty();
        assertThat(GatewayIsolationCheck.overlaps(List.of(
                new EndTarget("mp", "memory", null), new EndTarget("biz", "memory", null),
                new EndTarget("ops", "ehcache", null))))
                .as("进程内的存储不比").isEmpty();
    }

    @Test
    @DisplayName("★★★ strict：两端的 Redis 指向同一处，启动失败并说清是哪两端")
    void strictFailsOnSharedRedis() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("shop.gateway.isolation", "strict")
                .withProperty("shop.auth.token-store", "redis")
                .withProperty("shop.gateway.mp.redis.host", "10.0.0.1")
                .withProperty("shop.gateway.biz.redis.host", "10.0.0.1")
                .withProperty("shop.gateway.ops.redis.host", "10.0.0.3");
        assertThatThrownBy(() -> new GatewayEndRuntimes(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mp").hasMessageContaining("biz").hasMessageContaining("10.0.0.1");
    }

    @Test
    @DisplayName("strict：三端各有自己的 Redis 就能起；relaxed（默认）共用也能起")
    void strictPassesWhenSeparatedAndRelaxedAllowsSharing() throws Exception {
        MockEnvironment separated = new MockEnvironment()
                .withProperty("shop.gateway.isolation", "strict")
                .withProperty("shop.auth.token-store", "redis")
                .withProperty("shop.gateway.mp.redis.host", "10.0.0.1")
                .withProperty("shop.gateway.biz.redis.host", "10.0.0.2")
                .withProperty("shop.gateway.ops.redis.host", "10.0.0.3");
        GatewayEndRuntimes runtimes = new GatewayEndRuntimes(separated);
        try {
            assertThat(runtimes.redis(Realm.OPERATOR)).isPresent();
            assertThat(runtimes.redis(Realm.OPERATOR).get().keyPrefix()).isEqualTo("gw:ops:");
            assertThat(runtimes.jdbc(Realm.OPERATOR)).as("没配数据源就没有，调用方用主库").isEmpty();
        } finally {
            runtimes.destroy();
        }

        GatewayEndRuntimes relaxed = new GatewayEndRuntimes(
                new MockEnvironment().withProperty("shop.auth.token-store", "redis"));
        assertThat(relaxed.redis(Realm.CONSUMER)).as("什么都没配：不建任何连接，行为与之前相同").isEmpty();
        relaxed.destroy();
    }

    @Test
    @DisplayName("★★ 一端自己的 Redis 用自己的键前缀；不给前缀时仍是原来的 shop:session:")
    void redisStoreUsesGivenKeyPrefix() {
        assertThat(keyWrittenBy("gw:ops:")).startsWith("gw:ops:otk_");
        assertThat(keyWrittenBy(null)).startsWith("shop:session:otk_");
    }

    @SuppressWarnings("unchecked")
    private static String keyWrittenBy(String prefix) {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = Mockito.mock(ValueOperations.class);
        SetOperations<String, String> sets = Mockito.mock(SetOperations.class);
        Mockito.when(redis.opsForValue()).thenReturn(values);
        Mockito.when(redis.opsForSet()).thenReturn(sets);
        ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        RedisTokenStore store = prefix == null
                ? new RedisTokenStore(redis, mapper, Duration.ofMinutes(5))
                : new RedisTokenStore(redis, mapper, Duration.ofMinutes(5), prefix);
        store.issue(TokenStore.SessionData.of(LoginUser.operator("S1", "s", List.of(), List.of())));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        Mockito.verify(values).set(key.capture(), Mockito.anyString(), Mockito.any(Duration.class));
        return key.getValue();
    }
}
