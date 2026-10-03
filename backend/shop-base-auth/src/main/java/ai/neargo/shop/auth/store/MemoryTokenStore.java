package ai.neargo.shop.auth.store;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.TokenStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存会话（开发/测试默认）。**多实例部署下不可用** —— 换实例即掉线，
 * 因此生产必须 {@code shop.auth.token-store=redis}，由启动期配置校验拦下误配。
 */
public class MemoryTokenStore implements TokenStore {

    private record Entry(SessionData data, Instant expireAt) {
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();
    private final Duration ttl;

    public MemoryTokenStore(Duration ttl) {
        this.ttl = ttl;
    }

    @Override
    public String issue(SessionData data) {
        String token = TokenStore.newToken(data.user().realm());
        store.put(token, new Entry(data, Instant.now().plus(ttl)));
        return token;
    }

    @Override
    public int revokeUser(String userNo) {
        if (userNo == null || userNo.isBlank()) {
            return 0;
        }
        // 全表扫。会话量是「在线运营人数」量级，几十条 —— 为它建二级索引
        // 反而多一份要维护一致的状态
        int[] n = {0};
        store.entrySet().removeIf(e -> {
            boolean hit = userNo.equals(e.getValue().data().user().userNo());
            if (hit) {
                n[0]++;
            }
            return hit;
        });
        return n[0];
    }

    @Override
    public Optional<SessionData> get(String token) {
        Entry e = store.get(token);
        if (e == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(e.expireAt())) {
            store.remove(token);
            return Optional.empty();
        }
        return Optional.of(e.data());
    }

    @Override
    public void refresh(String token, SessionData data) {
        store.computeIfPresent(token, (k, old) -> new Entry(data, Instant.now().plus(ttl)));
    }

    @Override
    public void revoke(String token) {
        store.remove(token);
    }

    /** 测试便利：直接发一个 C 端会话。 */
    public String issueConsumer(String userNo) {
        return issue(SessionData.of(LoginUser.consumer(userNo, userNo)));
    }
}
