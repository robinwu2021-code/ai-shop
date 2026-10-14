package ai.neargo.shop.trade.order.refund.policy;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/** 行实付分摊（TDD-订单状态机 AC19 / AC20）。 */
class LineSharesTest {

    @Test
    @DisplayName("★★★ Σ 份额恰等于总额 —— 三行等权 100 元分不尽，零头不丢")
    void sumIsExact() {
        long[] s = LineShares.allocate(10000L, new long[]{1, 1, 1});
        assertThat(LongStream.of(s).sum()).isEqualTo(10000L);
        assertThat(s).containsExactly(3334L, 3333L, 3333L);
    }

    @Test
    @DisplayName("按权重比例：4980×2 与 5800，优惠后实付 14000")
    void proportional() {
        long[] s = LineShares.allocate(14000L, new long[]{9960L, 5800L});
        assertThat(LongStream.of(s).sum()).isEqualTo(14000L);
        // 9960/15760 × 14000 = 8847.7… → 8847 或 8848；5800 那行 5152.2…
        assertThat(s[0]).isBetween(8847L, 8848L);
    }

    @Test
    @DisplayName("权重为 0 的行（赠品）份额为 0；全部为 0 时全 0")
    void zeroWeights() {
        assertThat(LineShares.allocate(500L, new long[]{0, 1000, 0})).containsExactly(0L, 500L, 0L);
        assertThat(LineShares.allocate(500L, new long[]{0, 0})).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("总额 ≤ 0（全被积分抵完）→ 全 0")
    void nonPositiveTotal() {
        assertThat(LineShares.allocate(0L, new long[]{100, 200})).containsExactly(0L, 0L);
    }

    @Test
    @DisplayName("★★ 存量行没有快照 → 用同一算法现算；有快照 → 用快照")
    void legacyAndSnapshot() {
        OrdSubOrder sub = new OrdSubOrder();
        sub.setPayAmount(10500L);
        sub.setFreightAmount(500L);
        OrdItem a = item(1L, 6000L, 0L);
        OrdItem b = item(2L, 6000L, 0L);
        assertThat(LineShares.of(sub, List.of(a, b))).containsEntry(1L, 5000L).containsEntry(2L, 5000L);

        a.setPaidShareMinor(7000L);
        b.setPaidShareMinor(3000L);
        assertThat(LineShares.of(sub, List.of(a, b))).containsEntry(1L, 7000L).containsEntry(2L, 3000L);
    }

    private static OrdItem item(long id, long amount, long share) {
        OrdItem it = new OrdItem();
        it.setId(id);
        it.setAmount(amount);
        it.setPaidShareMinor(share);
        return it;
    }
}
