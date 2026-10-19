package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlBillRefundBasis;
import ai.neargo.shop.pay.entity.StlPayment;

import java.time.LocalDateTime;

/** 只复制生成时确定的值，绝不重新查费率或商家账户来改历史。 */
final class RefundBasisSnapshots {
    private RefundBasisSnapshots() { }

    static StlBillRefundBasis base(StlBill bill, String source, String reason) {
        var row = new StlBillRefundBasis();
        row.setSettleNo(bill.getSettleNo());
        row.setSubOrderNo(bill.getSubOrderNo());
        row.setBasisVersion(1);
        row.setSource(source);
        row.setStatus(reason == null ? SettlementRefundBasis.READY : SettlementRefundBasis.NEEDS_REVIEW);
        row.setReviewReason(reason);
        row.setTenantNo(bill.getTenantNo());
        row.setCreatedAt(LocalDateTime.now());
        return row;
    }

    static void amounts(StlBillRefundBasis row, StlBill bill, long goodsPaid) {
        row.setGoodsPaidMinor(goodsPaid);
        row.setGrossMinor(bill.getGrossMinor());
        row.setCommissionMinor(bill.getCommissionMinor());
        row.setServiceFeeMinor(bill.getServiceFeeMinor());
        row.setPointsFeeMinor(nz(bill.getPointsFeeMinor()));
        row.setPointsCostMinor(nz(bill.getPointsCostMinor()));
        row.setSubsidyMinor(nz(bill.getSubsidyMinor()));
        row.setFreightIncomeMinor(nz(bill.getFreightIncomeMinor()));
        row.setFreightCostMinor(nz(bill.getFreightCostMinor()));
        row.setMerchantGoodsMinor(bill.getNetMinor() - row.getFreightIncomeMinor() + row.getFreightCostMinor());
        row.setFreightShipMode(bill.getFreightShipMode());
    }

    static void account(StlBillRefundBasis row, StlBill bill, StlPayment origin) {
        row.setPaymentRoute("OFFLINE".equals(bill.getPayChannel()) ? "OFFLINE" : bill.getFundsMode());
        row.setOriginPaymentNo(origin == null ? null : origin.getPaymentNo());
        row.setAccountSnapshotRef(bill.getSettleNo() + "#1");
        row.setPayMerchantNo(bill.getPayMerchantNo());
        row.setPayChannel(bill.getPayChannel());
        row.setPayScene(bill.getPayScene());
        row.setCurrency(bill.getCurrency());
        row.setFeeBearer(bill.getFeeBearer());
        row.setWxAppid(origin == null ? null : origin.getWxAppid());
    }

    static SettlementRefundBasis model(StlBillRefundBasis row) {
        SettlementAccountSnapshot account = row.getAccountSnapshotRef() == null ? null : new SettlementAccountSnapshot(
                row.getPayMerchantNo(), row.getPayChannel(), row.getPayScene(), row.getCurrency(),
                row.getFeeBearer(), row.getWxAppid());
        return new SettlementRefundBasis(row.getSettleNo(), row.getSubOrderNo(), row.getBasisVersion(),
                row.getSource(), row.getStatus(), row.getReviewReason(), row.getGoodsPaidMinor(), row.getGrossMinor(),
                row.getMerchantGoodsMinor(), row.getCommissionMinor(), row.getServiceFeeMinor(),
                row.getPointsFeeMinor(), row.getPointsCostMinor(), row.getSubsidyMinor(),
                row.getFreightIncomeMinor(), row.getFreightCostMinor(),
                row.getFreightShipMode(), row.getPaymentRoute(), row.getOriginPaymentNo(), row.getAccountSnapshotRef(),
                account);
    }

    private static long nz(Long value) { return value == null ? 0 : value; }
}
