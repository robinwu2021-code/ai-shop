package ai.neargo.shop.pay.ledger.entity;

import ai.neargo.shop.pay.entity.PayBaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDate;
import lombok.Getter;
import lombok.Setter;

/**
 * pay_ledger_voucher（db/pay V7–V9 · TDD-支付服务-I3支付单退款流水 S1）：记账凭证：一次资金事件一张，下挂借贷平衡的分录。只增不改，更正只能追加冲正凭证
 *
 * <p>字段注释取自结构化模型 {@code pay-service-database.json}，逐列定义以表结构说明为准。
 */
@Getter
@Setter
@TableName("pay_ledger_voucher")
public class PayLedgerVoucher extends PayBaseEntity {

    /** 凭证号（给财务看的编号） */
    private String voucherNo;

    /** PAY_SUCCEEDED / REFUND_SUCCEEDED / CHANNEL_FEE / PLATFORM_FEE / SETTLEMENT / PAYOUT / FEE_OFFSET / ADJUST / REVERSAL */
    private String voucherType;

    /** PAY_ORDER / PAY_REFUND / STL_BILL / STL_PAYOUT / FEE_RECEIVABLE / MANUAL */
    private String refType;

    /** 引用对象 id（按 ref_type）；MANUAL 为空 */
    private Long refId;

    /** 幂等键：voucher_type|ref_type|ref_id[|序号]，同一事件只记一次 */
    private String idemKey;

    /** 冲正哪张凭证 pay_ledger_voucher.id */
    private Long reversalOfId;

    /** 市场 */
    private String market;

    /** 币种（一张凭证单币种） */
    private String currency;

    /** 借方合计 = 贷方合计 */
    private Long totalMinor;

    /** 业务日（按市场时区切日） */
    private LocalDate bizDate;

    /** 资金路径 */
    private String fundsPath;

    /** 来源类型（继承支付单） */
    private String sourceType;

    /** 来源 id */
    private Long sourceId;

    /** 人工凭证的操作人 */
    private String operatorNo;

    /** 摘要 */
    private String memo;
}
