package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.entity.OrdShipment;
import ai.neargo.shop.trade.order.mapper.StatusCasMapper;
import ai.neargo.shop.trade.order.service.ship.MybatisShipmentReadStore;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;

/** Production locks/transactions run concurrently; spies only synchronize real DB reads at the race window. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.test.url:jdbc:h2:mem:shipping-concurrency;MODE=MySQL;"
                + "DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1}",
        "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ"
})
@ActiveProfiles("test")
class OrderShippingConcurrencyFlowTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MerchantOrderService shipping;
    @Autowired private SqlSessionTemplate sessions;
    @MockitoSpyBean private MybatisShipmentReadStore read;
    @MockitoSpyBean private StatusCasMapper locks;
    private static final String MERCHANT = "SHIPPING-RACE-MERCHANT";
    private static final String STORE = "SHIPPING-RACE-STORE";

    @Test
    void differentOrdersPastLookupCannotClaimSameNormalizedWaybill() throws Exception {
        String first = seed();
        String second = seed();
        String waybill = "SF" + System.nanoTime();
        CyclicBarrier lookups = new CyclicBarrier(2);
        doAnswer(invocation -> {
            OrdShipment result = (OrdShipment) invocation.callRealMethod();
            assertThat(result).as("both actual lookups must see an unclaimed waybill").isNull();
            lookups.await(10, TimeUnit.SECONDS);
            return result;
        }).when(read).findActiveWaybill("SF", waybill);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> ship(first, waybill));
            var two = pool.submit(() -> ship(second, "  " + waybill.toLowerCase() + "  "));
            ErrorCode[] results = {one.get(20, TimeUnit.SECONDS), two.get(20, TimeUnit.SECONDS)};
            assertThat(Arrays.stream(results).filter(r -> r == null).count()).isEqualTo(1);
            assertThat(Arrays.stream(results).filter(r -> r != null).toList())
                    .containsExactly(ErrorCode.WAYBILL_DUPLICATED);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE carrier='SF' AND waybill_no=? "
                + "AND status <> 'VOIDED'", Long.class, waybill)).isEqualTo(1);
    }

    @Test
    void sameChildConcurrentRetryReadsCommittedPackageAfterLock() throws Exception {
        String sub = seed();
        String waybill = "SF" + System.nanoTime();
        CyclicBarrier entry = new CyclicBarrier(2);
        Set<Long> arrived = ConcurrentHashMap.newKeySet();
        StatusCasMapper realLocks = sessions.getMapper(StatusCasMapper.class);
        doAnswer(invocation -> {
            if (arrived.add(Thread.currentThread().threadId())) {
                entry.await(10, TimeUnit.SECONDS);
            }
            return realLocks.lockOrder(sub);
        }).when(locks).lockOrder(sub);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> ship(sub, waybill));
            var two = pool.submit(() -> ship(sub, waybill));
            assertThat(one.get(20, TimeUnit.SECONDS)).isNull();
            assertThat(two.get(20, TimeUnit.SECONDS)).isNull();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_shipment WHERE sub_order_no=?", Long.class, sub))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=? "
                + "AND event_type='SUB_ORDER_SHIPPED'", Long.class, sub)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_status_log WHERE sub_order_no=?", Long.class, sub))
                .isEqualTo(1);
    }

    private ErrorCode ship(String sub, String waybill) {
        try {
            shipping.ship(MERCHANT, STORE, sub, waybill, "SF");
            return null;
        } catch (BizException e) {
            return e.errorCode();
        }
    }

    private String seed() {
        String sub = "SRC" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO ord_order (order_no,user_no,status,created_at,updated_at) "
                + "VALUES (?,'SHIP-RACE-BUYER','PAID',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", "O-" + sub);
        jdbc.update("INSERT INTO ord_sub_order (sub_order_no,order_no,user_no,entity_no,store_no,fulfillment,"
                + "status,created_at,updated_at) VALUES (?,?,'SHIP-RACE-BUYER',?,?,'EXPRESS','WAIT_FULFILL',"
                + "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub, MERCHANT, STORE);
        jdbc.update("INSERT INTO ord_item (sub_order_no,order_no,goods_no,sku_no,qty,created_at,updated_at) "
                + "VALUES (?,?,'SHIP-GOODS','SHIP-SKU',1,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", sub, "O-" + sub);
        return sub;
    }
}
