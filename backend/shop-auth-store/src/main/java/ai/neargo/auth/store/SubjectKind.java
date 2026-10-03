package ai.neargo.auth.store;

/**
 * 会话主体的 id <b>属于哪张表</b>。
 *
 * <h2>它与 {@link Realm} 是两件事</h2>
 * <p>{@code Realm}（在 shop-base）回答「这是哪个端的令牌」（决定前缀与会话表）；
 * {@code SubjectKind} 回答「{@code user_no} 这一列里的号该去哪张表查」。
 *
 * <p><b>它住在会话存储这一层而不是 shop-base</b>：{@code IdentityLoader} 要按它分发，
 * 而那个 SPI 在这里；放在 shop-base 的话这一层看不见它，只能退回传字符串 ——
 * 又变成约定。
 * 二者此前挤在 {@code LoginUser.realm} 一个字段里，而 B 端把它们撑开了：
 *
 * <ul>
 *   <li>店员从 {@code /biz/auth/staff-login} 进来 —— 主体是
 *       {@code mch_account.mch_account_no}（{@link #MCH}）</li>
 *   <li>老板、以及<b>还不是商家的人</b>从 {@code /biz/auth/login} 进来 ——
 *       主体是 {@code usr_account.user_no}（{@link #USR}）</li>
 * </ul>
 *
 * <p>两种都得认：生产实测 9 个商家账号里 <b>8 个只存在于 B 端</b>、没有
 * {@code usr_account}，所以「统一用 user_no」行不通；而还没开店的人没有
 * {@code mch_account_no}，「统一用它」同样行不通。
 *
 * <h2>为什么不靠号段区分</h2>
 * <p>今天 {@code SF-M0001} 与 {@code U2026…} 形状不同、零撞号，但那是数据长成这样，
 * 不是任何生成器保证的 —— {@code MerchantTokenAuthFilter} 自己的注释就写着
 * 「<b>那是约定不是结构保证</b>」。而在鉴权里撞号意味着<b>把会话解析成另一个人</b>，
 * 是最坏的一类错误：它不报错，只是让人看见别人的数据。
 */
public enum SubjectKind {

    /** {@code usr_account.user_no} —— 消费者，以及还没开店的人。 */
    USR,

    /** {@code mch_account.mch_account_no} —— 商家账号（含没有 C 端账号的店员）。 */
    MCH,

    /** {@code sys_ops_staff.staff_no} —— 平台运营。 */
    OPS
}
