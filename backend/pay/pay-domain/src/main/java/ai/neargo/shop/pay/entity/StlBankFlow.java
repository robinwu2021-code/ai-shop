package ai.neargo.shop.pay.entity;

import ai.neargo.shop.common.BaseEntity;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

/**
 * 银行流水镜像（V363 · 出款对账 B 侧）。
 *
 * <p>人工从网银导出后上传。它是「银行到底有没有划出这笔」的<b>唯一外部判据</b> ——
 * 在它之前，{@code PayoutReconAxis} 只能自查我方登记得对不对，
 * 而「登记了但银行没划」与「银行划了但没登记」两种都看不见。
 *
 * <p><b>金额恒为正，方向看 {@link #direction}。</b>银行导出的 CSV 里
 * 借贷方向各家写法不同（借/贷、支出/收入、负数），那一层归一化在导入时做完 ——
 * 让方言停在解析器里，不要流进比对逻辑。
 */
@Getter
@Setter
@TableName("stl_bank_flow")
public class StlBankFlow extends BaseEntity {

    /** 付出。出款对账只看这一类 */
    public static final String OUT = "OUT";
    /** 收入。同一份流水里两种都有，所以要存下来再筛 */
    public static final String IN = "IN";

    /** 银行流水号。**与 {@code stl_bill.payment_ref} 对勾的就是它** */
    private String flowNo;

    /** 交易日期 {@code yyyy-MM-dd}。按日对账，不需要时分秒 */
    private String tradeDate;

    /** {@link #OUT} / {@link #IN} */
    private String direction;

    /** 金额（分），<b>恒为正</b> */
    private Long amountMinor;

    /** 对方户名。核对「钱是不是打给了这家供应商」 */
    private String counterpartyName;

    /** 对方账号掩码。<b>只存掩码</b> —— 对账不需要全号 */
    private String counterpartyAccountMasked;

    /**
     * 银行附言/摘要。
     *
     * <p>导出付款清单时写的是 {@code 货款-{主体号}}，所以它是<b>第二条勾对线索</b>：
     * 流水号对不上时（财务填错一位），靠它还能找回是哪一家。
     */
    private String remark;

    /** 勾对上的结算单号；空 = 还没勾上（可能是差异，也可能只是还没扫到） */
    private String matchedSettleNo;

    /** 谁传的。银行流水是对账的判据，<b>判据从哪来要留痕</b> */
    private String importedBy;

    /** 导入时刻（毫秒） */
    private Long importedAt;
}
