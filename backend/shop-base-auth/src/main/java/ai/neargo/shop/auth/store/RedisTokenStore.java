package ai.neargo.shop.auth.store;

import ai.neargo.shop.auth.TokenStore;
import tools.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Set;
import java.util.Optional;

/**
 * Redis 会话（生产形态）。存 JSON 而不是 Java 序列化：会话结构会随权限模型演进，
 * Java 序列化会让「改一个字段 = 所有在线用户掉线」。
 */
public class RedisTokenStore implements TokenStore {

    private static final String KEY_PREFIX = "shop:session:";

    /** userNo → token 集合。停用账号要能 O(1) 找到他的全部会话 */
    private static final String USER_INDEX_PREFIX = "shop:session:user:";

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
        // 反向索引与会话同生命周期：不设过期的话，索引会无限增长，
        // 且里面全是早已失效的 token
        String idx = USER_INDEX_PREFIX + data.user().userNo();
        redis.opsForSet().add(idx, token);
        redis.expire(idx, ttl);
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
    public int revokeUser(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return 0;
        }
        /*
         * 反向索引：userNo → 它的 token 集合。
         *
         * 不用 SCAN 遍历 shop:session:*：那在生产上会随会话量线性变慢，
         * 而停用账号恰恰是出事时最需要立刻见效的操作。
         */
        String idx = USER_INDEX_PREFIX + userNo;
        Set<String> tokens = redis.opsForSet().members(idx);
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        redis.delete(tokens.stream().map(t -> KEY_PREFIX + t).toList());
        redis.delete(idx);
        return tokens.size();
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
