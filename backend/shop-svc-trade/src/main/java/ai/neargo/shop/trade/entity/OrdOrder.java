package ai.neargo.shop.trade.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 主订单：**用户视角**——一次支付、一个支付单号（ADR-002 / E3）。
 *
 * <p>钱在主单，货在子单。这条分界线决定了后面所有事：
 * 支付、退款走主单；分账、发货、核销、售后走子单。
 */
@Getter
@Setter
@TableName("ord_order")
public class OrdOrder extends BaseEntity {

    public static final String WAIT_PAY = "WAIT_PAY";
    public static final String PAID = "PAID";
    public static final String CANCELLED = "CANCELLED";
    public static final String CLOSED = "CLOSED";

    private String orderNo;
    private String userNo;

    private String communityNo;

    /** 应付金额（分）= 各子单之和。冗余在这里，支付时不必再聚合一次。 */
    private Long payAmount;

    /** 商品总额，不含运费与优惠。对账时的基准列。 */
    private Long goodsAmount;

    private Long freightAmount;
    private Long discountAmount;

    private String currency;

    /** WAIT_PAY / PAID / CANCELLED / CLOSED */
    private String status;

    private String payChannel;

    /** 支付服务商流水号，回调对账用。 */
    private String payTradeNo;

    private Long paidAt;

    /** 支付截止时间点：下单时算好，超时任务据此扫描（原 expireAt，Q7 随前端命名）。 */
    private Long payDeadlineAt;

    private String cancelReason;
}
