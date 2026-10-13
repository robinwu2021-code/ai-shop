package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.spi.product.StockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CreateOrderHandlerTest {

    static class FakeStore implements OrderWriteStore {
        final List<PaymentDraft> saved = new ArrayList<>();

        public String findPayNoByIdemKey(String userNo, String idemKey) {
            return null;
        }

        public void savePayment(PaymentDraft draft, String idemKey) {
            saved.add(draft);
        }
    }

    static class FakeStock implements StockPort {
        public List<String> lock(String lockNo, List<SkuQty> items) {
            return List.of();
        }

        public void release(String lockNo) {
        }

        public void confirm(String lockNo) {
        }

        public void restore(String restoreNo, List<SkuQty> items) {
        }

        public void setOnHand(String skuNo, String storeNo, int onHand, String reason) {
        }
    }

    static class FixedNoGen implements OrderNoGen {
        public String nextPayNo() {
            return "PAY1";
        }

        public String subOrderNo(String payNo, int index) {
            return payNo + "-" + index;
        }
    }

    private FakeStore store;
    private CreateOrderHandler handler;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        handler = new CreateOrderHandler(new OrderPersister(store, new FakeStock()), new FixedNoGen());
    }

    @Test
    @DisplayName("同商家两门店 → 1 支付单含 2 订单（按门店拆）")
    void sameMerchantTwoStores() {
        List<CartLine> lines = List.of(
                new CartLine("SK1", 2, 4980L, "M0001", "ST-A"),
                new CartLine("SK2", 1, 5800L, "M0001", "ST-B"));

        String payNo = handler.create("U1", "C1", lines, "idem-1");

        assertThat(payNo).isEqualTo("PAY1");
        assertThat(store.saved).hasSize(1);
        PaymentDraft draft = store.saved.get(0);
        assertThat(draft.payment().getPayAmount()).isEqualTo(4980L * 2 + 5800L);
        assertThat(draft.orders()).hasSize(2);
        assertThat(draft.orders().get(0).order().getStoreNo()).isEqualTo("ST-A");
        assertThat(draft.orders().get(1).order().getStoreNo()).isEqualTo("ST-B");
    }

    @Test
    @DisplayName("单门店：1 支付单 1 订单")
    void singleStore() {
        String payNo = handler.create("U1", "C1",
                List.of(new CartLine("SK1", 1, 1000L, "M0001", "ST-A")), "idem-2");
        assertThat(payNo).isEqualTo("PAY1");
        assertThat(store.saved.get(0).orders()).hasSize(1);
    }
}
