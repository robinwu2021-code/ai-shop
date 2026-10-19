package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.pay.service.RefundExecutionService;
import ai.neargo.shop.pay.SettleService;
import ai.neargo.shop.pay.StubSplitGateway;
import ai.neargo.shop.spi.settle.SettlePort.SplitReversal;
import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.support.DatabaseThreadBarrier;
import java.time.Duration;
import java.sql.SQLException;
import javax.sql.DataSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;

/** Real competing database transactions must serialize the split claim against a local refund adjustment. */
@SpringBootTest(properties = {
        "spring.datasource.url=${order.phase1.refund.split.test.url:jdbc:h2:mem:phase1-refund-split;"
                + "MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=15000}"
})
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class RefundSplitClaimFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SettleService settle;
    @Autowired private RefundExecutionService refunds;
    @Autowired @Qualifier("payTxManager") private PlatformTransactionManager transactions;
    @Autowired @Qualifier("payDataSource") private DataSource payDataSource;
    @MockitoSpyBean private StubSplitGateway gateway;

    @Test
    void staleSplitCannotSendAfterRefundWonTheBillClaim() throws Exception {
        try (var fixture = new BasicRefundFixture(context)) {
            var p = fixture.purchase("13207071101");
            String bill = bill(p.subNo());
            AtomicReference<Thread> thread = new AtomicReference<>();
            AtomicReference<CompletableFuture<Void>> worker = new AtomicReference<>();
            JdbcTemplate financial = new JdbcTemplate(payDataSource);
            new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                financial.queryForObject("select id from stl_bill where settle_no=? for update", Long.class, bill);
                worker.set(CompletableFuture.runAsync(() -> {
                    thread.set(Thread.currentThread());
                    settle.executeSplit(bill);
                }));
                await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(10)).until(() ->
                        DatabaseThreadBarrier.executing(thread.get(), "SplitPreparationServiceImpl", "prepare"));
                financial.update("update stl_bill set status='REVERSED',version=version+1 where settle_no=?", bill);
            });
            awaitSplitLoser(worker.get());
            verify(gateway, never()).split(anyString(), any(), anyLong(), anyString());
            assertThat(jdbc.queryForObject("select count(*) from stl_split_log where settle_no=?"
                    + " and split_action='SPLIT'", Integer.class, bill)).isZero();
            assertThat(jdbc.queryForObject("select status from stl_bill where settle_no=?", String.class, bill))
                    .isEqualTo("REVERSED");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAID", "PAID_AT", "PAYOUT"})
    void settledOrAllocatedSupplierFundsCannotPretendToBeUnsplit(String fact) throws Exception {
        try (var fixture = new BasicRefundFixture(context)) {
            var p = fixture.purchase("13207071102");
            String bill = bill(p.subNo());
            if ("PAID".equals(fact)) {
                jdbc.update("update stl_bill set status='PAID' where settle_no=?", bill);
            } else if ("PAID_AT".equals(fact)) {
                jdbc.update("update stl_bill set paid_at=123 where settle_no=?", bill);
            } else {
                jdbc.update("update stl_bill set payout_no='PAY-ALLOCATED' where settle_no=?", bill);
            }
            long amount = jdbc.queryForObject("select pay_amount from ord_sub_order where sub_order_no=?",
                    Long.class, p.subNo());
            assertThat(refunds.reverseUnsplit(new SplitReversal(p.subNo(), "AS-" + bill, amount, 0, amount)))
                    .isFalse();
            assertThat(jdbc.queryForObject("select count(*) from stl_bill_refund where after_sale_no=?",
                    Integer.class, "AS-" + bill)).isZero();
        }
    }

    private String bill(String subNo) {
        return jdbc.queryForObject("select settle_no from stl_bill where sub_order_no=?", String.class, subNo);
    }

    private void awaitSplitLoser(CompletableFuture<Void> worker) throws Exception {
        try {
            worker.get(15, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            while (cause != null) {
                // Strict MariaDB snapshots reject the stale CAS; H2 instead reports zero affected rows.
                if (cause instanceof SQLException sql && sql.getErrorCode() == 1020
                        && "HY000".equals(sql.getSQLState())) {
                    return;
                }
                cause = cause.getCause();
            }
            throw failure;
        }
    }
}
