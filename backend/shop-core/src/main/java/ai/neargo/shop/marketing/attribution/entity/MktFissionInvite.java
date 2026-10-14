package ai.neargo.shop.marketing.attribution.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 裂变邀请台账（V121）。**这张表是「邀了多少、成了多少」的唯一真源。**
 *
 * <p>建表很久，而在 2026-08-30 之前**没有任何代码读写过它** —— 于是运营端
 * 「邀请有礼」列表那两列（累计邀请 / 完成首单）恒为 0，而 0 既像「还没人参加」
 * 又像「坏了」，没人分得出来。
 */
@Getter
@Setter
@TableName("mkt_fission_invite")
public class MktFissionInvite extends BaseEntity {

    private String fissionNo;

    /** 邀请人 userNo */
    private String inviterNo;

    /** 被邀请人 userNo。与 fissionNo 组成唯一键 uk_fission_invitee = 发奖幂等锁 */
    private String inviteeNo;

    /** 被邀请人设备号，新客判定用（因子 DEVICE） */
    private String deviceId;

    /** 手机号后四位（因子 PHONE）。**完整号码永远不出 UserQueryPort**（B12） */
    private String phoneTail;

    /**
     * 是不是新客。**非新客照样落行、但不发奖** ——
     * 不落行的话运营只会看到一个莫名其妙偏低的 invitedCount，
     * 而「邀了 100 个只有 3 个算数」这件事在数据里看不见。
     */
    private Integer isNewUser;

    /** 奖励是否已发出 */
    private Integer rewarded;

    /** 发奖失败原因（券停用 / 预算耗尽）。**发奖失败不打断归因主流程** */
    private String rewardError;

    /** 被邀请人首单，由下单那一刻回填 = 转化 */
    private String orderNo;
}
