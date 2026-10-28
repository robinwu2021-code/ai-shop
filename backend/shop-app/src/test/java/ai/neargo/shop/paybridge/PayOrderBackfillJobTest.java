package ai.neargo.shop.paybridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.job.api.JobInvocation;
import ai.neargo.job.api.TriggerType;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill.Assessment;
import ai.neargo.shop.pay.acquiring.backfill.PayOrderBackfill.Status;
import ai.neargo.shop.spi.pay.PaymentPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

/** Real source tables plus the financial port establish what the manual job may associate. */
class PayOrderBackfillJobTest {
    private final PayOrderBackfill backfill = mock(PayOrderBackfill.class);
    private final MerchantQueryPort merchants = mock(MerchantQueryPort.class);
    private EmbeddedDatabase database;
    private JdbcTemplate jdbc;
    private PayOrderBackfillJob job;

    @BeforeEach void source() {
        database = new EmbeddedDatabaseBuilder().generateUniqueName(true).setType(EmbeddedDatabaseType.H2).build();
        jdbc = new JdbcTemplate(database);
        jdbc.execute("CREATE TABLE ord_sub_order(order_no VARCHAR(64),sub_order_no VARCHAR(64),store_no VARCHAR(64),"
                + "entity_no VARCHAR(64),user_no VARCHAR(64),tenant_no VARCHAR(32),deleted INT DEFAULT 0)");
        jdbc.execute("CREATE TABLE ord_industry_order_source(order_no VARCHAR(64),sub_order_no VARCHAR(64),"
                + "store_no VARCHAR(64),entity_no VARCHAR(64),buyer_no VARCHAR(64),source_system VARCHAR(32),"
                + "status VARCHAR(32),payment_out_trade_no VARCHAR(64),tenant_no VARCHAR(32),deleted INT DEFAULT 0)");
        jdbc.update("INSERT INTO ord_sub_order(order_no,sub_order_no,store_no,entity_no,user_no,tenant_no)"
                + " VALUES('O1','S1','STORE','MERCHANT','USER','MAIN')");
        when(backfill.pendingOrderNos(anyInt())).thenReturn(List.of("O1"));
        when(backfill.pendingOrderNos(anyString(), anyInt())).thenReturn(List.of("O1"));
        when(backfill.inspect(anyString(), anyString(), any(), any(), any(), anyString(), any()))
                .thenAnswer(call -> ready(call.getArgument(0), "USER"));
        when(backfill.backfill(anyString(), anyString(), any(), any(), any(), anyString(), any())).thenReturn(100L);
        // Current configuration deliberately differs from the immutable financial evidence.
        when(merchants.payStoreOf("STORE")).thenReturn(Optional.of(new MerchantQueryPort.PayStore(1L, "STORE",
                "门店", "MERCHANT", "主体", "CN", "DIRECT")));
        job = new PayOrderBackfillJob(backfill, merchants, jdbc);
    }

    @AfterEach void close() { database.shutdown(); }

    private Assessment ready(String order, String user) {
        return new Assessment(Status.READY, null, null, "AGGREGATED", "CNY", 100, 100, 0, "TEST",
                List.of("STORE"), List.of("MERCHANT"), user, order);
    }

    private JobInvocation invocation(Map<String, String> params) {
        return new JobInvocation("BACKFILL-TEST", TriggerType.MANUAL, LocalDate.of(2026, 10, 29), params);
    }

    @Test void defaultInvocationIsReadOnlyAndReturnsAContinuationCursor() {
        var result = job.run(invocation(Map.of()));
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertThat(result.detail()).contains("dry-run", "nextCursor=O1");
    }

    @Test void mixedStoreRootIsNotAssignedToItsFirstChild() {
        jdbc.update("INSERT INTO ord_sub_order(order_no,sub_order_no,store_no,entity_no,user_no,tenant_no)"
                + " VALUES('O1','S2','OTHER','MERCHANT','USER','MAIN')");
        var result = job.run(invocation(Map.of("apply", "true")));
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertThat(result.detail()).contains("SOURCE_STORE_AMBIGUOUS");
    }

    @Test void currentDisplayNamesAreNotClaimedAsHistoricalSnapshots() {
        job.run(invocation(Map.of("apply", "true")));
        verify(backfill).backfill("O1", PaymentPort.MALL_ORDER, 1L, null, null, "CN", "AGGREGATED");
    }

    @Test void explicitApplyUsesOriginalFundsPathRatherThanCurrentMerchantConfiguration() {
        job.run(invocation(Map.of("apply", "true")));
        verify(backfill).backfill("O1", PaymentPort.MALL_ORDER, 1L, null, null, "CN", "AGGREGATED");
    }

