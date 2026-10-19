package ai.neargo.shop.scenario;

import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.trade.order.machine.order.OrderContext;
import ai.neargo.shop.trade.order.machine.order.OrderEvent;
import ai.neargo.shop.trade.order.statemachine.Actor;
import ai.neargo.shop.trade.order.transition.TradeStatusWriter;
import ai.neargo.shop.trade.service.MerchantOrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/** 两次备货都读到未备货时，真实写入和流水必须仍只有一次。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:prepare-fact;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class PrepareFactFlowTest {

    @Autowired
    private MerchantOrderService orders;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private TradeStatusWriter writer;

    @Test
    void concurrentPrepareWritesOneFactAndLog() throws Exception {
        String no = insertOrder();
        CyclicBarrier bothRead = new CyclicBarrier(2);
        doAnswer(call -> {
            OrdSubOrder sub = call.getArgument(0);
            if (no.equals(sub.getSubOrderNo())) {
                bothRead.await(10, TimeUnit.SECONDS);
            }
            return call.callRealMethod();
        }).when(writer).order(any(OrdSubOrder.class), eq(OrderEvent.PREPARE), any(Actor.class),
                anyString(), anyString(), any(OrderContext.class));

        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> orders.prepare("M0001", "ST0001", no));
            var second = pool.submit(() -> orders.prepare("M0001", "ST0001", no));
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        }

        assertThat(logCount(no)).isOne();
        assertThat(jdbc.queryForObject("select version from ord_sub_order where sub_order_no = ?",
                Long.class, no)).isEqualTo(1L);
        Long firstTime = jdbc.queryForObject("select prepared_at from ord_sub_order where sub_order_no = ?",
                Long.class, no);
        assertThat(firstTime).isPositive();
        orders.prepare("M0001", "ST0001", no);
        assertThat(logCount(no)).isOne();
        assertThat(jdbc.queryForObject("select prepared_at from ord_sub_order where sub_order_no = ?",
                Long.class, no)).isEqualTo(firstTime);
    }

    @Test
    void existingAfterSaleSlotBlocksFactEvenBeforeTicketIsVisible() {
        String no = insertOrder();
        jdbc.update("update ord_sub_order set open_after_sale_no = 'AS-IN-FLIGHT' where sub_order_no = ?", no);

        assertThatThrownBy(() -> orders.prepare("M0001", "ST0001", no))
                .isInstanceOf(BizException.class)
                .extracting(failure -> ((BizException) failure).errorCode()).isEqualTo(ErrorCode.CONFLICT);
        assertThat(logCount(no)).isZero();
        assertThat(jdbc.queryForObject("select prepared_at from ord_sub_order where sub_order_no = ?",
                Long.class, no)).isNull();
    }

    private String insertOrder() {
        String no = "PF-" + UUID.randomUUID();
        jdbc.update("insert into ord_sub_order (sub_order_no, order_no, user_no, entity_no, store_no, "
                        + "status, fulfillment, pay_amount, goods_amount, tenant_no, version, deleted, "
                        + "created_at, updated_at) values (?, ?, 'U-PREPARE', 'M0001', 'ST0001', "
                        + "'WAIT_FULFILL', 'EXPRESS', 1000, 1000, 'MAIN', 0, 0, now(), now())", no, "P-" + no);
        return no;
    }

    private int logCount(String no) {
        return jdbc.queryForObject("select count(*) from ord_status_log where sub_order_no = ? and event = 'PREPARE'",
                Integer.class, no);
    }
}
