package ai.neargo.shop.pay.service.impl;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlBillRefund;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.settle.refund.SettlementRefundCoordinator;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Only a local unsplit journal is confirmation; legacy stub acknowledgements are never cash authority. */
@Component
public class UnsplitRefundAdjustment {
    private final BillMapper bills;
    private final BillRefundMapper journals;
    private final SettlementRefundCoordinator coordinator;

    public UnsplitRefundAdjustment(BillMapper bills, BillRefundMapper journals,
                                    SettlementRefundCoordinator coordinator) {
        this.bills = bills;
        this.journals = journals;
        this.coordinator = coordinator;
    }

    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.READ_COMMITTED)
    public boolean prepare(SplitReversal request, boolean zeroLiability) {
        return DataScopeContext.executeWithoutScope(() -> {
            StlBill bill = bills.selectOne(Wrappers.<StlBill>lambdaQuery()
                    .eq(StlBill::getSubOrderNo, request.subOrderNo()).last("FOR UPDATE"));
            if (bill == null) {
                return zeroLiability && request.goodsMinor() == 0 && request.freightMinor() == 0;
            }
            StlBillRefund previous = journals.selectOne(Wrappers.<StlBillRefund>lambdaQuery()
                    .eq(StlBillRefund::getAfterSaleNo, request.afterSaleNo()));
            if (previous != null) {
                return Boolean.TRUE.equals(previous.getPreSplit())
                        && previous.getGoodsMinor() == request.goodsMinor()
                        && previous.getFreightMinor() == request.freightMinor();
            }
            boolean splitDispatched = bill.getSplitAt() != null
                    || StlBill.SPLITTING.equals(bill.getStatus())
                    || StlBill.SPLIT.equals(bill.getStatus())
                    || StlBill.SPLIT_CONFIRMED.equals(bill.getStatus())
                    || StlBill.MANUAL.equals(bill.getStatus()) && bill.getSplitAt() != null;
            if (splitDispatched) {
                return coordinator.reverse(request);
            }
            if (bill.getPaidAt() != null || bill.getPayoutNo() != null || StlBill.REVERSED.equals(bill.getStatus())) {
                return false;
            }
            return coordinator.reverse(request);
        });
    }
}
