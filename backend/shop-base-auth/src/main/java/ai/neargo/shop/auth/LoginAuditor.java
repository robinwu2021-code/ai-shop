package ai.neargo.shop.auth;

/**
 * 登录审计入口：**成功与失败都从这里走**。
 *
 * <h2>为什么成功也要显式调，而不是在 TokenStore 里自动落</h2>
 * <p>原先的分工是「成功与登出在 {@code DbTokenStore} 的签发/撤销处自动落」——
 * 那句话只在 {@code shop.auth.token-store=db} 时成立。而<b>生产此刻走的是
 * {@code ehcache}</b>，那条自动路径根本不存在：三张登录日志表建好了、迁移也上了，
 * 却只可能收到失败记录，「谁在什么时候登录了」在生产查不到 ——
 * 而那恰恰是做这三张表的头号理由。
 *
 * <p>所以 {@code LOGIN} 与 {@code LOGIN_FAILED} 一起挪到业务层：
 * <b>登录这件事发生在业务层，与会话存在哪里无关</b>。切到 db 之后也不必回改，
 * 更不会重复写 —— {@code DbTokenStore} 不再写 {@code LOGIN}。
 *
 * <p>留在存储层的是 {@code LOGOUT} / {@code REVOKED} / {@code ORPHAN_SESSION}：
 * 那三件事本身就是会话表上的事件，业务层没有可靠的时机去记。
 * <b>代价说清楚：切 db 之前，这三类在生产同样是空的。</b>
 *
 * <h2>它不是控制平面</h2>
 * 「失败 N 次锁定账号」由 {@code RateLimiter} 做（密码尝试 15 分钟 10 次），
 * 与这里无关。审计可以丢、可以异步、可以采样，控制平面不行 ——
 * 合在一起等于让安全策略依赖一条允许丢失的写入。
 */
public interface LoginAuditor {

    /** 什么都不做的实现：没装配时用（注意它不再是 lambda —— 接口有两个方法了）。 */
    LoginAuditor NONE = new LoginAuditor() {
        @Override
        public void failed(Realm realm, String principal, String reason) {
        }

        @Override
        public void succeeded(Realm realm, String userNo) {
        }
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
     * 登录成功。
     *
     * @param userNo 记的是<b>账号号</b>而不是登录名：登录名可能是手机号（PII），
     *               而号是稳定的、可关联的、本来就到处在传。
     *               失败那一侧只能记登录名（那时还不知道是谁），所以两边的
     *               {@code user_no} 列含义不同 —— <b>这是刻意的</b>，
     *               成功看得出「是谁」，失败看得出「谁在试」。
     */
    void succeeded(Realm realm, String userNo);

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
