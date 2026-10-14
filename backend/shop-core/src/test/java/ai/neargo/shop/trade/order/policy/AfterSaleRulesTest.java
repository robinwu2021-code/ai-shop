package ai.neargo.shop.trade.order.policy;

import ai.neargo.shop.trade.entity.OrdAfterSale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("售后规则矩阵：五档 × 生鲜 × 质量/非质量")
class AfterSaleRulesTest {

    private static final String QUALITY = OrdAfterSale.REASON_DAMAGED;   // 质量
    private static final String NON_QUALITY = OrdAfterSale.REASON_NOT_WANTED; // 非质量

    // ── 生鲜已揽收/已签收：非质量硬拒，质量放行 ──
    @Test
    @DisplayName("生鲜·已揽收·非质量 → 拒")
    void fresh_shipped_non_quality_denied() {
        AfterSaleRules.Gate g = AfterSaleRules.applyGate(FulfillStage.SHIPPED, true, NON_QUALITY);
        assertThat(g.allow()).isFalse();
        assertThat(g.deny()).isEqualTo(AfterSaleRules.DENY_FRESH_QUALITY_ONLY);
    }

    @Test
    @DisplayName("生鲜·已签收·非质量 → 拒（已签收也只限质量）")
    void fresh_received_non_quality_denied() {
        assertThat(AfterSaleRules.applyGate(FulfillStage.RECEIVED, true, NON_QUALITY).allow()).isFalse();
    }

    @Test
    @DisplayName("生鲜·已揽收·质量 → 放行")
    void fresh_shipped_quality_allowed() {
        AfterSaleRules.Gate g = AfterSaleRules.applyGate(FulfillStage.SHIPPED, true, QUALITY);
        assertThat(g.allow()).isTrue();
        assertThat(g.needMerchant()).isFalse();
    }

    // ── 生鲜灰色地带：允许但需商家确认 ──
    @Test
    @DisplayName("生鲜·备货中·非质量 → 放行且需商家确认")
    void fresh_preparing_needs_merchant() {
        AfterSaleRules.Gate g = AfterSaleRules.applyGate(FulfillStage.PREPARING, true, NON_QUALITY);
        assertThat(g.allow()).isTrue();
        assertThat(g.needMerchant()).isTrue();
    }

    @Test
    @DisplayName("生鲜·已出单未揽收·非质量 → 放行且需商家确认（货还在仓里）")
    void fresh_labeled_needs_merchant() {
        AfterSaleRules.Gate g = AfterSaleRules.applyGate(FulfillStage.LABELED, true, NON_QUALITY);
        assertThat(g.allow()).isTrue();
        assertThat(g.needMerchant()).isTrue();
    }

    // ── 普通商品：任何档任何原因都放行、不灰色 ──
    @Test
    @DisplayName("普通商品·已揽收·非质量 → 放行、不灰色")
    void normal_shipped_any_reason_allowed() {
        AfterSaleRules.Gate g = AfterSaleRules.applyGate(FulfillStage.SHIPPED, false, NON_QUALITY);
        assertThat(g.allow()).isTrue();
        assertThat(g.needMerchant()).isFalse();
    }

    @Test
    @DisplayName("生鲜·未备货·非质量 → 放行、不灰色（货没动）")
    void fresh_not_prepared_allowed() {
        AfterSaleRules.Gate g = AfterSaleRules.applyGate(FulfillStage.NOT_PREPARED, true, NON_QUALITY);
        assertThat(g.allow()).isTrue();
        assertThat(g.needMerchant()).isFalse();
    }

    // ── 图片：只有已签收要 ──
    @Test
    @DisplayName("图片凭证只在已签收时要")
    void image_only_when_received() {
        assertThat(AfterSaleRules.needImage(FulfillStage.RECEIVED)).isTrue();
        assertThat(AfterSaleRules.needImage(FulfillStage.SHIPPED)).isFalse();
        assertThat(AfterSaleRules.needImage(FulfillStage.NOT_PREPARED)).isFalse();
    }

    // ── 原因下拉 ──
    @Test
    @DisplayName("生鲜已签收只给质量三项，其余给全部")
    void reasons_filtered_for_fresh_received() {
        assertThat(AfterSaleRules.reasonsFor(FulfillStage.RECEIVED, true))
                .isEqualTo(AfterSaleRules.QUALITY_REASONS);
        assertThat(AfterSaleRules.reasonsFor(FulfillStage.LABELED, true))
                .isEqualTo(OrdAfterSale.REASONS);
        assertThat(AfterSaleRules.reasonsFor(FulfillStage.RECEIVED, false))
                .isEqualTo(OrdAfterSale.REASONS);
    }
}
