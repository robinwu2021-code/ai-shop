package ai.neargo.shop.pay.acquiring.backfill.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.acquiring.backfill.PayIntegrity;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerEntryMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerVoucherMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.stereotype.Service;

/** {@link PayIntegrity} 的实现 */
@Service
public class PayIntegrityImpl implements PayIntegrity {

    private final PaymentMapper attempts;
    private final LedgerVoucherMapper vouchers;
    private final LedgerEntryMapper entries;

    public PayIntegrityImpl(PaymentMapper attempts, LedgerVoucherMapper vouchers, LedgerEntryMapper entries) {
        this.attempts = attempts;
        this.vouchers = vouchers;
        this.entries = entries;
    }

    @Override
    public long unlinkedAttempts(LocalDateTime since) {
        return DataScopeContext.executeWithoutScope(() -> attempts.selectCount(Wrappers.<StlPayment>lambdaQuery()
                .eq(StlPayment::getDirection, StlPayment.PAY).isNull(StlPayment::getPayOrderId)
                .ge(StlPayment::getCreatedAt, since)));
    }

    @Override
    public long unbalancedVouchers() {
        // 在库里聚合（W3）：此前把全部凭证与流水 selectList 进内存再分组，账一多就是一次 OOM
        return DataScopeContext.executeWithoutScope(vouchers::countUnbalanced);
    }

    @Override
    public List<String> ledgerMismatchedOrders(int limit) {
        return DataScopeContext.executeWithoutScope(() -> entries.ledgerMismatchedOrders(Math.max(1, Math.min(limit, 10_000))));
    }
}
