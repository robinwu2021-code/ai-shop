package ai.neargo.shop.auth.store;

import ai.neargo.shop.auth.TokenStore;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis 会话（生产形态）。存 JSON 而不是 Java 序列化：会话结构会随权限模型演进，
 * Java 序列化会让「改一个字段 = 所有在线用户掉线」。
 */
public class RedisTokenStore implements TokenStore {

    private static final String KEY_PREFIX = "shop:session:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
    }

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        write(token, data);
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        String json = redis.opsForValue().get(KEY_PREFIX + token);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, SessionData.class));
        } catch (Exception e) {
            // 结构不兼容（多半是刚发版改了会话字段）：当作会话失效，让用户重登，而不是 500
            redis.delete(KEY_PREFIX + token);
            return Optional.empty();
        }
    }

    @Override
    public void refresh(String token, SessionData data) {
        write(token, data);
    }

    @Override
    public void revoke(String token) {
        redis.delete(KEY_PREFIX + token);
    }

    private void write(String token, SessionData data) {
        try {
            redis.opsForValue().set(KEY_PREFIX + token, mapper.writeValueAsString(data), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("session serialize failed", e);
        }
    }
}
