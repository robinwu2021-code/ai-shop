package ai.neargo.shop.scenario;

import ai.neargo.shop.job.JobSupport;
import ai.neargo.shop.trade.entity.OrdStatusLog;
import ai.neargo.shop.trade.job.OrderAutoReceiptJob;
import ai.neargo.shop.trade.mapper.TradeMappers.StatusLogMapper;
import ai.neargo.shop.trade.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

/** Job真实三参入口：一单流水失败应整单回滚，其他到期单仍独立完成。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:auto-receipt-tx;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class OrderAutoReceiptTransactionFlowTest {

    @Autowired
    private OrderService orders;
    @Autowired
    private JobSupport jobs;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private StatusLogMapper logs;
    @Autowired
    @Qualifier("transactionManager")
    private PlatformTransactionManager transactions;

    @Test
    void waitReceiptAfterSaleStillCompletesAndRemindsMerchant() {
        String no = insertDueOrder();
        String as = "AS-" + UUID.randomUUID();
        jdbc.update("insert into ord_after_sale (after_sale_no, sub_order_no, order_no, user_no, entity_no, "
                + "type, status, reason, refund_minor, tenant_no, created_at, updated_at) "
                + "values (?, ?, ?, 'U-AUTO', 'M0001', 'REFUND_ONLY', 'WAIT_RECEIPT', 'NOT_WANTED', 1000, 'MAIN', now(), now())",
                as, no, "P-" + no);
        jdbc.update("update ord_sub_order set open_after_sale_no = ? where sub_order_no = ?", as, no);
        new OrderAutoReceiptJob(orders, jobs, 15, 0).run(null);
        assertThat(status(no)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select count(*) from ord_status_log where aggregate_no = ? "
                + "and event = 'REMIND_SIGNED_CONFIRM_REFUND'", Integer.class, as)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no = ?",
                String.class, as)).isEqualTo("WAIT_RECEIPT");
    }

    @Test
    void unrecognizedOccupiedSlotDoesNotComplete() {
        String no = insertDueOrder();
        jdbc.update("update ord_sub_order set open_after_sale_no = 'AS-IN-FLIGHT' where sub_order_no = ?", no);
        new OrderAutoReceiptJob(orders, jobs, 15, 0).run(null);
        assertThat(status(no)).isEqualTo("FULFILLING");
    }

    @Test
    void outerTransactionCannotUndoIndividualReceipts() {
        String no = insertDueOrder();
        new TransactionTemplate(transactions).executeWithoutResult(outer -> {
            new OrderAutoReceiptJob(orders, jobs, 15, 0).run(null);
            outer.setRollbackOnly();
        });
        assertThat(status(no)).as("每单完成不能并入扫描调用者的事务").isEqualTo("COMPLETED");
    }

    @Test
    void logFailureRollsBackOneOrderAndContinuesOther() {
        String bad = insertDueOrder();
        String good = insertDueOrder();
        doThrow(new IllegalStateException("injected receipt log failure"))
                .when(logs).insert(argThat((OrdStatusLog row) -> bad.equals(row.getSubOrderNo())
                        && "RECEIPT_AUTO_CONFIRMED".equals(row.getEvent())));

        Throwable failure = catchThrowable(() -> new OrderAutoReceiptJob(orders, jobs, 15, 0).run(null));

        assertThat(status(bad)).as("状态与流水必须在同一单事务中回滚").isEqualTo("FULFILLING");
        assertThat(jdbc.queryForObject("select completed_at from ord_sub_order where sub_order_no = ?",
                Long.class, bad)).isNull();
        assertThat(jdbc.queryForObject("select count(*) from ord_status_log where sub_order_no = ? "
                + "and event = 'RECEIPT_AUTO_CONFIRMED'", Integer.class, bad)).isZero();
        assertThat(failure).as("单笔失败不得中断整轮扫描").isNull();
        assertThat(status(good)).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("select completed_at from ord_sub_order where sub_order_no = ?",
                Long.class, good)).isPositive();
    }

    private String insertDueOrder() {
        String no = "AR-" + UUID.randomUUID();
        jdbc.update("insert into ord_sub_order (sub_order_no, order_no, user_no, entity_no, store_no, "
                        + "status, fulfillment, pay_amount, goods_amount, tenant_no, version, deleted, "
                        + "created_at, updated_at) values (?, ?, 'U-AUTO', 'M0001', 'ST0001', "
                        + "'FULFILLING', 'EXPRESS', 1000, 1000, 'MAIN', 0, 0, now(), now())", no, "P-" + no);
        jdbc.update("insert into ord_status_log (sub_order_no, status, label, operator_type, at, tenant_no, "
                        + "created_at) values (?, 'FULFILLING', '商家发货', 'MERCHANT', ?, 'MAIN', now())",
                no, System.currentTimeMillis() - 20L * 86_400_000L);
        return no;
    }

    private String status(String no) {
        return jdbc.queryForObject("select status from ord_sub_order where sub_order_no = ?", String.class, no);
    }
}
