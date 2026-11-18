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

    /** 默认键前缀。三端共用一个 Redis 时用它；某一端有自己的 Redis 时由构造参数给（ADR-061）。 */
    public static final String DEFAULT_KEY_PREFIX = "shop:session:";

    private final String keyPrefix;

    /** userNo → token 集合。停用账号要能 O(1) 找到他的全部会话 */
    private final String userIndexPrefix;

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;

    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl) {
        this(redis, mapper, ttl, DEFAULT_KEY_PREFIX);
    }

    /** @param keyPrefix 会话键的前缀，以冒号结尾；按用户索引的键在它后面再接 {@code user:} */
    public RedisTokenStore(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl, String keyPrefix) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
        this.keyPrefix = keyPrefix;
        this.userIndexPrefix = keyPrefix + "user:";
    }

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        write(token, data);
        // 反向索引与会话同生命周期：不设过期的话，索引会无限增长，
        // 且里面全是早已失效的 token
        String idx = userIndexPrefix + data.user().userNo();
        redis.opsForSet().add(idx, token);
        redis.expire(idx, ttl);
        return token;
    }

    @Override
    public Optional<SessionData> get(String token) {
        String json = redis.opsForValue().get(keyPrefix + token);
        if (json == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(json, SessionData.class));
        } catch (Exception e) {
            // 结构不兼容（多半是刚发版改了会话字段）：当作会话失效，让用户重登，而不是 500
            redis.delete(keyPrefix + token);
            return Optional.empty();
        }
    }

    @Override
    public void refresh(String token, SessionData data) {
        write(token, data);
    }

    @Override
    public int revokeUserExcept(String userNo, String keepToken) {
        if (userNo == null || userNo.isBlank()) {
            return 0;
        }
        String idx = userIndexPrefix + userNo;
        Set<String> tokens = redis.opsForSet().members(idx);
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        var victims = tokens.stream().filter(t -> !t.equals(keepToken)).toList();
        if (victims.isEmpty()) {
            return 0;
        }
        redis.delete(victims.stream().map(t -> keyPrefix + t).toList());
        redis.opsForSet().remove(idx, victims.toArray());
        return victims.size();
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
        String idx = userIndexPrefix + userNo;
        Set<String> tokens = redis.opsForSet().members(idx);
        if (tokens == null || tokens.isEmpty()) {
            return 0;
        }
        redis.delete(tokens.stream().map(t -> keyPrefix + t).toList());
        redis.delete(idx);
        return tokens.size();
    }

    private Optional<String> tokenByHash(String hash, String principal) {
        return java.util.Optional.ofNullable(redis.opsForSet().members(userIndexPrefix + principal)).orElse(java.util.Set.of()).stream()
                .filter(t -> ai.neargo.auth.store.TokenHash.of(t).equals(hash))
                .filter(t -> get(t).filter(d -> principal.equals(d.user().userNo())).isPresent()).findFirst();
    }

    @Override
    public Optional<SessionData> getByHash(String hash, String principal) {
        return tokenByHash(hash, principal).flatMap(this::get);
    }

    @Override
    public void revokeByHash(String hash, String principal) {
        tokenByHash(hash, principal).ifPresent(this::revoke);
    }

    @Override
    public void revoke(String token) {
        redis.delete(keyPrefix + token);
    }

    private void write(String token, SessionData data) {
        try {
            redis.opsForValue().set(keyPrefix + token, mapper.writeValueAsString(data), ttl);
        } catch (Exception e) {
            throw new IllegalStateException("session serialize failed", e);
        }
    }
}
