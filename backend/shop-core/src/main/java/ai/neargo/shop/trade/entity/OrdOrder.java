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

    /**
     * 下单端 MP_WECHAT/MP_ALIPAY/IOS/ANDROID/H5。取值域见 {@link ai.neargo.shop.common.PayScenes}。
     * <b>与 payChannel 不是一回事</b> —— App 两个通道都能走。
     *
     * <p><b>积分发放的端判定读的是这一列，不是当前请求的端。</b>
     * 发放时机是订单完成时，而那一刻用户可能已经换了端，
     * <b>更常见的是根本没有用户在场</b>（超时自动确认收货是系统动作）。
     * 读当前端会让同一笔订单发不发积分取决于谁在哪个端点的确认、
     * 甚至取决于是不是定时任务跑的 —— 不可复现、无法解释、也无法对账。
     *
     * <p>⚠️ 值来自客户端请求头，<b>天然可伪造</b>：只能用于平台策略判定，
     * 绝不能用于权限或资金判定。
     */
    private String payScene;

    /**
     * 线下收款的确认人（B 端操作员）。
     *
     * <p>平台不碰这笔钱，所以出纠纷时平台能提供的只有这条留痕 ——
     * 缺了它，争议就变成两边各执一词。
     */
    private String offlineConfirmedBy;

    /** 线下收款的确认时间。 */
    private Long offlineConfirmedAt;
}
