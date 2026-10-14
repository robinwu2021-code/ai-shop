package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 登录凭证。一个人多条（{@code user_no} : 本表 = 1 : N）。
 *
 * <p><b>为什么要有这张表</b>：凭证平铺在 {@code usr_account} 上时，一个账号只能有一个
 * openid，而微信 openid 是<b>按应用隔离</b>的——同一个人在小程序、公众号、App 里是三个
 * 不同的 openid。旧结构根本存不下「同一个人从小程序和 App 都登录过」这个事实，
 * 于是他会变成两个账号，订单、积分、卡包全部分裂，且不报任何错。
 *
 * <p>新增一种登录来源，在这里只是多一行数据，不再需要改表。
 *
 * @see IdentityType 凭证类型与识别强度
 */
@Getter
@Setter
@TableName("usr_identity")
public class UsrIdentity extends BaseEntity {

    private String userNo;

    /** 见 {@link IdentityType}。 */
    private String identityType;

    /** 凭证值：手机号 / openid / unionid / Apple sub。 */
    private String identityValue;

    /** 来源留痕：{@code MP} / {@code APP} / {@code H5}。用于排查「这个人是从哪进来的」。 */
    private String channel;

    /** 验证时间。手机号走 OTP 通过即记；微信与 Apple 的凭证由平台背书，登录即视为已验证。 */
    private java.time.LocalDateTime verifiedAt;
}
