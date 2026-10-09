package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 放款记录：<b>账期批次 × 收款号，一笔网银转账一条</b>（V391 / TDD-账期推进与放款记录 §2.3）。
 *
 * <p>凭证号、银行流水都挂在它上面，不再挂在逐张结算单上。
 * 户名 / 开户行 / 掩码账号是<b>付款那一刻的快照</b>：银行要户名，而账号会改。
 *
 * <p>状态只有一个方向：PENDING → EXPORTED → PAID → MATCHED；FAILED 是退回。
 * MATCHED <b>只能由出款对账轴写</b>（银行流水勾上），不给人工入口 ——
 * 与「SPLIT_CONFIRMED 只能由通道回执产生」同一条规矩。
 */
@Getter
@Setter
@TableName("stl_payout")
public class StlPayout extends BaseEntity {

    /** 已生成，还没进付款清单 */
    public static final String PENDING = "PENDING";
    /** 已导出进付款清单，财务拿去网银 */
    public static final String EXPORTED = "EXPORTED";
    /** 财务登记了凭证号 */
    public static final String PAID = "PAID";
    /** 银行流水勾上了。对账轴写，不给人工入口 */
    public static final String MATCHED = "MATCHED";
    /** 打款失败或退回。批次回到可放款，结算单回到待对账 */
    public static final String FAILED = "FAILED";

    public static final String CHANNEL_MANUAL = "MANUAL";
    public static final String CHANNEL_BANK_API = "BANK_API";

    private String payoutNo;
    private String batchNo;
    private String entityNo;
    private String payMerchantNo;
    private String accountName;
    private String bankName;
    private String bankBranch;
    private String accountNoMasked;
    private Long amountMinor;
    private Integer billCount;
    private String currency;
    private String status;
    private String channel;
    private String paymentRef;
    private String bankFlowNo;
    private Long exportedAt;
    private Long paidAt;
    private String paidBy;
    private Long matchedAt;
    private String failReason;
}
