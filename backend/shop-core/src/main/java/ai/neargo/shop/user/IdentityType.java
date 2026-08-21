package ai.neargo.shop.user;

import java.util.List;

/**
 * 登录凭证类型，<b>按识别强度排列</b>。
 *
 * <p>登录时不是「只用一把钥匙开一把锁」，而是把本次能拿到的全部凭证按这个顺序
 * 依次去找人；命中之后把新出现的凭证补登到同一个人名下，识别能力越用越强。
 *
 * <p><b>手机号是唯一权威标识</b>（本项目的选择）：它跨微信生态、跨 App、跨 H5，
 * 且用户真实控制。三个 openid 互不相通，只能认出「同一应用的回访」。
 * unionid 仍然存但不参与认人——它是手机号缺失或换号时的修复线索，
 * 存它的边际成本是一行数据，不存则将来想补要回填历史用户。
 */
public final class IdentityType {

    /** 手机号。<b>唯一权威标识</b>，注册时强制获取。 */
    public static final String PHONE = "PHONE";

    /** 微信开放平台 unionid：同主体下所有微信应用通用。不参与认人，作修复线索。 */
    public static final String WX_UNIONID = "WX_UNIONID";

    /** 小程序 openid。作用域仅该小程序。 */
    public static final String WX_OPENID_MP = "WX_OPENID_MP";

    /** App 微信登录的 openid。作用域仅该 App，与小程序的**不是同一个值**。 */
    public static final String WX_OPENID_APP = "WX_OPENID_APP";

    /** 公众号网页授权的 openid（H5）。作用域仅该公众号。 */
    public static final String WX_OPENID_OA = "WX_OPENID_OA";

    /** Apple identityToken 的 sub。iOS 上架强制项，且**永远拿不到手机号**——
     *  所以 Apple 登录后若未关联账号，必须强制走手机号绑定，不能跳过。 */
    public static final String APPLE_SUB = "APPLE_SUB";

    /**
     * 登录密码（bcrypt 哈希存在 {@code identity_value} 里）。
     *
     * <p><b>它和上面几种不是一回事</b>：上面是「标识」——拿着它去找人；
     * 密码是「秘密」——先用手机号找到人，再验这一条对不对。所以它
     * <b>刻意不进 {@link #RESOLVE_ORDER}</b>：把哈希拿去认人既没有意义
     * （谁会拿哈希当账号输），又会让「两个人碰巧同哈希」变成串号事故。
     *
     * <p>存这里而不是往 {@code usr_account} 加一列，是本模型的既定选择
     * （见本类与 {@code AuthServiceImpl} 的类注释：凭证一人多条，不平铺成列）。
     */
    public static final String PASSWORD = "PASSWORD";

    /**
     * 认人时的尝试顺序。
     *
     * <p>手机号排第一是本项目的决定；其余按「同应用回访」处理，顺序之间无实质差别，
     * 但**必须是确定的顺序**——不确定的话，同一组凭证在并发下可能认到不同的人。
     *
     * <p>{@link #PASSWORD} 不在其中，理由见该常量的注释。
     */
    public static final List<String> RESOLVE_ORDER =
            List.of(PHONE, WX_UNIONID, WX_OPENID_MP, WX_OPENID_APP, WX_OPENID_OA, APPLE_SUB);

    private IdentityType() {
    }
}
