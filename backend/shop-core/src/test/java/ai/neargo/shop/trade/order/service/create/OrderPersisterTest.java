package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.product.StockPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderPersisterTest {

    /** 记录锁了什么、落了什么；可预设幂等命中与缺货 */
    static class FakeStore implements OrderWriteStore {
        String existingPayNo;
        final List<PaymentDraft> saved = new ArrayList<>();

        public String findPayNoByIdemKey(String userNo, String idemKey) {
            return existingPayNo;
        }

        public void savePayment(PaymentDraft draft, String idemKey) {
            saved.add(draft);
        }
    }

    static class FakeStock implements StockPort {
        List<String> failOnLock = List.of();
        final List<String> lockedNos = new ArrayList<>();

        public List<String> lock(String lockNo, List<SkuQty> items) {
            lockedNos.add(lockNo);
            return failOnLock;
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

    private FakeStore store;
    private FakeStock stock;
    private OrderPersister persister;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        stock = new FakeStock();
        persister = new OrderPersister(store, stock);
    }

    private PaymentDraft draft() {
        return PaymentDraftFactory.build("PAY1", "U1", "C1",
                List.of(new OrderGroup("M0001", List.of(new CartLine("SK1", 2, 100L, "M0001", "ST-A")))),
                n -> "PAY1-" + n);
    }

    @Test
    @DisplayName("正常：锁库存 + 落库，返回支付单号")
    void happyPath() {
        String payNo = persister.persist(draft(), "U1", "idem-1");
        assertThat(payNo).isEqualTo("PAY1");
        assertThat(stock.lockedNos).containsExactly("PAY1");
        assertThat(store.saved).hasSize(1);
    }

    @Test
    @DisplayName("幂等命中：返回原单，不锁库存、不落库")
    void idempotentHit() {
        store.existingPayNo = "PAY0";
        String payNo = persister.persist(draft(), "U1", "idem-1");
        assertThat(payNo).isEqualTo("PAY0");
        assertThat(stock.lockedNos).isEmpty();
        assertThat(store.saved).isEmpty();
    }

    @Test
    @DisplayName("库存不足：抛 STOCK_SHORT_ITEMS，不落库")
    void stockShort() {
        stock.failOnLock = List.of("SK1");
        assertThatThrownBy(() -> persister.persist(draft(), "U1", "idem-1"))
                .isInstanceOf(BizException.class);
        assertThat(store.saved).isEmpty();
    }
}
