package ai.neargo.shop.pay.ledger.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.pay.ledger.LedgerAccount;
import ai.neargo.shop.pay.ledger.LedgerVoucher;
import ai.neargo.shop.pay.ledger.entity.PayLedgerEntry;
import ai.neargo.shop.pay.ledger.entity.PayLedgerVoucher;
import ai.neargo.shop.pay.ledger.service.LedgerService;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerEntryMapper;
import ai.neargo.shop.pay.mapper.ledger.LedgerMappers.LedgerVoucherMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** {@link LedgerService} 的实现（TDD-支付服务-I3支付单退款流水 S5）：只 insert，幂等靠 {@code uk idem_key} */
@Service
public class LedgerServiceImpl implements LedgerService {

    private final LedgerVoucherMapper vouchers;
    private final LedgerEntryMapper entries;

    public LedgerServiceImpl(LedgerVoucherMapper vouchers, LedgerEntryMapper entries) {
        this.vouchers = vouchers;
        this.entries = entries;
    }

    @Override
    public Long post(LedgerVoucher v) {
        return insert(v, null);
    }

    private Long insert(LedgerVoucher v, Long reversalOfId) {
        PayLedgerVoucher existing = byIdem(v.idemKey());
        if (existing != null) {
            return existing.getId();
        }
        PayLedgerVoucher row = new PayLedgerVoucher();
        row.setVoucherNo(BizKey.next(BizKey.LEDGER_VOUCHER));
        row.setVoucherType(v.voucherType());
        row.setRefType(v.refType());
        row.setRefId(v.refId());
        row.setIdemKey(v.idemKey());
        row.setMarket(v.market());
        row.setCurrency(v.currency());
        row.setTotalMinor(v.total());
        row.setBizDate(v.bizDate());
        row.setFundsPath(v.fundsPath());
        row.setSourceType(v.sourceType());
        row.setSourceId(v.sourceId());
        row.setMemo(v.memo());
        row.setReversalOfId(reversalOfId);
        try {
            DataScopeContext.executeWithoutScope(() -> vouchers.insert(row));
        } catch (DuplicateKeyException concurrent) {
            return byIdem(v.idemKey()).getId();
        }
        int n = 0;
        for (LedgerVoucher.Line l : v.lines()) {
            PayLedgerEntry e = new PayLedgerEntry();
            e.setPayLedgerVoucherId(row.getId());
            e.setLineNo(++n);
            e.setAccount(l.account().name());
            e.setDirection(l.direction().name());
            e.setAmountMinor(l.amountMinor());
            e.setCurrency(v.currency());
            e.setStoreId(l.storeId());
            e.setPayMerchantId(l.payMerchantId());
            e.setPayChannel(l.payChannel());
            e.setMarket(v.market());
            e.setFundsPath(v.fundsPath());
            e.setPayOrderId(l.payOrderId());
            e.setBizDate(v.bizDate());
            DataScopeContext.executeWithoutScope(() -> entries.insert(e));
        }
        return row.getId();
    }

    @Override
    public Long reverse(Long voucherId, String reason) {
        PayLedgerVoucher original = DataScopeContext.executeWithoutScope(() -> vouchers.selectById(voucherId));
        List<PayLedgerEntry> lines = DataScopeContext.executeWithoutScope(() -> entries.selectList(
                Wrappers.<PayLedgerEntry>lambdaQuery().eq(PayLedgerEntry::getPayLedgerVoucherId, voucherId)
                        .orderByAsc(PayLedgerEntry::getLineNo)));
        List<LedgerVoucher.Line> flipped = new ArrayList<>();
        for (PayLedgerEntry e : lines) {
            flipped.add(new LedgerVoucher.Line(LedgerAccount.valueOf(e.getAccount()),
                    "DR".equals(e.getDirection()) ? LedgerVoucher.Direction.CR : LedgerVoucher.Direction.DR,
                    e.getAmountMinor(), e.getStoreId(), e.getPayMerchantId(), e.getPayChannel(), e.getPayOrderId()));
        }
        return insert(new LedgerVoucher("REVERSAL", original.getRefType(), original.getRefId(),
                "REVERSAL|" + original.getIdemKey(), original.getMarket(), original.getCurrency(), original.getBizDate(),
                original.getFundsPath(), original.getSourceType(), original.getSourceId(), reason, flipped), voucherId);
    }

    private PayLedgerVoucher byIdem(String idemKey) {
        return DataScopeContext.executeWithoutScope(() -> vouchers.selectOne(Wrappers.<PayLedgerVoucher>lambdaQuery()
                .eq(PayLedgerVoucher::getIdemKey, idemKey)));
    }
}
