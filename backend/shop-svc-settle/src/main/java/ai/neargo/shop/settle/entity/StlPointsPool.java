package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 积分营销资金账户流水（V17、V24、V33、V38）。
 *
 * <p><b>平台自己的钱</b>，用于兑现平台发出的积分 —— 与平台优惠券的补差是同一件事。
 * 措辞上刻意避开「清算」「备付」：那是持牌支付机构的法定概念，
 * 平台用它描述自己的营销账户，等于在数据库里自述在做支付清算业务。
 *
 * <p><b>钱实际分散在两个通道账户</b>（平台的微信商户号与支付宝账户），
 * 所以 {@code balanceAfter} 要按 {@code (market, payChannel)} 各自累计。
 * 不按通道记账的话，账面永远是平的，而两个真实账户一个溢一个空，
 * <b>没有任何指标能看出来</b>。
 */
@Getter
@Setter
@TableName("stl_points_pool")
public class StlPointsPool extends BaseEntity {

    public static final String IN = "IN";
    public static final String OUT = "OUT";

    /** 补贴收单商家（补差） */
    public static final String MERCHANT_PAY = "MERCHANT_PAY";
    /** 收商家的发分服务费 */
    public static final String MERCHANT_RECEIVE = "MERCHANT_RECEIVE";
    /** 平台自发的成本 */
    public static final String PLATFORM_ISSUE = "PLATFORM_ISSUE";
    /** 到期转平台收入 —— 不记这一笔的话池子只增不减，恒等式永久失衡 */
    public static final String EXPIRE_INCOME = "EXPIRE_INCOME";
    public static final String RECOVERY = "RECOVERY";
    public static final String PENALTY = "PENALTY";
    public static final String BAD_DEBT = "BAD_DEBT";

    private String flowNo;

    private String direction;

    private String poolType;

    private Long amountMinor;

    /** 变动后余额。按 (market, payChannel) 各自累计，**不是全局一个数**。 */
    private Long balanceAfter;

    private String entityNo;

    private String period;

    /** 关联单据：MERCHANT_RECEIVE 指 stl_bill.settle_no；MERCHANT_PAY 指补贴批次。 */
    private String refNo;

    private String remark;

    private String market;

    private String currency;

    /** 这笔钱进/出哪个通道的账户。 */
    private String payChannel;
}
