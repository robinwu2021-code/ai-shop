package ai.neargo.shop.pay.settle.refund;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.pay.entity.StlBillRefundBasis;
import ai.neargo.shop.pay.entity.StlPayment;
import ai.neargo.shop.pay.mapper.RefundBasisMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.BillMapper;
import ai.neargo.shop.pay.mapper.SettleMappers.PaymentMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 同一账单只插一次，历史补录先锁账单，防止把已扣减金额当作原始版本。 */
@Component
public class MybatisRefundBasisStore implements RefundBasisStore {
    private final RefundBasisMapper bases;
    private final BillMapper bills;
    private final PaymentMapper payments;
    private final RefundBasisHistory history;

    public MybatisRefundBasisStore(RefundBasisMapper bases, BillMapper bills, PaymentMapper payments,
                                   RefundBasisHistory history) {
        this.bases = bases;
        this.bills = bills;
        this.payments = payments;
        this.history = history;
    }

    @Override
    public Optional<SettlementRefundBasis> find(String settleNo) {
        return DataScopeContext.executeWithoutScope(() -> Optional.ofNullable(bases.selectOne(
                Wrappers.<StlBillRefundBasis>lambdaQuery().eq(StlBillRefundBasis::getSettleNo, settleNo)))
                .map(RefundBasisSnapshots::model));
    }

    @Override
    @Transactional("payTxManager")
    public void recordCreated(StlBill bill, long originalGoodsPaidMinor) {
        if (find(bill.getSettleNo()).isPresent()) return;
        insert(bill, originalGoodsPaidMinor, "ORIGINAL", false);
    }

    @Override
    @Transactional(value = "payTxManager", propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void backfill(StlBill bill, long originalGoodsPaidMinor) {
        // 外层刚插入而未提交的账单属于生成事务，独立连接不能等待它自己的锁。
        StlBill committed = DataScopeContext.executeWithoutScope(() -> bills.selectOne(
                Wrappers.<StlBill>lambdaQuery().eq(StlBill::getSettleNo, bill.getSettleNo())));
        if (committed == null) return;
        StlBill locked = DataScopeContext.executeWithoutScope(() -> bills.selectOne(Wrappers.<StlBill>lambdaQuery()
                .eq(StlBill::getSettleNo, bill.getSettleNo()).last("FOR UPDATE")));
        if (locked == null) return;
        var current = DataScopeContext.executeWithoutScope(() -> bases.selectOne(
                Wrappers.<StlBillRefundBasis>lambdaQuery().eq(StlBillRefundBasis::getSettleNo, locked.getSettleNo())
                        .last("FOR UPDATE")));
        if (current != null) return;
        insert(locked, originalGoodsPaidMinor, "LEGACY_UNREFUNDED", history.hasRefunds(locked));
    }

    private void insert(StlBill bill, long goodsPaid, String source, boolean historicalRefunds) {
        List<StlPayment> origins = DataScopeContext.executeWithoutScope(() -> payments.selectList(
                Wrappers.<StlPayment>lambdaQuery().eq(StlPayment::getOrderNo, bill.getOrderNo())
                        .eq(StlPayment::getDirection, StlPayment.PAY).eq(StlPayment::getStatus, StlPayment.SUCCESS)));
        StlPayment origin = origins.size() == 1 ? origins.get(0) : null;
        String reason = historicalRefunds ? "HISTORICAL_REFUNDS" : routeReason(bill, goodsPaid, origin);
        var row = RefundBasisSnapshots.base(bill, source, reason);
        if (!historicalRefunds) {
            RefundBasisSnapshots.amounts(row, bill, goodsPaid);
            RefundBasisSnapshots.account(row, bill, origin);
        }
        DataScopeContext.executeWithoutScope(() -> bases.insert(row));
    }

    private static String routeReason(StlBill bill, long goodsPaid, StlPayment origin) {
        if (goodsPaid < 0) return "INVALID_ORIGINAL_AMOUNT";
        if ("OFFLINE".equals(bill.getPayChannel())) return null;
        if (!StlBill.DIRECT.equals(bill.getFundsMode()) && !StlBill.AGGREGATED.equals(bill.getFundsMode())) {
            return "UNKNOWN_PAYMENT_ROUTE";
        }
        if (blank(bill.getCurrency()) || blank(bill.getPayChannel()) || blank(bill.getPayScene())
                || (!"MERCHANT".equals(bill.getFeeBearer()) && !"PLATFORM".equals(bill.getFeeBearer()))) {
            return "ACCOUNT_NOT_CAPTURED";
        }
        if (goodsPaid + nz(bill.getFreightIncomeMinor()) > 0) {
            if (origin == null) return "ORIGIN_PAYMENT_NOT_UNIQUE";
            if (!Objects.equals(origin.getCurrency(), bill.getCurrency())
                    || !Objects.equals(origin.getPayChannel(), bill.getPayChannel())
                    || !Objects.equals(origin.getPayScene(), bill.getPayScene())
                    || nz(origin.getAmountMinor()) < goodsPaid + nz(bill.getFreightIncomeMinor())) {
                return "ORIGIN_PAYMENT_MISMATCH";
            }
            if ("WECHAT".equals(origin.getPayChannel()) && blank(origin.getWxAppid())) return "ACCOUNT_NOT_CAPTURED";
        }
        if (bill.getPayMerchantNo() == null || bill.getPayMerchantNo().isBlank()) return "ACCOUNT_NOT_CAPTURED";
        return null;
    }

    private static long nz(Long value) { return value == null ? 0 : value; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
