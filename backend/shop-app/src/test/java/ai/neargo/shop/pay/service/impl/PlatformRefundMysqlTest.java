package ai.neargo.shop.pay.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.pay.channel.PayGateway;
import ai.neargo.shop.pay.service.*;
import ai.neargo.shop.spi.pay.RefundQueryResult;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;
import java.util.concurrent.atomic.*;

class PlatformRefundMysqlTest {
    @Test
    void fullAcceptedReviewEvidenceIsFrozenAndDispatchableAtEveryBoundary() throws Exception {
        for (int size : List.of(1000, 1001, 1024)) {
            try (var f = new PlatformCollectionMysqlTest.Fixture()) {
                f.service.create(f.request("A"));
                String external =
                        f.jdbc.queryForObject("SELECT out_trade_no FROM stl_payment", String.class);
                f.answer.set(
                        new PayGateway.QueryResult(
                                true,
                                true,
                                true,
                                100,
                                "WX1",
                                "CNY",
                                10000L,
                                "platform-mch",
                                external,
                                "SUCCESS"));
                var paid = f.service.query(f.request("A").key());
                String evidence = "核".repeat(size);
                var request =
                        new PlatformRefundService.Request(
                                new PlatformRefundService.Key("MAIN", "order", "R1"),
                                "store",
                                "A",
                                paid.paymentNo(),
                                "platform-mch",
                                100,
                                60,
                                "CNY",
                                evidence);
                when(f.gateway.supportsRefundCurrency("CNY")).thenReturn(true);
                when(f.gateway.refund(any(), anyLong(), any(), eq(evidence)))
                        .thenReturn(PayGateway.Result.ok("accepted"));
                when(f.gateway.queryRefundOutcome(any()))
                        .thenReturn(RefundQueryResult.unknown("unknown"));
                var refunds =
                        new PlatformRefundServiceImpl(
                                f.ds, new DataSourceTransactionManager(f.ds), f.service, f.router);
                var result = assertDoesNotThrow(() -> refunds.refund(request));
                assertEquals(evidence, result.request().reason());
                assertEquals(evidence, refunds.refund(request).request().reason());
                verify(f.gateway, times(1)).refund(any(), eq(60L), any(), eq(evidence));
                assertThrows(
                        RuntimeException.class,
                        () ->
                                refunds.refund(
                                        new PlatformRefundService.Request(
                                                request.key(),
                                                "store",
                                                "A",
                                                paid.paymentNo(),
                                                "platform-mch",
                                                100,
                                                60,
                                                "CNY",
                                                "changed")));
            }
        }
    }

    @Test
    void refundUsesOriginalCashCapacityAndRequiresExactFinalOriginalPaymentProof()
            throws Exception {
        try (var f = new PlatformCollectionMysqlTest.Fixture()) {
            f.service.create(f.request("A"));
            String external =
                    f.jdbc.queryForObject("SELECT out_trade_no FROM stl_payment", String.class);
            f.answer.set(
                    new PayGateway.QueryResult(
                            true,
                            true,
                            true,
                            100,
                            "WX1",
                            "CNY",
                            10000L,
                            "platform-mch",
                            external,
                            "SUCCESS"));
            var paid = f.service.query(f.request("A").key());
            var request =
                    new PlatformRefundService.Request(
                            new PlatformRefundService.Key("MAIN", "order", "R1"),
                            "store",
                            "A",
                            paid.paymentNo(),
                            "platform-mch",
                            100,
                            60,
                            "CNY",
                            "approved evidence");
            when(f.gateway.supportsRefundCurrency("CNY")).thenReturn(true);
            var answer = new AtomicReference<>(RefundQueryResult.unknown("unknown"));
            var sends = new AtomicInteger();
            when(f.gateway.refund(any(), anyLong(), any(), any()))
                    .thenAnswer(
                            call -> {
                                assertFalse(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                sends.incrementAndGet();
                                assertEquals(
                                        1,
                                        f.jdbc.queryForObject(
                                                "SELECT count(*) FROM stl_payment WHERE"
                                                    + " direction='REFUND' AND refund_dispatch_at"
                                                    + " IS NOT NULL",
                                                Integer.class));
                                return PayGateway.Result.ok("accepted-only");
                            });
            when(f.gateway.queryRefundOutcome(any()))
                    .thenAnswer(
                            call -> {
                                assertFalse(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                return answer.get();
                            });
            var refunds =
                    new PlatformRefundServiceImpl(
                            f.ds, new DataSourceTransactionManager(f.ds), f.service, f.router);
            var pending = assertDoesNotThrow(() -> refunds.refund(request));
            assertNotEquals("SUCCESS", pending.state());
            refunds.refund(request);
            assertEquals(1, sends.get());
            assertThrows(
                    RuntimeException.class,
                    () ->
                            refunds.refund(
                                    new PlatformRefundService.Request(
                                            new PlatformRefundService.Key("MAIN", "order", "R2"),
                                            "store",
                                            "A",
                                            paid.paymentNo(),
                                            "platform-mch",
                                            100,
                                            50,
                                            "CNY",
                                            "over capacity")));
            String refundExternal =
                    f.jdbc.queryForObject(
                            "SELECT out_trade_no FROM stl_payment WHERE direction='REFUND'",
                            String.class);
            answer.set(
                    new RefundQueryResult(
                            RefundQueryResult.Status.TERMINAL_FAILED,
                            "CLOSED",
                            refundExternal,
                            60,
                            "WR1",
                            "CNY",
                            20000));
            assertEquals("UNKNOWN", refunds.query(request.key()).state());
            assertEquals(
                    "HELD",
                    f.jdbc.queryForObject(
                            "SELECT refund_capacity_state FROM stl_payment WHERE"
                                + " direction='REFUND'",
                            String.class));
            answer.set(
                    new RefundQueryResult(
                            RefundQueryResult.Status.SUCCEEDED,
                            "SUCCESS",
                            refundExternal,
                            60,
                            "WR1",
                            "CNY",
                            21000,
                            external,
                            "WRONG",
                            100L,
                            20000L,
                            "platform-mch"));
            assertEquals("UNKNOWN", refunds.query(request.key()).state());
            answer.set(
                    new RefundQueryResult(
                            RefundQueryResult.Status.SUCCEEDED,
                            "SUCCESS",
                            refundExternal,
                            60,
                            "WR1",
                            "CNY",
                            22000,
                            external,
                            "WX1",
                            100L,
                            20000L,
                            "platform-mch"));
            var completed = refunds.query(request.key());
            assertEquals("SUCCESS", completed.state());
            assertEquals("WR1", completed.providerNo());
            assertEquals(22000L, completed.verifiedAt());
            assertEquals(completed, refunds.query(request.key()));
            assertEquals(
                    "CONSUMED",
                    f.jdbc.queryForObject(
                            "SELECT refund_capacity_state FROM stl_payment WHERE"
                                + " direction='REFUND'",
                            String.class));
            assertEquals(
                    2, f.jdbc.queryForObject("SELECT count(*) FROM stl_payment", Integer.class));
        }
    }
}
