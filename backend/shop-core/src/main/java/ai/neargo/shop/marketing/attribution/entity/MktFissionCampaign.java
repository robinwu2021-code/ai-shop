package ai.neargo.shop.marketing.attribution.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 裂变活动（邀请有礼 / 老带新，P-9.2.1/9.2.2，V121）。
 *
 * <p><b>奖励只能是券</b>：ADR-004 去团长化之后不存在现金激励 ——
 * 一旦发现金，职业薅羊毛立刻回来，且归因作弊有了直接的变现路径。
 * 所以 {@code rewardType} 只有一个合法值，它存在只是为了让这条约束在数据里也说得出来。
 */
@Getter
@Setter
@TableName("mkt_fission_campaign")
public class MktFissionCampaign extends BaseEntity {

    /** 唯一合法的奖励类型 */
    public static final String COUPON = "COUPON";

    private String fissionNo;
    private String name;
    private String rewardType;

    /** 奖励券模板（{@code mkt_coupon.coupon_no}） */
    private String couponNo;

    private Integer inviterCount;
    private Integer inviteeCount;
    private Boolean enabled;

    /**
     * 累计邀请人数 / 其中完成首单的人数。
     *
     * <p><b>这两个是台账（{@code mkt_fission_invite}）的聚合快照</b>，
     * 写在这里只为列表页省一次 count；真值以台账为准，对不上时以台账重算。
     */
    private Integer invitedCount;
    private Integer convertedCount;
}
