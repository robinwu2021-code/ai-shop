package ai.neargo.shop.fulfillment;

import ai.neargo.shop.spi.fulfillment.FreightPort;
import ai.neargo.shop.spi.fulfillment.FreightPort.Part;
import ai.neargo.shop.spi.fulfillment.FreightPort.Quote;
import ai.neargo.shop.spi.fulfillment.FreightPort.Rule;
import ai.neargo.shop.spi.fulfillment.FreightPort.Template;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 多模板合并（淘宝式，ADR-031 §2.5，TDD-下单按门店拆单与运费模板 AC6）。
 *
 * <p>与 {@code packages/shared/tests/freight.test.ts} 里「merge」那组<b>同一批用例、同一批数</b> ——
 * 端上预估与后端实收按同一套规则，改一边另一边必红。
 */
class FreightMergeTest {

    /** A：首重 1kg 8 元，续 0.5kg 2 元，满 99 包邮 */
    private static final Template A = new Template("A", "A", 1000, 800, 500, 200, 9900, List.of());
    /** B：首重 1kg 10 元，续 1kg 3 元，不包邮 */
    private static final Template B = new Template("B", "B", 1000, 1000, 1000, 300, 0, List.of());

    @Test
    @DisplayName("★ 只有一个模板：与单模板公式逐字相同（改造前行为不变）")
    void singleTemplateEqualsFee() {
        Quote q = FreightPort.merge(List.of(new Part(A, 2300, 1000, null)));
        assertThat(q.feeMinor()).isEqualTo(FreightPort.fee(2300, 1000, 800, 500, 200)).isEqualTo(1400);
        assertThat(q.rejected()).isFalse();
    }

    @Test
    @DisplayName("★★ 两个模板：首费高的计首重，另一个的全部重量只按续重")
    void primaryByHighestFirstFee() {
        // B 首费 10 元 > A 的 8 元 → B 计首重：500g ≤ 1kg = 1000；A 的 1200g 全按续重 ⌈1200/500⌉×200 = 600
        Quote q = FreightPort.merge(List.of(new Part(A, 1200, 1000, null), new Part(B, 500, 1000, null)));
        assertThat(q.feeMinor()).isEqualTo(1600);
        assertThat(q.templateNo()).isEqualTo("B");
    }

    @Test
    @DisplayName("★ 满额包邮按各自模板判：包邮那份 0 元，也不参与首费比较")
    void freeShareExcluded() {
        // A 这份 99 元达标 → 只剩 B：1500g → 1000 + ⌈500/1000⌉×300 = 1300
        Quote q = FreightPort.merge(List.of(new Part(A, 3000, 9900, null), new Part(B, 1500, 500, null)));
        assertThat(q.feeMinor()).isEqualTo(1300);
        assertThat(q.free()).isFalse();
    }

    @Test
    @DisplayName("全部包邮：0 元且标包邮")
    void allFree() {
        Quote q = FreightPort.merge(List.of(new Part(A, 3000, 9900, null)));
        assertThat(q.feeMinor()).isZero();
        assertThat(q.free()).isTrue();
    }

    @Test
    @DisplayName("★ 任一模板对这个地区不配送：整店拒")
    void anyRejectRejects() {
        Rule reject = new Rule("西藏", FreightPort.ACTION_REJECT, 0);
        Quote q = FreightPort.merge(List.of(new Part(A, 500, 100, null), new Part(B, 500, 100, reject)));
        assertThat(q.rejected()).isTrue();
    }

    @Test
    @DisplayName("地区加收取参与计费那几份里最高的一笔，一个包裹只加一次")
    void surchargeOnceMax() {
        Rule s5 = new Rule("新疆", "SURCHARGE", 500);
        Rule s20 = new Rule("新疆", "SURCHARGE", 2000);
        // B 计首重 1000 + A 续重 ⌈500/500⌉×200 = 200 → 1200，再加最高加收 2000（不是 2500）
        Quote q = FreightPort.merge(List.of(new Part(A, 500, 100, s5), new Part(B, 500, 100, s20)));
        assertThat(q.feeMinor()).isEqualTo(3200);
    }
}
