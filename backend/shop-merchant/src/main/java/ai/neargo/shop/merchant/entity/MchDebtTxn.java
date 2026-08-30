package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 欠款流水。<b>只有余额字段的账户是不可审计的</b> ——
 * 出现争议时说不清「这笔钱什么时候欠的、凭什么欠、从哪一批扣的」，
 * 而欠款恰恰是最会被商家争的一笔。
 */
@Getter
@Setter
@TableName("mch_debt_txn")
public class MchDebtTxn extends BaseEntity {

    /** 产生：退款追不回来 */
    public static final String INCUR = "INCUR";

    /** 从后续货款里抵扣 */
    public static final String OFFSET = "OFFSET";

    /** 从保证金里抵扣 */
    public static final String DEPOSIT = "DEPOSIT";

    /** 核销：认了这笔收不回来。<b>需审批</b> */
    public static final String WRITE_OFF = "WRITE_OFF";

    /** 源头类型：退款追偿 */
    public static final String SRC_REFUND = "REFUND";

    private String txnNo;

    private String entityNo;

    private String txnType;

    /**
     * 变动额（分），<b>有符号</b>：产生为正、偿还为负。
     *
     * <p>存绝对值再靠 {@link #txnType} 推方向，等于把方向这件事重复表达两遍，
     * 两处一旦不一致就没法判定谁对。
     */
    private Long amountMinor;

    /** 变动后欠款余额（分）。对账时用它逐笔回放 */
    private Long balanceAfterMinor;

    /** 源头类型。<b>指不出源头的欠款没法向商家解释</b> */
    private String sourceType;

    /** 源单号：售后单号 / 结算单号 */
    private String sourceNo;

    /** {@link #OFFSET} 时记从哪一批扣的 */
    private String batchNo;

    private String reason;

    private String operator;
}
