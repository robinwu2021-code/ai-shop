package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 主体的增值包订阅（P-11.2，V150）。一主体一行。
 *
 * <p><b>额度是快照，不是每次去查档位定义</b>：运营下调 PRO 的门店额度时，
 * 已经开了 3 家店的存量商家不该突然有一家变只读 —— 他什么都没做。
 * 快照让「改档位定义」只影响之后新订阅的人。这是老用户保护，不是冗余。
 */
@Getter
@Setter
@TableName("mch_entity_plan")
public class MchEntityPlan extends BaseEntity {

    public static final String FREE = "FREE";
    public static final String PRO = "PRO";
    public static final String CHAIN = "CHAIN";

    /** 正常生效 */
    public static final String ACTIVE = "ACTIVE";
    /** 到期后的宽限期：**能力全部保留**。扣款失败、老板出差、忘了续，九成与经营无关 */
    public static final String GRACE = "GRACE";
    /** 宽限期也过了：降级到 FREE，但**不关店** */
    public static final String EXPIRED = "EXPIRED";

    public static final String BY_SELF_PAID = "SELF_PAID";
    /** 平台授予（谈下来的连锁客户不走自助付费）——**催收要跳过这一类** */
    public static final String BY_PLATFORM = "PLATFORM";
    public static final String BY_TRIAL = "TRIAL";

    private String entityNo;

    private String planCode;

    /** 门店额度快照 */
    private Integer storeQuota;

    /** 子账号额度快照 */
    private Integer staffQuota;

    /** 能力位：跨店总览与对比。一期只有这一个 —— 它就是这个包卖的东西本身 */
    private Boolean crossStoreStats;

    /**
     * 单商家额度覆盖（P-11.2.4）。{@code null} = 用快照。
     *
     * <p><b>不写进 {@link #storeQuota}</b>：混在一起就分不清「这个数是档位给的还是单独谈的」，
     * 而下次调档位定义时没人知道该不该动它。
     */
    private Integer storeQuotaOverride;
    private Integer staffQuotaOverride;

    /** {@link #ACTIVE} / {@link #GRACE} / {@link #EXPIRED} */
    private String status;

    private Long startAt;

    /** FREE 无到期 */
    private Long expireAt;

    /** {@link #BY_SELF_PAID} / {@link #BY_PLATFORM} / {@link #BY_TRIAL} */
    private String grantedBy;

    /** 一主体一次。**置 1 后永不回退** —— 否则「订阅→退订→再试用」是个无限免费通道 */
    private Boolean trialUsed;

    /** 降级执行到哪一步。重跑到期任务时靠它幂等，不重复把门店压成只读 */
    private Long downgradedAt;
}
