package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.entity.StlBillRefund;
import ai.neargo.shop.pay.mapper.BillRefundMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;

/** 同支付域串行锁内消费已成功现金退款；计划全量验证后再修改，未知历史只留核对。 */
@Component
public class SettlementRefundInitializer {
    private final RefundBasisStore bases;
    private final PaymentMapper payments;
    private final BillMapper bills;
    private final BillRefundMapper refunds;
    private final SettlementRefundPolicy policy;
    private final PreSplitAdjuster adjuster;

    public SettlementRefundInitializer(RefundBasisStore bases, PaymentMapper payments, BillMapper bills,
                                      BillRefundMapper refunds, SettlementRefundPolicy policy,
                                      PreSplitAdjuster adjuster) {
        this.bases = bases; this.payments = payments; this.bills = bills;
        this.refunds = refunds; this.policy = policy; this.adjuster = adjuster;
    }

    /** 原收款锁由生成或到账入口持有；不能在网络请求前后持此锁。 */
    public void reconcile(StlBill bill) {
        List<StlPayment> cash = DataScopeContext.executeWithoutScope(() -> payments.selectList(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getOrderNo, bill.getOrderNo())
                        .eq(StlPayment::getDirection, StlPayment.REFUND)
                        .notIn(StlPayment::getStatus, StlPayment.FAILED, StlPayment.CLOSED)
                        .and(q -> q.eq(StlPayment::getSubOrderNo, bill.getSubOrderNo())
                                .or().isNull(StlPayment::getSubOrderNo))
                        .orderByAsc(StlPayment::getId).last("FOR UPDATE")));
        if (cash.isEmpty()) return;
        var basis = bases.find(bill.getSettleNo()).orElse(null);
        if (basis == null || !basis.ready()) { review(bill, "INITIAL_REFUND_BASIS_NEEDS_REVIEW"); return; }
        List<Allocation> allocations;
        try { allocations = plan(bill, basis, cash); }
        catch (IllegalArgumentException | IllegalStateException | ArithmeticException e) {
            review(bill, "INITIAL_REFUND_NEEDS_REVIEW: " + e.getMessage()); return;
        }
        if (allocations.isEmpty()) return;
        if (bill.getSplitAt() != null || StlBill.PAID.equals(bill.getStatus()) || bill.getPayoutNo() != null) {
            review(bill, "INITIAL_REFUND_HAS_PAYMENT_INTENT"); return;
        }
        for (Allocation allocation : allocations) {
            BillRefundShare cut = allocation.share();
            if (!adjuster.adjust(bill, cut, allocation.afterSaleNo())) {
                throw new IllegalStateException("Validated initial refund has inconsistent financial position");
            }
            advance(bill, cut);
        }
    }

    private List<Allocation> plan(StlBill bill, SettlementRefundBasis basis, List<StlPayment> cash) {
        var planned = new ArrayList<Allocation>();
        long goods = nz(bill.getRefundedGoodsMinor()), freight = nz(bill.getRefundedFreightMinor());
        Set<String> seen = new HashSet<>();
        for (StlPayment row : cash) {
            if (blank(row.getAfterSaleNo())) throw new IllegalStateException("MISSING_AFTER_SALE_KEY");
            if (!seen.add(row.getAfterSaleNo())) throw new IllegalStateException("DUPLICATE_AFTER_SALE_KEY");
            context(bill, row);
            var applied = DataScopeContext.executeWithoutScope(() -> refunds.selectOne(
                    Wrappers.<StlBillRefund>lambdaQuery().eq(StlBillRefund::getAfterSaleNo, row.getAfterSaleNo())));
            if (applied != null) {
                validateReplay(bill, row, applied);
                continue;
            }
            if (!StlPayment.SUCCESS.equals(row.getStatus())) throw new IllegalStateException("CASH_UNCONFIRMED");
            RefundPosition part = components(row, basis);
            var cut = policy.share(basis, new RefundPosition(goods, freight), part.goodsMinor(), part.freightMinor());
            if (cut.requiresReview()) throw new IllegalStateException("NON_CASH_POLICY_UNCONFIRMED");
            goods = Math.addExact(goods, part.goodsMinor()); freight = Math.addExact(freight, part.freightMinor());
            planned.add(new Allocation(row.getAfterSaleNo(), cut));
        }
        long merchantCut = planned.stream().mapToLong(a -> a.share().merchantReverseMinor()).sum();
        if (merchantCut > bill.getNetMinor()) throw new IllegalStateException("NEGATIVE_REMAINING_PAYABLE");
        return planned;
    }

    private static void context(StlBill bill, StlPayment cash) {
        if (!Objects.equals(cash.getSubOrderNo(), bill.getSubOrderNo())
                || !Objects.equals(cash.getPayChannel(), bill.getPayChannel())
                || !Objects.equals(cash.getPayScene(), bill.getPayScene())
                || !Objects.equals(cash.getCurrency(), bill.getCurrency())) {
            throw new IllegalStateException("CASH_CONTEXT_MISMATCH");
        }
    }

    private static void validateReplay(StlBill bill, StlPayment cash, StlBillRefund applied) {
        boolean match = Objects.equals(applied.getSettleNo(), bill.getSettleNo())
                && Objects.equals(applied.getSubOrderNo(), bill.getSubOrderNo()) && BillRefundJournal.complete(applied)
                && Math.addExact(applied.getGoodsMinor(), applied.getFreightMinor()) == nz(cash.getAmountMinor());
        if (cash.getRefundGoodsMinor() != null || cash.getRefundFreightMinor() != null) {
            match &= Objects.equals(applied.getGoodsMinor(), cash.getRefundGoodsMinor())
                    && Objects.equals(applied.getFreightMinor(), cash.getRefundFreightMinor());
        }
        if (!match) throw new IllegalStateException("REFUND_REPLAY_CONTEXT_MISMATCH");
    }

    private static RefundPosition components(StlPayment row, SettlementRefundBasis basis) {
        Long goods = row.getRefundGoodsMinor(), freight = row.getRefundFreightMinor();
        long amount = nz(row.getAmountMinor());
        if (goods == null && freight == null && basis.freightIncomeMinor() == 0) return new RefundPosition(amount, 0);
        if (goods == null || freight == null || goods < 0 || freight < 0 || Math.addExact(goods, freight) != amount) {
            throw new IllegalStateException("UNKNOWN_GOODS_FREIGHT_BREAKDOWN");
        }
        return new RefundPosition(goods, freight);
    }

    private static void advance(StlBill bill, BillRefundShare cut) {
        bill.setRefundedGoodsMinor(nz(bill.getRefundedGoodsMinor()) + cut.goodsMinor());
        bill.setRefundedFreightMinor(nz(bill.getRefundedFreightMinor()) + cut.freightMinor());
        bill.setNetMinor(bill.getNetMinor() - cut.merchantReverseMinor());
        bill.setPointsFeeMinor(nz(bill.getPointsFeeMinor()) - cut.pointsFeeCutMinor());
        bill.setGrossMinor(bill.getGrossMinor() - cut.grossCutMinor());
        bill.setCommissionMinor(bill.getCommissionMinor() - cut.commissionCutMinor());
        bill.setServiceFeeMinor(nz(bill.getServiceFeeMinor()) - cut.serviceFeeCutMinor());
        bill.setSubsidyMinor(nz(bill.getSubsidyMinor()) - cut.subsidyReturnMinor());
        bill.setPointsCostMinor(nz(bill.getPointsCostMinor()) - cut.pointsCostCutMinor());
        bill.setFreightIncomeMinor(nz(bill.getFreightIncomeMinor()) - cut.freightReverseMinor());
        bill.setSplitAmountMinor(nz(bill.getSplitAmountMinor()) - cut.platformCutMinor());
        if (bill.getNetMinor() == 0) bill.setStatus(StlBill.REVERSED);
    }

    private void review(StlBill bill, String reason) {
        String old = bill.getStatus();
        bill.setLastError(reason);
        DataScopeContext.executeWithoutScope(() -> refunds.markManual(bill.getSettleNo(), old, reason));
        bill.setStatus(StlBill.MANUAL);
    }

    private record Allocation(String afterSaleNo, BillRefundShare share) { }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static long nz(Long value) { return value == null ? 0 : value; }
}
