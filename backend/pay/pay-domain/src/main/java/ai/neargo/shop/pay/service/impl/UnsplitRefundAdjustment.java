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

import java.util.Objects;

/** Only a local settlement journal is confirmation; legacy stub acknowledgements are never cash authority. */
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
                return replayed(previous, bill, request);
            }
            boolean splitDispatched = bill.getSplitAt() != null
                    || StlBill.SPLITTING.equals(bill.getStatus())
                    || StlBill.SPLIT.equals(bill.getStatus())
                    || StlBill.SPLIT_CONFIRMED.equals(bill.getStatus())
                    || StlBill.MANUAL.equals(bill.getStatus()) && bill.getSplitAt() != null;
            if (splitDispatched) {
                return coordinator.reverse(request);
            }
            if (bill.getPaidAt() != null || bill.getPayoutNo() != null || StlBill.PAID.equals(bill.getStatus())
                    || StlBill.REVERSED.equals(bill.getStatus())) {
                return false;
            }
            return coordinator.reverse(request);
        });
    }

    /**
     * 本售后单已有结算流水 = 上一次回退已提交（分账前扣减、或分账后通道回退成功后才落的那行），
     * 续跑时它就是确认。分账前 / 分账后两种流水都算：只认 preSplit 的话，崩在「回退已提交、
     * 执行单还没转 CASH」之间的分账后退款永远停在 REVERSE_UNCONFIRMED，买家的钱再也退不出去。
     *
     * <p>流水必须就是这一笔：同一张结算单、同一子单、货款与运费一致。对不上说明方案被改过或串了单，
     * 不放行现金。
     */
    private static boolean replayed(StlBillRefund previous, StlBill bill, SplitReversal request) {
        return Objects.equals(previous.getSettleNo(), bill.getSettleNo())
                && Objects.equals(previous.getSubOrderNo(), request.subOrderNo())
                && Objects.equals(previous.getGoodsMinor(), request.goodsMinor())
                && Objects.equals(previous.getFreightMinor(), request.freightMinor());
    }
}
