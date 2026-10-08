package ai.neargo.shop.spi.user;

import java.util.Optional;

/**
 * user → merchant：按**店员登录手机号**签发一个 B 端会话。
 *
 * <p>给「C 端免登录切商家端」用（见 TDD-C端免登录切商家端）。店主那一支靠
 * {@code mch_account.user_no} 直接关联 C 端账号，而<b>店员行往往没有 user_no</b>
 * —— 店员是店主在后台录入手机号加进来的，他自己未必在 C 端注册过同一个身份。
 * 所以店员只能按手机号认：拿当前 C 端用户<b>本人已验证的手机号</b>去匹配
 * {@code mch_account.login_phone}。
 *
 * <h2>为什么不并进 {@link StaffLoginPhonePort}</h2>
 *
 * <p>那个接口刻意只回一个布尔、「不回是哪个账号」，为的是不让调用方靠它<b>枚举</b>
 * 某个手机号是不是商家。签发会话是另一回事，塞进去会把那条边界破掉，所以单开一个。
 *
 * <h2>安全边界</h2>
 *
 * <p>调用方必须传<b>当前登录用户本人的手机号</b>（从 user_no 反查，不是请求里带来的），
 * 否则就是「报上任意手机号即可登进那个人的店」。手机号在 C 端是验过码才绑上的，
 * 这一点保证了「号是他的」。
 *
 * <p>另外必须是**完整号**，不能是脱敏号：拿 {@code 185****8359} 去
 * {@code where login_phone = ?} 永远查不到，而表现是「店员切过去变成不是商家」——
 * 不报错，只是他的店不见了（{@code /biz/auth/login} 的注释里记过同一个坑）。
 */
public interface StaffSessionPort {

    /**
     * 没有实现时的兜底：谁都不是店员。
     *
     * <p><b>fail-closed</b> —— 宁可店员暂时切不过去，也不要因为实现没接上而放行。
     */
    StaffSessionPort NONE = phone -> Optional.empty();

    /**
     * 按店员登录手机号签发 B 端会话令牌。
     *
     * @param phone 当前登录用户本人的**完整**手机号，见类注释「安全边界」
     * @return 命中 ACTIVE 店员账号时给出 {@code btk_} 令牌；没命中返回空
     */
    Optional<String> issueStaffSession(String phone);

    /**
     * 这个手机号<b>现在</b>能不能切到商家端 —— 只问，不签会话。
     *
     * <p>给「我的」那一页判显示哪个入口用：是店员就给「商家运营」，不是就给「我也想开店」。
     *
     * <p><b>判据必须与 {@link #issueStaffSession} 完全一致（只认 ACTIVE）。</b>
     * 松一档（比如把停用的也算上）的后果不是多显示一个入口，而是
     * <b>点进去报「你还不是商家」</b> —— 页面刚告诉他他是。
     * 两个判据写在两处就迟早会分家，所以实现要走同一个查询条件。
     *
     * <p>与 {@link StaffLoginPhonePort#isStaffLoginPhone} 也不是一回事：那个<b>含已停用</b>，
     * 问的是「这个号能不能被录进白名单」，口径更宽，拿来判身份会多放行一批人。
     *
     * <p><b>为什么是 default 而不是第二个抽象方法</b>：加上去的话这个接口就不再是
     * 函数式接口，{@link #NONE} 只能写成匿名类 —— 而匿名类是一个**具名的 Port 实现**
     * ({@code StaffSessionPort$1})，住在 spi 包里，架构闸「Port 实现集中在各域的 .port 包」
     * 当场变红（2026-10-08 真撞了一次）。与 {@code BizIdentityResolver} 同一个形状：
     * 单抽象方法 + default。
     *
     * <p>default 回 false 就是 {@link #NONE} 要的那个行为（fail-closed）。
     * <b>真实实现必须覆盖它</b> —— 不覆盖的症状是「所有店员都看不到商家运营入口」，
     * 方向上安全，但同样是错的。
     *
     * @param phone 当前登录用户本人的**完整**手机号，见类注释「安全边界」
     */
    default boolean hasActiveStaffAccount(String phone) {
        return false;
    }
}
