package ai.neargo.shop.auth;

import java.util.Map;
import java.util.Optional;

/**
 * 按池分发的会话存储门面。**装配上去之后，业务代码注入的还是 {@link TokenStore}，
 * 不必知道有三个池。**
 *
 * <p>分发依据：
 * <ul>
 *   <li>{@link #issue} —— 会话里的 {@code realm}</li>
 *   <li>{@link #get} / {@link #refresh} / {@link #revoke} —— <b>令牌前缀</b>
 *       （{@link Realm#ofToken}）。前缀不认识就当作没有这个会话，
 *       连库都不查 —— 这是端隔离最便宜的一道</li>
 *   <li>{@link #revokeUser} —— <b>推不出来，直接拒绝</b>，见 {@link TokenStores}</li>
 * </ul>
 */
public class RealmRoutingTokenStore implements TokenStore, TokenStores {

    private final Map<Realm, TokenStore> byRealm;

    public RealmRoutingTokenStore(Map<Realm, TokenStore> byRealm) {
        for (Realm r : Realm.values()) {
            if (!byRealm.containsKey(r)) {
                // 少配一个池的后果是那一端**全员登不上**，而错误信息会指向别处。
                // 启动时炸掉，比上线后查半天强
                throw new IllegalArgumentException("没有为 " + r + " 配置会话存储");
            }
        }
        this.byRealm = Map.copyOf(byRealm);
    }

    @Override
    public TokenStore of(Realm realm) {
        return byRealm.get(realm);
    }

    @Override
    public String issue(SessionData data) {
        return byRealm.get(data.user().realm()).issue(data);
    }

    @Override
    public Optional<SessionData> get(String token) {
        Realm realm = Realm.ofToken(token);
        return realm == null ? Optional.empty() : byRealm.get(realm).get(token);
    }

    @Override
    public void refresh(String token, SessionData data) {
        Realm realm = Realm.ofToken(token);
        if (realm != null) {
            byRealm.get(realm).refresh(token, data);
        }
    }

    @Override
    public void revoke(String token) {
        Realm realm = Realm.ofToken(token);
        if (realm != null) {
            byRealm.get(realm).revoke(token);
        }
    }

    /**
     * <b>不支持。</b>主体号不带池信息，推不出该在哪个池里踢。
     *
     * <p>「在所有池里都踢一遍」看似无害（主体号跨池不会撞），
     * 但那正是本次改造要消灭的假设 —— 一旦哪天真撞了，
     * 停用一个消费者会顺手踢掉同号的运营账号，而没有任何地方会说这件事发生过。
     */
    @Override
    public int revokeUser(String userNo) {
        throw new UnsupportedOperationException(
                "分池之后必须指明是哪个池：tokenStores.of(Realm.X).revokeUser(userNo)");
    }
}
