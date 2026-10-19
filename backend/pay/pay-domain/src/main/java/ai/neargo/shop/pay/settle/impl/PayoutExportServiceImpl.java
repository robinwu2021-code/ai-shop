package ai.neargo.shop.pay.settle.impl;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayout;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PayoutMapper;
import ai.neargo.shop.pay.settle.PayoutExportService;
import ai.neargo.shop.pay.settle.SettlementPointsFundingService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;

/** Export retains the caller's data scope and rechecks funding in the transaction that changes export state. */
@Service
@Transactional("payTxManager")
public class PayoutExportServiceImpl implements PayoutExportService {
    private final PayoutMapper payouts;
    private final BillMapper bills;
    private final SettlementPointsFundingService funding;
    public PayoutExportServiceImpl(PayoutMapper payouts,BillMapper bills,SettlementPointsFundingService funding) {
        this.payouts=payouts; this.bills=bills; this.funding=funding;
    }
    @Override public int markExported(Collection<String> numbers,String actor) {
        int count=0;
        for (String no:numbers) {
            var payout=payouts.selectOne(Wrappers.<StlPayout>lambdaQuery()
                    .eq(StlPayout::getPayoutNo,no).last("FOR UPDATE"));
            if (payout==null) { throw BizException.of(ErrorCode.NOT_FOUND); }
            if (!StlPayout.PENDING.equals(payout.getStatus())) { continue; }
            for (var bill:bills.selectList(Wrappers.<StlBill>lambdaQuery().eq(StlBill::getPayoutNo,no))) {
                funding.assertReady(bill.getSettleNo());
            }
            count+=payouts.markExported(payout.getId(),System.currentTimeMillis(),actor);
        }
        return count;
    }
}
