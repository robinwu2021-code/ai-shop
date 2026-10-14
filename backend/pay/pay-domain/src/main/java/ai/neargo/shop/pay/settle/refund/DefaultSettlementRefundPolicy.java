package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import org.springframework.stereotype.Component;

import java.math.BigInteger;

/**
 * 结算口径 A（2026-10-14 拍板）：<b>按货款退款比例</b>回退商家所得、平台少收、补贴收回。
 *
 * <p><b>按累计算，不按单次算</b>：每一项的份额 = ⌊总额 × 退后累计 ÷ 实付⌋ − ⌊总额 × 退前累计 ÷ 实付⌋。
 * 分几次退完，Σ 恰等于总额 —— 尾差自然落在最后一笔，不需要单独的「最后一笔取剩余」分支。
 *
 * <p>运费：商家自寄 → 商家收过的运费由商家回退（不超过收过的数）；平台代寄 → 平台承担，商家不回退。
 */
@Component
public class DefaultSettlementRefundPolicy implements SettlementRefundPolicy {

    @Override
    public BillRefundShare share(StlBill bill, long refundGoodsMinor, long refundFreightMinor, long goodsPaidMinor) {
        long before = nz(bill.getRefundedGoodsMinor());
        long after = before + refundGoodsMinor;
        if (refundGoodsMinor < 0 || refundFreightMinor < 0 || after > Math.max(goodsPaidMinor, 0)) {
            throw new IllegalArgumentException("退款货款超出商品实付：已退 " + before + " + 本次 "
                    + refundGoodsMinor + " > " + goodsPaidMinor);
        }
        Cut cut = new Cut(before, after, goodsPaidMinor);
        long freightIncome = nz(bill.getFreightIncomeMinor());
        long freightCost = nz(bill.getFreightCostMinor());
        long merchantGoods = nz(bill.getNetMinor()) - freightIncome + freightCost;
        return new BillRefundShare(refundGoodsMinor, refundFreightMinor,
                cut.of(merchantGoods), freightReverse(bill, refundFreightMinor, freightIncome),
                cut.of(nz(bill.getSubsidyMinor())),
                cut.of(nz(bill.getGrossMinor())), cut.of(nz(bill.getCommissionMinor())),
                cut.of(nz(bill.getServiceFeeMinor())), cut.of(nz(bill.getPointsFeeMinor())),
                cut.of(nz(bill.getPointsCostMinor())), cut.isFinal());
    }

    private static long freightReverse(StlBill bill, long refundFreightMinor, long freightIncome) {
        if (SettleSourcePort.SHIP_PLATFORM_CALL.equals(bill.getFreightShipMode())) {
            return 0L;
        }
        return Math.min(refundFreightMinor, Math.max(freightIncome, 0L));
    }

    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    /** 累计比例切分：退前 / 退后累计相对分母的两个下取整之差。分母为 0（0 元单）视为一次退完。 */
    private record Cut(long before, long after, long base) {

        boolean isFinal() {
            return base <= 0 || after >= base;
        }

        long of(long total) {
            if (base <= 0) {
                return total;
            }
            return floor(total, after) - floor(total, before);
        }

        private long floor(long total, long part) {
            return BigInteger.valueOf(total).multiply(BigInteger.valueOf(part))
                    .divide(BigInteger.valueOf(base)).longValueExact();
        }
    }
}
