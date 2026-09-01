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

    /**
     * 通道回调原文。
     *
     * <p><b>这一列从 V1 建库至今一行都没写过，永远是 null。</b>
     * 原注释写着「唯一的资金凭据 —— 出纠纷要原文」，那句话从来没有兑现过：
     * 没有任何代码给它赋值。<b>一个宣称自己是举证依据、而实际永远为空的列，
     * 比没有这一列危险</b> —— 真出纠纷时有人会先来查它，查到 null 才发现没有。
     *
     * <p>它的职责 2026-09-01 起由 {@code stl_channel_message}（V286）承担，
     * 而且那边做得更完整：被拒的回调也留、发送侧也留、还落了脱敏与保留期。
     * <b>那边存的是脱敏后的报文，同样不能拿去举证</b> ——
     * 举证要去通道后台调原件，这一点两边都一样，只是现在说清楚了。
     *
     * <p>列本身没删：删列要 DDL、要停机窗口，而它不占地方也不骗人了。
     * 谁哪天真要在支付流水上挂原文，先读 V286 的表头注释再决定。
     */
    private String rawNotify;

    /** 与通道账单核对通过的时间。**为空 = 未对账**，掉单只能靠这个发现。 */
    private Long reconciledAt;

    /** 对账批次（通道账单日期 YYYYMMDD）。 */
    private String reconcileBatch;
}
