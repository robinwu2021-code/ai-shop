package ai.neargo.shop.trade.order.service.create;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OrderSplitterTest {

    private CartLine line(String sku, int qty, String merchant, String store) {
        return new CartLine(sku, qty, 100L, merchant, store);
    }

    @Test
    @DisplayName("跨商家 → 每个商家一组，顺序稳定")
    void splitsAcrossMerchants() {
        List<OrderGroup> groups = OrderSplitter.splitByMerchant(List.of(
                line("SK1", 2, "M0001", "ST-A"),
                line("SK2", 1, "M0002", "ST-B")));
        assertThat(groups).extracting(OrderGroup::merchantNo).containsExactly("M0001", "M0002");
        assertThat(groups.get(0).lines()).hasSize(1);
        assertThat(groups.get(1).lines()).hasSize(1);
    }

    @Test
    @DisplayName("同一商家多门店 → 仍是一张订单（门店下沉到行，新模型关键差异）")
    void sameMerchantMultiStoreIsOneOrder() {
        List<OrderGroup> groups = OrderSplitter.splitByMerchant(List.of(
                line("SK1", 1, "M0001", "ST-A"),
                line("SK2", 1, "M0001", "ST-B"),
                line("SK3", 1, "M0001", "ST-A")));
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).merchantNo()).isEqualTo("M0001");
        assertThat(groups.get(0).lines()).hasSize(3);
        // 门店信息保留在行上，不再成为拆单维度
        assertThat(groups.get(0).lines()).extracting(CartLine::supplyStoreNo)
                .containsExactly("ST-A", "ST-B", "ST-A");
    }

    @Test
    @DisplayName("单商家 → 一组")
    void singleMerchant() {
        List<OrderGroup> groups = OrderSplitter.splitByMerchant(List.of(
                line("SK1", 1, "M0001", "ST-A"),
                line("SK2", 2, "M0001", "ST-A")));
        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).lines()).hasSize(2);
    }

    @Test
    @DisplayName("空购物车 → 空结果")
    void empty() {
        assertThat(OrderSplitter.splitByMerchant(List.of())).isEmpty();
    }
}
