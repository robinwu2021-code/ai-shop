package ai.neargo.shop.auth;

import java.util.Optional;
import java.util.UUID;

/**
 * 会话存储 SPI（memory / redis 可切，配置 {@code shop.auth.token-store}）。
 * 登录与认证过滤器只依赖本接口，换后端零改业务代码。
 */
public interface TokenStore {

    /** 会话数据：主体 + 权限版本戳（运营端改了角色权限，靠它让在线会话失效重建）。 */
    record SessionData(LoginUser user, long permStamp) {
        public static SessionData of(LoginUser user) {
            return new SessionData(user, 0L);
        }
    }

    String issue(SessionData data);

    Optional<SessionData> get(String token);

    void refresh(String token, SessionData data);

    void revoke(String token);

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
     * 带池前缀的不透明 token。前缀不是装饰：拿 C 端 token 打 {@code /ops/**} 时，
     * 过滤器不用查库就能判定池不符，直接 401。
     */
    static String newToken(Realm realm) {
        String prefix = realm == Realm.OPERATOR ? "otk_" : "ctk_";
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
