package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import ai.neargo.shop.support.BasicRefundFixture;
import ai.neargo.shop.trade.order.command.OrderActor;
import ai.neargo.shop.trade.order.refund.service.RefundPreparationService;
import ai.neargo.shop.trade.service.AfterSaleService;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

/** A committed source decision survives the before-preparation gap and is found by the actual recovery scan. */
@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:source-missing-refund;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
@Import(StrictRefundCallbackFlowTest.ExternalChannel.class)
class SourceRefundMissingPreparationRecoveryFlowTest {
    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AfterSaleService afterSales;
    @MockitoBean private RefundPreparationService preparation;

    @Test void committedDecisionSurvivesFirstPreparationFailureAndRealScanRetriesOutsideTransaction() throws Exception {
        try(var fixture=new BasicRefundFixture(context)) {
            var purchase=fixture.purchase("13207071811"); fixture.prepare(purchase);
            String no=fixture.apply(purchase,"REFUND_ONLY");
            jdbc.update("update ord_after_sale set source_system='FOOD',source_request_key=?,source_request_hash=? where after_sale_no=?",
                    "a".repeat(64),"b".repeat(64),no);
            String merchantNo = fixture.merchant(purchase);
            var attempts = new AtomicInteger();
            when(preparation.prepare(eq(no), any(OrderActor.class))).thenAnswer(call -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                assertThat(call.<String>getArgument(0)).isEqualTo(no);
                if (attempts.incrementAndGet() == 1) {
                    throw new IllegalStateException("INJECTED_PREPARATION_TIMEOUT_AFTER_COMMIT");
                }
                return "RF-" + no;
            });
            fixture.approve(purchase,no);
            assertThat(attempts.get()).isEqualTo(1);
            verify(preparation).prepare(eq(no), argThat(actor -> merchantNo.equals(actor.operatorNo())));
            assertThat(jdbc.queryForObject("select status from ord_after_sale where after_sale_no=?",String.class,no)).isEqualTo("REFUNDING");
            assertThat(jdbc.queryForObject("select count(*) from ord_refund_execution where after_sale_no=?",Long.class,no)).isZero();
            jdbc.update("update ord_after_sale set updated_at=? where after_sale_no=?",LocalDateTime.now().minusHours(1),no);
            assertThat(afterSales.stuckRefundNos(System.currentTimeMillis()-30*60_000L,200)).contains(no);
            afterSales.resumeRefund(no,"SYSTEM");
            assertThat(attempts.get()).isEqualTo(2);
            var ordered = inOrder(preparation);
            ordered.verify(preparation).prepare(eq(no), argThat(actor -> merchantNo.equals(actor.operatorNo())));
            ordered.verify(preparation).prepare(eq(no), argThat(actor -> "SYSTEM".equals(actor.operatorNo())));
            ordered.verifyNoMoreInteractions();
        }
    }
}
