package ai.neargo.shop.user.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * C 端用户。
 *
 * <p><b>归属键 {@code communityNo} + {@code pickupNo} 直接挂在用户上</b>，而不是单独一张归属表：
 * 一期一个用户只有一个当前社区（切换是覆盖，不是并存），单独建表只会让每次读商品池多一次 join。
 * 将来要支持「同时属于公司和家两个社区」时再拆，那时是加表不是改表。
 *
 * <p>{@code entityNo} 是「我的常去店」（C-ST-09 进店归因写入），不是「我开的店」——
 * 后者在 {@link MchEntity#getOwnerUserNo()}。两个概念共用一个字段名会在 B 端权限上出大事。
 */
@Getter
@Setter
@TableName("usr_account")
public class UsrAccount extends BaseEntity {

    private String userNo;
    private String nickname;
    private String avatar;
    private String phone;

    /** 微信 openid（小程序）。 */
    private String openid;

    /** 微信 unionid：小程序与 App 是同一个人的唯一依据。 */
    private String unionid;

    /** Apple 登录标识（App 上架必须支持）。 */
    private String appleSub;

    private String communityNo;
    private String pickupNo;

    /**
     * 当前生效位置（{@code usr_address.address_id}）。**与 is_default 是两回事**：
     * 默认是「下单预填哪个收货人」，生效是「现在按哪儿看货」——
     * 给父母下单时切到父母家看货，而默认收货人仍是自己。
     */
    private String activeAddressId;

    /** 常去店（进店归因，C-ST-09/10）。 */
    private String entityNo;

    /** NORMAL / RISK_LIMITED / BANNED */
    private String status;
}
