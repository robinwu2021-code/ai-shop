package ai.neargo.shop.trade.order.service.create;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSplitterTest {

    private CartLine line(String sku, String merchant, String store) {
        return new CartLine(sku, 1, 100L, merchant, store);
    }

    @Test
    @DisplayName("同一商家不同门店 → 拆成多张订单（ADR-031：一店一单）")
    void sameMerchantDifferentStoresSplit() {
        List<OrderGroup> groups = OrderSplitter.splitByStore(List.of(
                line("SK1", "M0001", "ST-A"),
                line("SK2", "M0001", "ST-B")));
        assertThat(groups).hasSize(2);
        assertThat(groups).extracting(OrderGroup::storeNo).containsExactly("ST-A", "ST-B");
        assertThat(groups).allMatch(g -> "M0001".equals(g.merchantNo()));
    }

    @Test
    @DisplayName("同一门店 → 一张订单")
    void sameStoreOneOrder() {
        List<OrderGroup> groups = OrderSplitter.splitByStore(List.of(
                line("SK1", "M0001", "ST-A"),
                line("SK2", "M0001", "ST-A")));
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).storeNo()).isEqualTo("ST-A");
        assertThat(groups.get(0).lines()).hasSize(2);
    }

    @Test
    @DisplayName("跨商家不同门店 → 各自一张订单，顺序稳定")
    void acrossMerchants() {
        List<OrderGroup> groups = OrderSplitter.splitByStore(List.of(
                line("SK1", "M0001", "ST-A"),
                line("SK2", "M0002", "ST-B")));
        assertThat(groups).extracting(OrderGroup::storeNo).containsExactly("ST-A", "ST-B");
    }

    @Test
    @DisplayName("无归属门店 → 落到主体（按主体号成组）")
    void noStoreFallsToMerchant() {
        List<OrderGroup> groups = OrderSplitter.splitByStore(List.of(
                line("SK1", "M0001", null),
                line("SK2", "M0001", null)));
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).merchantNo()).isEqualTo("M0001");
    }

    @Test
    @DisplayName("空购物车 → 空结果")
    void empty() {
        assertThat(OrderSplitter.splitByStore(List.of())).isEmpty();
    }
}
