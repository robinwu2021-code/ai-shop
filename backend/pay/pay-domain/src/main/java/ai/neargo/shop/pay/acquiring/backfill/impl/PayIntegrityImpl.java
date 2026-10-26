package ai.neargo.shop.pay.acquiring.backfill.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.acquiring.backfill.PayIntegrity;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.ledger.entity.PayLedgerEntry;
import ai.neargo.shop.pay.ledger.entity.PayLedgerVoucher;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerEntryMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerVoucherMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
        List<PayLedgerVoucher> all = DataScopeContext.executeWithoutScope(() -> vouchers.selectList(null));
        Map<Long, List<PayLedgerEntry>> lines = DataScopeContext.executeWithoutScope(() -> entries.selectList(null))
                .stream().collect(Collectors.groupingBy(PayLedgerEntry::getPayLedgerVoucherId));
        return all.stream().filter(v -> {
            List<PayLedgerEntry> ls = lines.getOrDefault(v.getId(), List.of());
            long dr = ls.stream().filter(e -> "DR".equals(e.getDirection())).mapToLong(PayLedgerEntry::getAmountMinor).sum();
            long cr = ls.stream().filter(e -> "CR".equals(e.getDirection())).mapToLong(PayLedgerEntry::getAmountMinor).sum();
            return dr != cr || v.getTotalMinor() == null || v.getTotalMinor() != dr;
        }).count();
    }
}
