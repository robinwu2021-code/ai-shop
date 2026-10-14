package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.order.mapper.RefundLedgerMapper;
import ai.neargo.shop.trade.order.refund.port.impl.MybatisRefundLedgerStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

/** 退款账本的条件增量落到真库（集群规则 R2）：超上限写 0 行；两线程同时退最后一件只一笔成功。 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("退款账本 · 真库")
class RefundLedgerStoreTest {

    @Autowired
    private RefundLedgerMapper mapper;
    @Autowired
    private JdbcTemplate jdbc;

    private long newItem(String sub, int qty) {
        jdbc.update("insert into ord_sub_order (sub_order_no, order_no, user_no, entity_no, status, pay_amount, goods_amount, "
                + "version, deleted, created_at, updated_at) values (?, ?, 'U-LG', 'M0001', 'FULFILLING', 9000, 9000, 0, 0, now(), now())",
                sub, "P-" + sub);
        jdbc.update("insert into ord_item (sub_order_no, order_no, goods_no, sku_no, title, price, qty, amount, paid_share_minor, "                + "created_at, updated_at) values (?, ?, 'G', 'S', 'T', 3000, ?, 9000, 9000, now(), now())", sub, "P-" + sub, qty);
        return jdbc.queryForObject("select id from ord_item where sub_order_no = ?", Long.class, sub);
    }

    @Test
    @DisplayName("★★ 超件数 / 超金额 → false；未超 → true 且累加")
    void conditionalIncrement() {
        var store = new MybatisRefundLedgerStore(mapper);
        long id = newItem("LG-1", 3);
        assertThat(store.addItemRefund(id, 2, 6000)).isTrue();
        assertThat(store.addItemRefund(id, 2, 6000)).as("只剩 1 件").isFalse();
        assertThat(store.addSubRefund("LG-1", 9000)).isTrue();
        assertThat(store.addSubRefund("LG-1", 1)).as("已退满实付").isFalse();
        assertThat(store.subRefundedAndPay("LG-1")).containsExactly(9000L, 9000L);
    }

    @Test
    @DisplayName("★★★ 两线程同时退最后一件：恰一笔成功（并发不会超退）")
    void concurrentLastUnit() throws Exception {
        var store = new MybatisRefundLedgerStore(mapper);
        long id = newItem("LG-2", 1);
        var pool = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        Callable<Boolean> task = () -> {
            start.await();
            return store.addItemRefund(id, 1, 3000);
        };
        var a = pool.submit(task);
        var b = pool.submit(task);
        start.countDown();
        assertThat(a.get() ^ b.get()).as("必须恰一笔成功").isTrue();
        pool.shutdown();
        assertThat(jdbc.queryForObject("select refunded_qty from ord_item where id = ?", Integer.class, id)).isOne();
    }
}
