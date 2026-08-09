package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 结算单（ADR-002）。**按子单**：一个子单 = 一个商家 = 一次分账。
 *
 * <p>三个金额列各有用处，不能合并成一个 net：
 * 商家问「为什么这单只结了 46 块」时，要能拆成「基数 - 佣金 - 服务费」给他看。
 */
@Getter
@Setter
@TableName("stl_bill")
public class StlBill extends BaseEntity {

    public static final String PENDING = "PENDING";
    public static final String SPLITTING = "SPLITTING";
    public static final String SPLIT = "SPLIT";
    public static final String RETRYING = "RETRYING";
    public static final String MANUAL = "MANUAL";
    public static final String REVERSED = "REVERSED";

    private String settleNo;
    private String subOrderNo;
    private String orderNo;
    private String entityNo;

    /** 应结基数 = 用户实付 + **平台补贴的优惠**（平台券的钱最终要给商家）。 */
    private Long grossMinor;

    private Long commissionMinor;
    private Long serviceFeeMinor;

    /** 商家实得 = 基数 - 佣金 - 服务费。 */
    private Long netMinor;

    private String trafficSource;

    /** 万分比，**落库快照** —— 费率会变，历史账不能跟着变。 */
    private Integer commissionRate;

    private String status;
    private Long splitAt;
    private Integer retryCount;
    private String lastError;
    /**
     * 计提时间（支付成功时）。与 {@code splitAt}（实际分账时间）**分开** ——
     * 账面与资金是两个时点：支付即计提让商家立刻看到实收，
     * 而真实资金移动等到售后期结束解冻时才发生。
     */
    private Long accruedAt;


    /** 支付通道 WECHAT/ALIPAY：分账实现按它路由，对账按它切分。 */
    private String payChannel;

    /** 下单端，与 payChannel 不是一回事。 */
    private String payScene;

    /** 该笔实际扣的通道手续费（分）。**以回执为准**，不是按费率算出来的。 */
    private Long channelFeeMinor;

    /** 通道费率快照（万分比）。 */
    private Integer channelFeeRate;

    /** STANDARD/PROMO —— 费率来源，差异要能对商家解释。 */
    private String channelFeeSource;

    /** 通道费由 MERCHANT 还是 PLATFORM 承担。 */
    private String feeBearer;

    /** 本单的积分服务费（分）：商家发分即扣，结算时从货款扣走进积分池。 */
    private Long pointsFeeMinor;

    /**
     * 实际向通道发起分账的金额（分）= 佣金 + 履约服务费 + 积分服务费。
     *
     * <p><b>分账指令以它为准，不要在发起时重算</b> —— 算式会变（将来可能加收费项），
     * 而历史账不能跟着变。与 commissionRate 落快照是同一个道理。
     */
    private Long splitAmountMinor;
}