    @Test void unknownIndustryOwnerIsNotGuessedAsFood() {
        jdbc.update("INSERT INTO ord_industry_order_source(order_no,sub_order_no,store_no,entity_no,buyer_no,"
                + "source_system,status,payment_out_trade_no,tenant_no)"
                + " VALUES('O1','S1','STORE','MERCHANT','USER','OTHER',"
                + "'BOUND','O1','MAIN')");
        var result = job.run(invocation(Map.of("apply", "true")));
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertThat(result.detail()).contains("SOURCE_BUSINESS_UNKNOWN");
    }

    private void foodSource(String originalReference) {
        jdbc.update("INSERT INTO ord_industry_order_source(order_no,sub_order_no,store_no,entity_no,buyer_no,"
                + "source_system,status,payment_out_trade_no,tenant_no)"
                + " VALUES('O1','S1','STORE','MERCHANT','USER','FOOD','BOUND',?,'MAIN')", originalReference);
    }

    @Test void retiredIndustryBindingIsNotReclassifiedAsAnOrdinaryMallOrder() {
        foodSource("O1");
        jdbc.update("UPDATE ord_industry_order_source SET deleted=1 WHERE order_no='O1'");
        var result = job.run(invocation(Map.of("apply", "true")));
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertThat(result.detail()).contains("SOURCE_BINDING_DELETED");
    }

    @Test void retiredChildCannotHideAnAmbiguousHistoricalOwner() {
        jdbc.update("INSERT INTO ord_sub_order(order_no,sub_order_no,store_no,entity_no,user_no,tenant_no,deleted)"
                + " VALUES('O1','S2','OTHER','MERCHANT','USER','MAIN',1)");
        var result = job.run(invocation(Map.of("apply", "true")));
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertThat(result.detail()).contains("SOURCE_ORDER_DELETED");
    }

    @Test void knownFoodSourceKeepsItsOriginalPaymentOwner() {
        foodSource("O1");
        job.run(invocation(Map.of("apply", "true")));
        verify(backfill).backfill("O1", PaymentPort.FOOD_CHECK, 1L, null, null, "CN", "AGGREGATED");
    }

    @Test void foodBindingCannotBeLinkedToAnotherCashAttempt() {
        foodSource("ANOTHER-PAYMENT");
        var result = job.run(invocation(Map.of("apply", "true")));
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertThat(result.detail()).contains("SOURCE_PAYMENT_CONFLICT");
    }

    @Test void businessBuyerMustMatchOriginalFinancialPayer() {
        when(backfill.inspect(anyString(), anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(ready("O1", "ANOTHER-USER"));
        var result = job.run(invocation(Map.of("apply", "true")));
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertThat(result.detail()).contains("SOURCE_BUYER_CONFLICT");
    }

    @Test void scheduledInvocationCannotApplyEvenWithAnOldPersistedApplyParameter() {
        var scheduled = new JobInvocation("CRON-BACKFILL", TriggerType.CRON, LocalDate.of(2026, 10, 29),
                Map.of("apply", "true"));
        var result = job.run(scheduled);
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
        assertThat(result.error()).isEqualTo("PAY_BACKFILL_MANUAL_APPLY_REQUIRED");
    }

    @Test void blockedSourceDoesNotPreventTheNextOrderFromBeingApplied() {
        jdbc.update("INSERT INTO ord_sub_order(order_no,sub_order_no,store_no,entity_no,user_no,tenant_no)"
                + " VALUES('O1','S2','OTHER','MERCHANT','USER','MAIN')");
        jdbc.update("INSERT INTO ord_sub_order(order_no,sub_order_no,store_no,entity_no,user_no,tenant_no)"
                + " VALUES('O2','S3','STORE','MERCHANT','USER','MAIN')");
        when(backfill.pendingOrderNos(anyString(), anyInt())).thenReturn(List.of("O1", "O2"));
        var result = job.run(invocation(Map.of("apply", "true")));
        verify(backfill, never()).backfill(eq("O1"), anyString(), any(), any(), any(), anyString(), any());
        verify(backfill).backfill("O2", PaymentPort.MALL_ORDER, 1L, null, null, "CN", "AGGREGATED");
        assertThat(result.detail()).contains("nextCursor=O2", "阻断1", "已写入1");
    }

    @Test void largeBlockedBatchFitsThePlatformSummaryWithoutLosingItsCursor() {
        List<String> candidates = IntStream.range(0, 50).mapToObj(i -> "X".repeat(60) + "%04d".formatted(i)).toList();
        when(backfill.pendingOrderNos(anyString(), anyInt())).thenReturn(candidates);
        var result = job.run(invocation(Map.of("limit", "50")));
        assertThat(result.detail().length()).isLessThanOrEqualTo(500);
        assertThat(result.detail()).contains("nextCursor=" + candidates.get(49));
        verify(backfill, never()).backfill(anyString(), anyString(), any(), any(), any(), anyString(), any());
    }

    @Test void explicitCursorAndBoundedLimitReachTheFinancialCandidateScan() {
        job.run(invocation(Map.of("afterOrderNo", "A", "limit", "10")));
        verify(backfill).pendingOrderNos("A", 10);
    }
}
