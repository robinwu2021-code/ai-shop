package ai.neargo.shop.merchant.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 供应商收款账户（ADR-011 自营供应商模式 · V358）。
 *
 * <p><b>为什么它不能复用进件时采的那个结算账号</b>：进件那条路上账号是
 * 「明文只在本次调用中存在，不落库」（{@code PayApplymentGateway.SubmitCommand}），
 * 库里只留掩码。那个设计是对的 —— 它的用途是<b>转交给通道</b>，平台自己不用留。
 * 自营付款不一样：平台要拿着账号去网银转账，绕不过落库。
 *
 * <p><b>账号只以密文形态存在于这个类里</b>（{@link #accountNumberEnc}）。
 * 没有 {@code accountNumber} 字段 —— 不给明文留字段，就不会有人顺手 set 一个明文进去。
 * 解密在 {@code PayoutAccountCipher}，且只在「导出付款清单」那一个入口调用。
 *
 * <p><b>挂主体不挂门店</b>：收款是主体的事（一张营业执照一个收款人），
 * 门店只是统计维度 —— {@code StlBill.storeNo} 的注释写的就是这条界线。
 */
@Getter
@Setter
@TableName("mch_payout_account")
public class MchPayoutAccount extends BaseEntity {

    /** 已提交，等运营核。**此状态不可用于付款**。 */
    public static final String PENDING = "PENDING";

    /**
     * 生效中。<b>同一主体同时只能有一条</b>。
     *
     * <p>不允许多条并存的理由：付款时要能无歧义地回答「打给哪张卡」。
     * 允许多条就必须再引入一个「默认账户」标记，而那个标记与状态是两个真源，
     * 迟早会出现「两条都是默认」或「一条都不是」。
     */
    public static final String ACTIVE = "ACTIVE";

    /** 已驳回，必须带原因 —— 原文回商家 B 端。 */
    public static final String REJECTED = "REJECTED";

    /** 已停用：被新账户顶替，或运营主动停。历史付款仍指向它，所以不删行。 */
    public static final String DISABLED = "DISABLED";

    /** 个人银行卡。与 {@code sys_legal_form.settle_account_type} 同值域 */
    public static final String PERSONAL_BANK_CARD = "PERSONAL_BANK_CARD";
    /** 对公账户。 */
    public static final String CORPORATE = "CORPORATE";

    private String accountNo;

    /** 供应商主体。 */
    private String entityNo;

    /** {@link #PERSONAL_BANK_CARD} / {@link #CORPORATE}。 */
    private String accountType;

    /**
     * 户名。
     *
     * <p><b>三流一致比对用</b>：必须等于主体名与进项票开票方名。
     * V23 的进项票注释已经写明这一点 —— 户名对不上时钱付得出去，
     * 但这笔支出在税上站不住。
     */
    private String accountName;

    /** 账号密文 {@code base64(iv‖密文‖tag)}。<b>绝不存明文</b>。 */
    private String accountNumberEnc;

    /** 掩码，只留尾四位（口径同 {@code Masks.tail}）。列表、日志、导出预览一律用它。 */
    private String accountMasked;

    private String bankName;

    private String bankBranch;

    /** {@link #PENDING} / {@link #ACTIVE} / {@link #REJECTED} / {@link #DISABLED}。 */
    private String status;

    /** 驳回原因。原样回商家。 */
    private String auditRemark;

    /** 审核人（STAFF 账号）。改收款账户是资金重定向，必须留痕。 */
    private String auditedBy;

    /** 审核时刻（毫秒）。未审为空。 */
    private Long auditedAt;
}
