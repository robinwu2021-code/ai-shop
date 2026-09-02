package ai.neargo.shop.marketing.group.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 商家团（C-8.1）。一期的团由商家和运营铺出来，C 端也能开（C-GB-05）。 */
@Getter
@Setter
@TableName("mkt_group_buy")
public class MktGroupBuy extends BaseEntity {

    /**
     * 等平台审核，<b>C 端不可见、不可参团</b>。
     *
     * <p>只有开关 {@code group.audit} 打开时才会产生这个态；关着时建团直接进
     * {@link #OPEN}，行为与加开关之前逐字相同。
     *
     * <p>可见性无需另判：C 端只列 {@code OPEN/FORMED}，参团只认 {@code OPEN} ——
     * PENDING 天然被这两处挡住。**这正是选它作为新态而不是加一个布尔位的理由**：
     * 加布尔位要在每个读的地方补一次判断，漏一处就是「没审核就上线了」。
     */
    public static final String PENDING = "PENDING";
    public static final String OPEN = "OPEN";
    public static final String FORMED = "FORMED";
    public static final String FAILED = "FAILED";

    private String groupNo;
    /**
     * C 端发起人。<b>为空就是「这是商家开的团」</b>，不是「数据没填」——
     * 团有两种来源，用可空区分比再加一个 source 列更省，也不会出现两列打架。
     * {@code isOwner} 与「我发起的团」都靠它。
     */
    private String initiatorUserNo;
    private String goodsNo;
    private String skuNo;
    private String entityNo;
    private String title;
    private String cover;
    private Long groupPriceMinor;
    private Long originPriceMinor;

    /** 起团人数。 */
    private Integer minCount;
    private Integer joinedCount;

    private String status;
    private Long endAt;

    /**
     * 成团范围：自提点。
     * 团购拼的是**一车送到一个点**的成本，所以成团单位是自提点而不是社区（ADR-005）。
     * 此前库里没有这一列 —— 团按什么范围成、货送到哪个点，表达不了。
     */
    private String pickupNo;
}
