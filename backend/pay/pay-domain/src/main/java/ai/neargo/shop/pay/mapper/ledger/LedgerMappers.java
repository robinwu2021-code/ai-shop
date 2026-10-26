package ai.neargo.shop.pay.mapper.ledger;

import ai.neargo.shop.pay.ledger.entity.PayLedgerEntry;
import ai.neargo.shop.pay.ledger.entity.PayLedgerVoucher;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

/**
 * 流水的 mapper（db/pay V9）。<b>只增不改</b>：更正只能追加冲正凭证。
 * {@code BaseMapper} 带着 update / delete，调用它们由架构测试禁止（{@code LedgerAppendOnlyTest}）。
 */
public final class LedgerMappers {

    private LedgerMappers() {
    }

    public interface LedgerVoucherMapper extends BaseMapper<PayLedgerVoucher> {
    }

    public interface LedgerEntryMapper extends BaseMapper<PayLedgerEntry> {
    }
}
