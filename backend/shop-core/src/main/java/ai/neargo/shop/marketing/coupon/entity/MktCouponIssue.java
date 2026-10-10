package ai.neargo.shop.marketing.coupon.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 券的**主动发放**记录（P-7.1.2）。
 *
 * <p>与 {@link MktUserCoupon} 是两件事：那张表是「谁手里有哪张券」，
 * 这张表是「谁按了发放按钮、发给谁、为什么」。
 *
 * <p><b>操作人不能省</b>：客服也持有发券权限（矩阵 §2.3 补偿券），
 * 一天几十次。没有留痕的话「这张 50 元券是谁发的」事后完全查不出来 ——
 * 而那正是内部套现最省事的路径。
 */
@Getter
@Setter
@TableName("mkt_coupon_issue")
public class MktCouponIssue extends BaseEntity {

    /** 发给所有人 */
    public static final String ALL = "ALL";
    /** 发给新客 */
    public static final String NEW_USER = "NEW_USER";
    /** 发给某个社区 */
    public static final String COMMUNITY = "COMMUNITY";
    /** 发给某一个人 —— 客服补偿券走的就是这条 */
    public static final String SINGLE_USER = "SINGLE_USER";

    private String issueNo;
    private String couponNo;
    /** 券名快照：券改名或归档之后，这条记录仍要读得懂 */
    private String couponName;
    private String target;
    /**
     * 当时写下的定向说明，<b>自由文本</b>（「海棠（售后补偿）」「锦绣花园」）。
     * 不是外键 —— 事后审计要看的就是这句话，规范成社区号反而丢信息。
     */
    private String targetDesc;
    /** SINGLE_USER 时的收券人；其余目标类型为空 */
    private String userNo;
    private Integer issuedCount;
    /** 本次占用的预算（分）= 张数 × 面额 */
    private Long amountMinor;
    private String operatorNo;
}
