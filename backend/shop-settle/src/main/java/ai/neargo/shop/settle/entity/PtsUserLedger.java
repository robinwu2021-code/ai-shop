package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 用户积分流水：**全域的真源**，账户余额由它重算。
 *
 * <p><b>一张表两种形状</b>，按 {@code bizType} 分流之后才能读：
 * EARN 行有 {@code availableAt} / {@code issuerMerchantNo}；
 * USE 行有 {@code acceptorMerchantNo} / {@code amountMinor} / {@code status} / {@code period}。
 * 读之前不分流的话，拿到的是一堆 null。
 *
 * <p><b>没有「批次」概念</b>（V30 起按账户滚动到期）：
 * {@code remaining} 与行级 {@code expireAt} 已删除 —— 它们唯一的读者是过期任务，
 * 而到期改挂在账户上之后，那个读者也不需要它们了。
 */
@Getter
@Setter
@TableName("pts_user_ledger")
public class PtsUserLedger extends BaseEntity {

    /** 发放 */
    public static final String EARN = "EARN";
    /** 使用 */
    public static final String USE = "USE";
    /** 退回 */
    public static final String REFUND = "REFUND";
    /** 到期清零（整账户一次） */
    public static final String EXPIRE = "EXPIRE";
    /** 退款扣回 */
    public static final String REVOKE = "REVOKE";

    /** 仅 USE：预占，**此时池子还没付给收单方**（订单可能取消） */
    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String REVERSED = "REVERSED";

    private String ledgerNo;

    private String userNo;

    private String bizType;

    /** **带符号**：EARN/REFUND 为正，USE/EXPIRE/REVOKE 为负。 */
    private Long points;

    /** 变动后余额快照，用于定位「从哪条开始错的」。 */
    private Long balanceAfter;

    /**
     * 仅 EARN：可用时间（售后期结束）。此前分<b>可见不可用</b>，计入 pendingBalance。
     *
     * <p>没有这一列的话，刷子的标准打法是：下大单 → 拿分 → 立刻在别家花掉 → 退掉大单。
     */
    private Long availableAt;

    /** 仅 EARN：谁发的。**只用于追溯与统计**，不参与任何资金流动（V28 起与兑付脱钩）。 */
    private String issuerMerchantNo;

    /** 仅 USE：收单方，池子补差给它。**不记发放方** —— 发分时已付费。 */
    private String acceptorMerchantNo;

    /** 仅 USE：本次抵扣的金额（分）。与 ord_sub_order.points_deduct_minor 勾稽。 */
    private Long amountMinor;

    /** 汇率快照（多少分 = 1 元）。调汇率不改变已发生的账。 */
    private Integer rateSnapshot;

    /** 仅 USE：PENDING / CONFIRMED / REVERSED。 */
    private String status;

    /** 仅 USE：账期 YYYYMM，CONFIRMED 时落定。 */
    private String period;

    private Long confirmedAt;

    private String subOrderNo;

    private String remark;

    private String market;

    private String currency;
}
