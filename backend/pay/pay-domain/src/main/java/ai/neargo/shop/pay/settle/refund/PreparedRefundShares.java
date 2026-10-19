package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import org.springframework.stereotype.Component;

/** Cumulative cuts must use original amounts, never an already reduced payable. */
@Component
public class PreparedRefundShares {
    private final RefundBasisStore bases;
    private final BillRefundJournal journal;
    private final SettlementRefundPolicy policy;

    public PreparedRefundShares(RefundBasisStore bases, BillRefundJournal journal, SettlementRefundPolicy policy) {
        this.bases = bases;
        this.journal = journal;
        this.policy = policy;
    }

    public BillRefundShare share(StlBill bill, SplitReversal command) {
        var basis = bases.find(bill.getSettleNo()).orElse(null);
        StlBill amounts = bill;
        if (basis != null) {
            require(basis.ready(), "SETTLEMENT_REFUND_BASIS_REVIEW_REQUIRED");
            require(basis.goodsPaidMinor() != null && basis.goodsPaidMinor() == command.goodsPaidMinor()
                    && command.subOrderNo().equals(basis.subOrderNo()), "SETTLEMENT_REFUND_BASIS_MISMATCH");
            require(basis.grossMinor() != null && basis.merchantGoodsMinor() != null
                    && basis.commissionMinor() != null && basis.serviceFeeMinor() != null
                    && basis.pointsFeeMinor() != null && basis.pointsCostMinor() != null
                    && basis.subsidyMinor() != null && basis.freightIncomeMinor() != null
                    && basis.freightCostMinor() != null, "SETTLEMENT_REFUND_BASIS_INCOMPLETE");
            amounts = new StlBill();
            amounts.setGrossMinor(basis.grossMinor());
            amounts.setNetMinor(Math.subtractExact(Math.addExact(basis.merchantGoodsMinor(),
                    basis.freightIncomeMinor()), basis.freightCostMinor()));
            amounts.setCommissionMinor(basis.commissionMinor());
            amounts.setServiceFeeMinor(basis.serviceFeeMinor());
            amounts.setPointsFeeMinor(basis.pointsFeeMinor());
            amounts.setPointsCostMinor(basis.pointsCostMinor());
            amounts.setSubsidyMinor(basis.subsidyMinor());
            amounts.setFreightIncomeMinor(basis.freightIncomeMinor());
            amounts.setFreightCostMinor(basis.freightCostMinor());
            amounts.setFreightShipMode(basis.freightShipMode());
            amounts.setRefundedGoodsMinor(bill.getRefundedGoodsMinor());
        } else {
            boolean amountsRetained = bill.getPaidAt() != null || StlBill.PAID.equals(bill.getStatus())
                    || StlBill.SPLIT.equals(bill.getStatus()) || StlBill.SPLIT_CONFIRMED.equals(bill.getStatus())
                    || StlBill.MANUAL.equals(bill.getStatus()) && bill.getSplitAt() != null;
            long before = bill.getRefundedGoodsMinor() == null ? 0 : bill.getRefundedGoodsMinor();
            require(before == 0 || amountsRetained && journal.preSplitGoods(bill.getSettleNo()) == 0,
                    "SETTLEMENT_REFUND_BASIS_REQUIRED");
        }
        return policy.share(amounts, command.goodsMinor(), command.freightMinor(), command.goodsPaidMinor());
    }

    private void require(boolean valid, String message) {
        if (!valid) throw new IllegalStateException(message);
    }
}
