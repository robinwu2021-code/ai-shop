package ai.neargo.shop.marketing.group.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/** 商家团（C-8.1）。一期的团由商家和运营铺出来，C 端也能开（C-GB-05）。 */
@Getter
@Setter
@TableName("mkt_group_buy")
public class MktGroupBuy extends BaseEntity {

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
    private String merchantNo;
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
