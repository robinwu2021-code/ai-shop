package ai.neargo.shop.pay.settle.refund;

import ai.neargo.shop.pay.entity.StlBill;
import ai.neargo.shop.spi.trade.SettleSourcePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 结算口径 A：按累计比例切分，分几次退完 Σ 恰等于总额；运费按发货方式归属。 */
@DisplayName("结算口径 A")
class DefaultSettlementRefundPolicyTest {

    private final SettlementRefundPolicy policy = new DefaultSettlementRefundPolicy();

    /** 商品实付 10000、运费 800；佣金 503、服务费 101、补贴 333；商家所得 = 10000-503-101+800 = 10196 */
    private static StlBill bill(String shipMode) {
        StlBill b = new StlBill();
        b.setGrossMinor(10_333L);
        b.setCommissionMinor(503L);
        b.setServiceFeeMinor(101L);
        b.setPointsFeeMinor(7L);
        b.setSubsidyMinor(333L);
        b.setPointsCostMinor(0L);
        b.setFreightIncomeMinor(800L);
        b.setFreightCostMinor(SettleSourcePort.SHIP_PLATFORM_CALL.equals(shipMode) ? 800L : 0L);
        b.setFreightShipMode(shipMode);
        b.setNetMinor(10_333L - 503 - 101 - 7 + 800 - b.getFreightCostMinor());
        b.setRefundedGoodsMinor(0L);
        return b;
    }

    @Test
    @DisplayName("★★★ 三次分批退完：Σ 商家回退 = 商家货款所得、Σ 补贴 / 佣金 = 总额；最后一笔才是 final")
    void threeInstallmentsSumExactly() {
        StlBill b = bill(SettleSourcePort.SHIP_MERCHANT_SELF);
        long merchantGoods = b.getNetMinor() - 800;
        long sumMerchant = 0, sumSubsidy = 0, sumCommission = 0, sumGross = 0;
        long[] parts = {3333, 3333, 3334};
        for (int i = 0; i < parts.length; i++) {
            BillRefundShare s = policy.share(b, parts[i], 0, 10_000);
            assertThat(s.finalRefund()).isEqualTo(i == parts.length - 1);
            sumMerchant += s.merchantGoodsMinor();
            sumSubsidy += s.subsidyReturnMinor();
            sumCommission += s.commissionCutMinor();
            sumGross += s.grossCutMinor();
            b.setRefundedGoodsMinor(b.getRefundedGoodsMinor() + parts[i]);
        }
        assertThat(sumMerchant).isEqualTo(merchantGoods);
        assertThat(sumSubsidy).isEqualTo(333);
        assertThat(sumCommission).isEqualTo(503);
        assertThat(sumGross).isEqualTo(10_333);
    }

    @Test
    @DisplayName("★★ 退 1/3：商家回退 = 商家货款所得 × 1/3（下取整），未退完")
    void oneThird() {
        StlBill b = bill(SettleSourcePort.SHIP_MERCHANT_SELF);
        BillRefundShare s = policy.share(b, 3000, 0, 9000);
        assertThat(s.merchantGoodsMinor()).isEqualTo((b.getNetMinor() - 800) / 3);
        assertThat(s.finalRefund()).isFalse();
    }

    @Test
    @DisplayName("★★ 运费：商家自寄由商家回退（不超过收过的数）；平台代寄商家不回退")
    void freightByShipMode() {
        assertThat(policy.share(bill(SettleSourcePort.SHIP_MERCHANT_SELF), 10_000, 900, 10_000)
                .freightReverseMinor()).isEqualTo(800);
        assertThat(policy.share(bill(SettleSourcePort.SHIP_PLATFORM_CALL), 10_000, 800, 10_000)
                .freightReverseMinor()).isZero();
    }

    @Test
    @DisplayName("★ 一次全退：商家回退 = 结算单商家所得全额（与旧的整单回退金额一致）")
    void fullRefundEqualsLegacy() {
        StlBill b = bill(SettleSourcePort.SHIP_MERCHANT_SELF);
        assertThat(policy.share(b, 10_000, 800, 10_000).merchantReverseMinor()).isEqualTo(b.getNetMinor());
    }

    @Test
    @DisplayName("★ 0 元单：分母为 0 视为一次退完，取全额")
    void zeroPaid() {
        StlBill b = bill(SettleSourcePort.SHIP_MERCHANT_SELF);
        BillRefundShare s = policy.share(b, 0, 0, 0);
        assertThat(s.finalRefund()).isTrue();
        assertThat(s.subsidyReturnMinor()).isEqualTo(333);
    }

    @Test
    @DisplayName("★ 累计超过商品实付 → 拒绝（账本上游已挡，这里不静默截断）")
    void overRefundRejected() {
        StlBill b = bill(SettleSourcePort.SHIP_MERCHANT_SELF);
        b.setRefundedGoodsMinor(9000L);
        assertThatThrownBy(() -> policy.share(b, 1001, 0, 10_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
