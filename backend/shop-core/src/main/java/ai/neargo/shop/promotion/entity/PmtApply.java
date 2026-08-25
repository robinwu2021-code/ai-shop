package ai.neargo.shop.promotion.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 优惠发生记录：一单命中了什么、一张券被用了第几次。
 *
 * <p><b>线上抵扣与线下核销同一张表</b>。原设计里另有一张核销日志，
 * 与本表实打实地重叠 —— 券在下单抵扣时两边各写一行，金额要两处对得上，
 * 而两处对账迟早有一天加不上。合并之后：线上带 {@code orderNo}，
 * 线下带 {@code storeNo} + {@code operatorNo}，次卡用 5 次就是 5 行。
 *
 * <p>三件事都读它：活动效果（按 {@code promoNo} 聚合）、券的对账、会员来源归因。
 */
@Getter
@Setter
@TableName("pmt_apply")
public class PmtApply extends BaseEntity {

    public static final String COUPON = "COUPON";
    public static final String ACTIVITY = "ACTIVITY";
    public static final String POINTS = "POINTS";

    private String applyNo;
    private String promoType;
    private String promoNo;
    private String userNo;
    private String entityNo;
    private String storeNo;
    private String orderNo;
    private String subOrderNo;
    private String redeemMode;
    private String operatorNo;
    private Long amountMinor;
    private String funder;
    private Long appliedAt;
    /** 订单取消/退款时置。<b>线下核销不可撤销</b>，那一行恒为空 */
    private Long revertedAt;
}
