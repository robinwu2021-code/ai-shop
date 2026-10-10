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

    /**
     * 建户时给的占位昵称。
     *
     * <p><b>它同时是「这个人还没设过昵称」的判据</b>（见 {@code UserVO#nicknameSet}）——
     * 默认值与判据必须是同一个来源，否则改了默认值、判据就静默失效，
     * 而界面上看不出任何异常：所有人的「设置昵称」入口一起消失。
     *
     * <p>为什么不是空串：{@code nickname} 被 B 端订单列表、参团邻居墙、履约查询
     * 三处当买家展示名用，留空会让那三处一起变空。
     *
     * <p>为什么不带编号：原来是 {@code "邻居" + userNo 后四位}。那串数字对用户
     * 没有任何意义，却长得像个真名字 —— 于是没人意识到自己可以改。
     */
    public static final String DEFAULT_NICKNAME = "微信用户";

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

    // ---- 最后已知位置（L2，TDD-虹选鲜果运营落地 §1）。被动定位探得，与 communityNo 不互相覆盖 ----
    /** 最后已知位置纬度 e6（精确） */
    private Integer lastLatE6;
    /** 最后已知位置经度 e6 */
    private Integer lastLngE6;
    /** 最后已知区县码 */
    private String lastRegionCode;
    /** 最后已知小区/楼盘名 */
    private String lastPlace;
    /** 落进的开放聚落；没落进为空。**不覆盖主动绑定的 communityNo** */
    private String lastCommunityNo;
    /** 最后定位时刻，给写回节流与时效判断 */
    private java.time.LocalDateTime lastLocatedAt;

    /** 常去店（进店归因，C-ST-09/10）。 */
    private String entityNo;

    /** NORMAL / RISK_LIMITED / BANNED */
    private String status;
}
