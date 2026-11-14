package ai.neargo.shop.pay.ledger.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_ledger_entry（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：流水分录：每一笔资金变化的复式分录行
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_ledger_entry")
public class PayLedgerEntry extends PayBaseEntity {

    /** 所属凭证 pay_ledger_voucher.id */
    private Long payLedgerVoucherId;

    /** 行号 */
    private Integer lineNo;

    /** MERCHANT_CHANNEL（DIRECT，平台外备查）/ PLATFORM_CHANNEL / MERCHANT_PAYABLE / FEE_RECEIVABLE / PLATFORM_REVENUE / CHANNEL_FEE_EXPENSE / REFUND_CLEARING */
    private String account;

    /** DR / CR */
    private String direction;

    /** 金额，恒为正 */
    private Long amountMinor;

    /** 币种 */
    private String currency;

    /** 门店 mch_store.id（核心系统；关联核心系统一律用门店 id） */
    private Long storeId;

    /** 收款商户 pay_merchant.id */
    private Long payMerchantId;

    /** 渠道码 */
    private String payChannel;

    /** 市场 */
    private String market;

    /** 资金路径 */
    private String fundsPath;

    /** 支付单 pay_order.id（便于按单拉流水） */
    private Long payOrderId;

    /** 业务日 */
    private LocalDate bizDate;
}
