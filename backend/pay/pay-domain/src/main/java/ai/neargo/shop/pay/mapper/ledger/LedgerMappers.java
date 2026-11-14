package ai.neargo.shop.pay.mapper.ledger;

import ai.neargo.shop.pay.ledger.entity.PayLedgerEntry;
import ai.neargo.shop.pay.ledger.entity.PayLedgerVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 流水的 mapper（db/pay V9）。<b>只增不改</b>：更正只能追加冲正凭证。
 * {@code BaseMapper} 带着 update / delete，调用它们由架构测试禁止（{@code LedgerAppendOnlyTest}）。
 */
public final class LedgerMappers {

    private LedgerMappers() {
    }

    public interface LedgerVoucherMapper extends BaseMapper<PayLedgerVoucher> {

        /** 借贷不平、或合计与借方不符的凭证数（在库里聚合，不把全部流水拉进内存） */
        @Select("SELECT COUNT(*) FROM pay_ledger_voucher v LEFT JOIN (SELECT pay_ledger_voucher_id,"
                + " SUM(CASE WHEN direction = 'DR' THEN amount_minor ELSE 0 END) dr,"
                + " SUM(CASE WHEN direction = 'CR' THEN amount_minor ELSE 0 END) cr FROM pay_ledger_entry WHERE deleted = 0"
                + " GROUP BY pay_ledger_voucher_id) e ON e.pay_ledger_voucher_id = v.id WHERE v.deleted = 0"
                + " AND (e.dr IS NULL OR e.dr <> e.cr OR v.total_minor IS NULL OR v.total_minor <> e.dr)")
        long countUnbalanced();
    }

    public interface LedgerEntryMapper extends BaseMapper<PayLedgerEntry> {

        /** 恒等式①：已付 − 已退 ≠ 收款账户（两条资金路径各自的「钱在哪」）的借贷净额 */
        @Select("SELECT o.pay_order_no FROM pay_order o LEFT JOIN (SELECT pay_order_id,"
                + " SUM(CASE WHEN direction = 'DR' THEN amount_minor ELSE -amount_minor END) net FROM pay_ledger_entry"
                + " WHERE account IN ('MERCHANT_CHANNEL', 'PLATFORM_CHANNEL') AND deleted = 0 GROUP BY pay_order_id) l"
                + " ON l.pay_order_id = o.id WHERE o.deleted = 0 AND o.funds_path IS NOT NULL"
                + " AND o.status IN ('SUCCEEDED', 'PARTIALLY_REFUNDED', 'REFUNDED')"
                + " AND COALESCE(l.net, 0) <> o.paid_minor - o.refunded_minor ORDER BY o.id LIMIT #{limit}")
        List<String> ledgerMismatchedOrders(@Param("limit") int limit);
    }
}
