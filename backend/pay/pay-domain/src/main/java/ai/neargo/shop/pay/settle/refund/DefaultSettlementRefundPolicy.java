package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.spi.trade.SettleSourcePort;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

/** 固定原始基数的累计差额；当前账单金额仅是结果，不再参与比例计算。 */
@Component
public class DefaultSettlementRefundPolicy implements SettlementRefundPolicy {
    @Override
    public BillRefundShare share(SettlementRefundBasis basis, RefundPosition before,
                                 long refundGoodsMinor, long refundFreightMinor) {
        if (!basis.ready()) throw new IllegalStateException("Original settlement basis needs review");
        long base = known(basis.goodsPaidMinor());
        long after = Math.addExact(before.goodsMinor(), refundGoodsMinor);
        long freightAfter = Math.addExact(before.freightMinor(), refundFreightMinor);
        if (before.goodsMinor() < 0 || before.freightMinor() < 0 || refundGoodsMinor < 0 || refundFreightMinor < 0
                || after > base || freightAfter > known(basis.freightIncomeMinor())) {
            throw new IllegalArgumentException("Refund exceeds original goods/freight amount");
        }
        Cut cut = new Cut(before.goodsMinor(), after, base);
        boolean zero = base == 0;
        boolean review = zero && (known(basis.grossMinor()) > 0 || known(basis.merchantGoodsMinor()) > 0
                || known(basis.pointsFeeMinor()) > 0 || known(basis.pointsCostMinor()) > 0
                || known(basis.subsidyMinor()) > 0);
        long freightReverse = SettleSourcePort.SHIP_PLATFORM_CALL.equals(basis.freightShipMode())
                ? 0 : refundFreightMinor;
        return new BillRefundShare(refundGoodsMinor, refundFreightMinor, cut.of(known(basis.merchantGoodsMinor())),
                freightReverse, cut.of(known(basis.subsidyMinor())), cut.of(known(basis.grossMinor())),
                cut.of(known(basis.commissionMinor())), cut.of(known(basis.serviceFeeMinor())),
                cut.of(known(basis.pointsFeeMinor())), cut.of(known(basis.pointsCostMinor())), after == base,
                zero ? BillRefundShare.CashAllocation.NONE : BillRefundShare.CashAllocation.PROPORTIONAL, review);
    }

    private static long known(Long value) {
        if (value == null || value < 0) throw new IllegalStateException("Original amount needs review");
        return value;
    }

    private record Cut(long before, long after, long base) {
        long of(long total) {
            if (base == 0) return 0;
            return floor(total, after) - floor(total, before);
        }

        private long floor(long total, long part) {
            return BigInteger.valueOf(total).multiply(BigInteger.valueOf(part))
                    .divide(BigInteger.valueOf(base)).longValueExact();
        }
    }
}
