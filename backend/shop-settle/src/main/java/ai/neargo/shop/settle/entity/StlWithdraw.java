package ai.neargo.shop.settle.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 商家提现单（矩阵 P-12.2.1 提现审批 / 12.2.2 限额）。
 *
 * <p><b>这张表不打款。</b> 分账参数的书面口径还没拿到（待完成功能清单 §四 B7、
 * ADR-002 §6 待确认第 1 条），而 B-12.5 定的是「一期只记账、线下结算」。
 * 它记的是<b>审批与留痕</b>：谁在什么时候、按什么余额口径、批了多少钱。
 *
 * <p><b>{@link #APPROVED} → {@link #PAID} 刻意不给人工入口</b>：打款结果只能来自渠道回执。
 * 让人手动置为「已打款」，等于允许在钱没到账时把单子做平 ——
 * 之后对账差额永远说不清是通道慢了还是有人点早了。
 */
@Getter
@Setter
@TableName("stl_withdraw")
public class StlWithdraw extends BaseEntity {

    /** 已申请，等审批。 */
    public static final String PENDING = "PENDING";
    /** 已通过。<b>不等于已打款</b>。 */
    public static final String APPROVED = "APPROVED";
    /** 已驳回，必须写原因 —— 原文回商家 B 端。 */
    public static final String REJECTED = "REJECTED";
    /** 渠道回执确认已到账。本批没有生产者。 */
    public static final String PAID = "PAID";
    /** 渠道回执确认失败，可重新审批（多半是账户信息要改）。 */
    public static final String FAILED = "FAILED";

    /**
     * 单笔提现下限（分）。低于它渠道手续费比本金还贵。
     *
     * <p>与 {@code ops-web/lib/constants.ts} 的 {@code MIN_WITHDRAW_AMOUNT} 同值 ——
     * 界面先算一遍是为了让运营点之前就知道会不会被拒，<b>拦住的那一道在这里</b>。
     */
    public static final long MIN_AMOUNT_MINOR = 1000L;

    /**
     * 复核阈值（分）：到这个数以上必须写复核说明。
     *
     * <p>大额是最容易被冒用的口子。不要求说明的话，事后只能看到「某人批了五万」，
     * 看不到「为什么这五万是对的」。
     */
    public static final long REVIEW_THRESHOLD_MINOR = 500_000L;

    private String withdrawNo;

    /** 申请主体（商家）。 */
    private String entityNo;

    /** 商家名快照 —— 商家改名不该让历史提现单跟着变。 */
    private String merchantName;

    private Long amountMinor;

    /**
     * 申请时的可提余额快照（分）。<b>不是实时值</b>。
     *
     * <p>审批看的是「申请那一刻他能提多少」；用实时值的话，
     * 期间进来的新订单会让同一张单在两次打开之间给出两个结论。
     */
    private Long availableBalanceMinor;

    /*
     * ⚠️ **这里没有 tax_amount_minor。** 个税代扣（P-12.2.3）只对个人主体生效，
     * 而「这家商户是不是个人主体」在 MerchantQueryPort 上没有暴露 ——
     * 对全部主体一律代扣是错的（个体户与企业自行申报），
     * 而留一个恒为 0 的列比没有这个列更容易被误读成「已经扣过了」。
     * 本批只落规则（sys_setting: finance.tax-rule），落单等 Port 能给出主体类型。
     */

    /** 收款账户掩码。<b>只存掩码</b> —— 全号一旦落库就要按敏感信息管。 */
    private String bankAccountMasked;

    private String status;

    private Long appliedAt;
    private Long decidedAt;

    /** 审批人（STAFF 账号）。这是运营端唯一会把钱批出去的动作，必须留痕。 */
    private String decidedBy;

    /** 驳回原因 / 大额复核说明。<b>原样回商家 B 端</b>，不写等于让人猜。 */
    private String remark;

    /** 运营端可审批的两个入口状态。其余状态一律拒绝 —— 见类注释。 */
    public boolean decidable() {
        return PENDING.equals(status) || FAILED.equals(status);
    }
}
