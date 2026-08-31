package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 资金流水：收款 / 退款 / 补差 / 补差回退 / 打款（V31、V38）。
 *
 * <p><b>五种方向同一张表</b>：它们的通道回执结构一样、幂等要求一样、对账要一起做。
 * 拆开会有大量共同字段，而对账时还得把几张表 union 起来。
 *
 * <p>这张表存在的理由是三件只有它能做的事：
 * <ul>
 *   <li><b>重试幂等</b> —— 靠 {@code paymentNo}。没有它，退款失败重试就是退两次</li>
 *   <li><b>逐笔对账</b> —— 靠 {@code outTradeNo} + {@code tradeNo} 两个号</li>
 *   <li><b>掉单发现</b> —— 靠 {@code reconciledAt} 为空。用户付了钱而我方没收到回调，
 *       <b>只能靠对账发现</b>，没有别的手段</li>
 * </ul>
 */
@Getter
@Setter
@TableName("stl_payment")
public class StlPayment extends BaseEntity {

    /** 收款 */
    public static final String PAY = "PAY";
    /** 退款 */
    public static final String REFUND = "REFUND";
    /** 补差：积分抵扣的部分，分账前补进二级商户账户 */
    public static final String SUBSIDY = "SUBSIDY";
    /** 补差回退 */
    public static final String SUBSIDY_REVERSE = "SUBSIDY_REVERSE";
    /** 打款给商家 */
    public static final String PAYOUT = "PAYOUT";

    public static final String INIT = "INIT";
    public static final String PENDING = "PENDING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";
    public static final String CLOSED = "CLOSED";

    /** 本方流水号，**幂等键** —— 重试用同一个号，通道按它去重。 */
    private String paymentNo;

    private String direction;

    private String orderNo;

    /** REFUND 退的是哪个子单；SUBSIDY 补的是哪个子单。收款挂主订单，此列为空。 */
    private String subOrderNo;

    /** 仅 REFUND：对应售后单。部分退款会有多条，各自一次通道调用。 */
    private String afterSaleNo;

    private String userNo;

    /** 仅 PAYOUT：打给哪个商家。收款与退款的对手方是用户，不填。 */
    private String entityNo;

    private String payChannel;

    /** 下单端。**退款也要记** —— 退回原路，端不同接口不同。 */
    private String payScene;

    private String payMethod;

    /** 金额（分），**恒为正**。方向看 direction —— 负数金额在通道对账单上没有对应概念。 */
    private Long amountMinor;

    private String currency;

    private String status;

    /** 我方给通道的商户订单号。**用户报障时报的是这个**。 */
    private String outTradeNo;

    /** 通道交易号（微信 transaction_id / 支付宝 trade_no）。**对账单上是这个**。 */
    private String tradeNo;

    /** 通道实扣手续费（分）。以回执为准，不是按费率算出来的。 */
    private Long channelFeeMinor;

    private Long succeededAt;

    private Long closedAt;

    private String errCode;

    private String errMsg;

    /** 通道回调原文。**唯一的资金凭据** —— 出纠纷要原文，解析后的字段不能举证。 */
    private String rawNotify;

    /** 与通道账单核对通过的时间。**为空 = 未对账**，掉单只能靠这个发现。 */
    private Long reconciledAt;

    /** 对账批次（通道账单日期 YYYYMMDD）。 */
    private String reconcileBatch;
}
