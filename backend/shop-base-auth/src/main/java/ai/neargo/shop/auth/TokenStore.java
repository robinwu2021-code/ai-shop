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
    record SessionData(LoginUser user) {
        public static SessionData of(LoginUser user) {
            return new SessionData(user);
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
