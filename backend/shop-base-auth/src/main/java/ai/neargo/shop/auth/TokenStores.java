package ai.neargo.shop.auth;

/**
 * 按端取会话存储。**三端分池之后，「哪个池」必须是显式的。**
 *
 * <p>大部分操作可以从令牌本身推出池（前缀）或从会话数据推出（{@code realm}），
 * 由 {@code RealmRoutingTokenStore} 自动分发。
 * <b>只有 {@link TokenStore#revokeUser} 推不出来</b> —— 它只有一个主体号，
 * 而主体号本身不带池信息。
 *
 * <p>那种情况下必须由调用方指明：停用一个运营账号就该只在运营池里踢。
 * 让它「在所有池里都踢一遍」看似无害（主体号跨池不会撞），
 * 但那正是本次改造要消灭的假设 —— <b>一旦哪天真撞了，停用一个消费者
 * 会顺手踢掉同号的运营账号，而没有任何地方会说这件事发生过。</b>
 */
public interface TokenStores {

    TokenStore of(Realm realm);

    /** 单池形态（memory / ehcache / redis）：三端共用一个存储，返回同一个。 */
    static TokenStores single(TokenStore store) {
        return realm -> store;
    }
}
