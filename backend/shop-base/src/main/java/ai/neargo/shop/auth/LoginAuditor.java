package ai.neargo.shop.auth;

/**
 * 登录失败的审计入口。**成功与登出不用调它** —— 那两件事在 {@code DbTokenStore}
 * 的签发/撤销处自动落，业务代码碰不到。
 *
 * <p>失败落不到那里：登录失败根本走不到签发。所以只有这一类需要业务侧显式记一笔，
 * 而它恰恰是最该记的 —— <b>登录是最容易被刷的接口之一，失败日志是被刷时唯一的证据</b>。
 *
 * <h2>它不是控制平面</h2>
 * 「失败 N 次锁定账号」由 {@code RateLimiter} 做（密码尝试 15 分钟 10 次），
 * 与这里无关。审计可以丢、可以异步、可以采样，控制平面不行 ——
 * 合在一起等于让安全策略依赖一条允许丢失的写入。
 */
public interface LoginAuditor {

    /** 什么都不做的实现：{@code token-store} 之外的形态、或测试里没装配时用。 */
    LoginAuditor NONE = (realm, principal, reason) -> {
    };

    /**
     * @param principal 谁在登录。**必须是脱敏后的**（手机号打码）——
     *                  这张表将来会被多方读，而完整号码不该在审计里躺着
     * @param reason    失败原因，用错误码而不是给用户看的那句话：
     *                  「密码错误」与「账号被停用」在排查时是两件事，
     *                  而它们给用户的提示常常是同一句
     */
    void failed(Realm realm, String principal, String reason);

    /**
     * 手机号打码。**审计里不留完整号码** —— 这张表将来会被多方读，
     * 而「他上次从哪个号试的」用打码的号一样看得出来。
     *
     * <p>不是手机号（用户名、openid）就原样留：那些本来就不是 PII，
     * 而打码会让「谁在被撞库」变得看不出来。
     */
    static String maskPrincipal(String principal) {
        if (principal == null || principal.isBlank()) {
            return null;
        }
        String p = principal.trim();
        return p.length() == 11 && p.chars().allMatch(Character::isDigit)
                ? p.substring(0, 3) + "****" + p.substring(7)
                : p;
    }
}
