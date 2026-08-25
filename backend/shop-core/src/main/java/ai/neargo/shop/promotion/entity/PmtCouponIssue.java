package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 一批发放：发给谁、发了多少、<b>跳过多少</b>、谁发的。
 *
 * <p><b>不静默少发</b>是这张表存在的理由。商家选了 37 个人、实发 25 张，
 * 界面上只显示「发放成功」的话，他会以为发出去 37 张 —— 直到某个顾客说没收到。
 * {@code skipDetail} 要能把话说全：「12 跳过：9 人已达每人上限、3 人是线索会员」。
 */
@Getter
@Setter
@TableName("pmt_coupon_issue")
public class PmtCouponIssue extends BaseEntity {

    private String issueNo;
    private String couponNo;
    private String entityNo;
    private String issueMode;
    private String segmentNo;
    private String activityNo;
    /** 发放当时的人群条件快照。条件后来会改，追责要看当时那一份 */
    private String ruleSnapshot;
    private Integer plannedCount;
    private Integer issuedCount;
    private Integer skippedCount;
    private String skipDetail;
    private Long amountMinor;
    private String operatorNo;
    private Long issuedAt;
}
