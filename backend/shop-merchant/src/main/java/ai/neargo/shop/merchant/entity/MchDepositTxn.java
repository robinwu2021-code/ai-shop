package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 保证金流水。
 *
 * <p><b>流水不是可选项：只有余额字段的账户是不可审计的。</b>
 * 出现争议时，没有流水就说不清「这笔钱什么时候少的、谁扣的、凭什么扣」，
 * 而保证金恰恰是争议最集中的一笔钱。
 */
@Getter
@Setter
@TableName("mch_deposit_txn")
public class MchDepositTxn extends BaseEntity {

    public static final String PAY = "PAY";
    public static final String REFUND = "REFUND";
    public static final String FREEZE = "FREEZE";
    public static final String UNFREEZE = "UNFREEZE";
    public static final String DEDUCT = "DEDUCT";

    private String txnNo;
    private String merchantNo;
    private String txnType;
    /**
     * 变动额，<b>有符号</b>：扣划为负。
     *
     * <p>不存绝对值再靠 {@code txnType} 推方向——那等于把方向重复表达两遍，
     * 两处一旦不一致就没法判定谁对。
     */
    private Long amountMinor;
    private Long balanceAfterMinor;
    private String reason;
    private String operator;
}
