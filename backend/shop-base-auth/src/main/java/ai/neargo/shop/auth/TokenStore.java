package ai.neargo.shop.auth;

import java.util.Optional;
import java.util.UUID;

/**
 * 会话存储 SPI（memory / redis 可切，配置 {@code shop.auth.token-store}）。
 * 登录与认证过滤器只依赖本接口，换后端零改业务代码。
 */
public interface TokenStore {

    /**
     * 会话数据。
     *
     * <p>曾经带一个 {@code permStamp}（权限版本戳），设想是「改了角色权限就让在线会话
     * 失效重建」。它**从未被写入过非 0 值，也从未被读过** —— 方案后来走了两条别的路：
     * 停用/改角色时 {@link #revokeUser} 直接踢会话，判权本身则改成由
     * {@code LivePermResolver} 现算（2026-08-12）。
     *
     * <p>删掉它而不是留着：留着的话，下一个人很可能把它「实现完」，
     * 于是权限新鲜度有了第二个真源，与现算那条并存 —— 而两个真源迟早对不上。
     */
    record SessionData(LoginUser user, String openid) {
        /** 不带 openid 的会话（运营端、商家端，以及没有小程序身份的 C 端会话） */
        public SessionData(LoginUser user) {
            this(user, null);
        }

        public static SessionData of(LoginUser user) {
            return new SessionData(user, null);
        }

        /**
         * 带上小程序 openid 的 C 端会话。openid 是登录那一刻服务端从微信换来的付款人身份，
         * 跟着会话走、随会话撤销而失效（新库并进 {@code usr_session.openid}，TDD-平台基础库换表 第 2 步 H）。
         * 放在会话数据里而不是另存一张表：内存、Redis、Ehcache 各种存储都原样带着它，库存储写进会话行。
         */
        public static SessionData of(LoginUser user, String openid) {
            return new SessionData(user, openid == null || openid.isBlank() ? null : openid);
        }
    }

    String issue(SessionData data);

    Optional<SessionData> get(String token);

    void refresh(String token, SessionData data);

    void revoke(String token);

    /** Hash-only switch tickets never persist bearer credentials. Explicit pool required. */
    default Optional<SessionData> getByHash(String hash, String principal) {
        throw new UnsupportedOperationException("Hash lookup requires a concrete session pool");
    }

    default void revokeByHash(String hash, String principal) {
        throw new UnsupportedOperationException("Hash revocation requires a concrete session pool");
    }


    /**
     * 踢掉某个主体的**全部**在线会话。
     *
     * <p>停用一个账号时必须调它：只改库里的状态，已经登录的人在 token 过期之前
     * 照常操作 —— 而按下停用的那个人以为立刻生效了。
     * 前端契约里那句「停用后<b>立即</b>无法登录」，靠的就是这一步。
     *
     * <p>改角色与改数据域也调它：会话里带的是签发那一刻的 perms 与 scope，
     * 不重建的话新权限要等到下次登录才生效。
     *
     * @return 踢掉的会话数
     */
    int revokeUser(String userNo);

    /**
     * Revokes every session of {@code userNo} in this pool except {@code keepToken}.
     * Used after a credential change: the session that made the change stays valid.
     */
    default int revokeUserExcept(String userNo, String keepToken) {
        throw new UnsupportedOperationException("Selective revocation requires a concrete session pool");
    }

    /**
     * 带池前缀的不透明 token。前缀不是装饰：拿 C 端 token 打 {@code /ops/**} 时，
     * 过滤器不用查库就能判定池不符，直接 401。
     *
     * <p>前缀由 {@link Realm#tokenPrefix()} 给出 —— <b>那里是唯一的真源</b>。
     * 曾经这里写着 {@code realm == OPERATOR ? "otk_" : "ctk_"}，
     * 那个三元表达式在加第三个池时会**静默地**把新池归进 {@code ctk_}：
     * 编译过、测试过，只是两端从此共用一个池。
     */
    static String newToken(Realm realm) {
        return realm.tokenPrefix() + UUID.randomUUID().toString().replace("-", "");
    }
}
