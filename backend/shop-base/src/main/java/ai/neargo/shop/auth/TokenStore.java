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
     * 带池前缀的不透明 token。前缀不是装饰：拿 C 端 token 打 {@code /ops/**} 时，
     * 过滤器不用查库就能判定池不符，直接 401。
     */
    static String newToken(Realm realm) {
        String prefix = realm == Realm.OPERATOR ? "otk_" : "ctk_";
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
