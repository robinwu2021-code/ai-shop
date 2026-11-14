package ai.neargo.shop.pay.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.commerce.api.common.AsyncStatus;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.shop.commercebridge.port.ShopMallPaymentRecovery;
import ai.neargo.shop.pay.channel.PayGateway;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PlatformMoneyHandoffMysqlTest {
    @Test
    void failureAfterCashCommitBeforeCoreDispatchKeepsDurableHandoffAndCallbackAcknowledgesIt()
            throws Exception {
        try (var f = new PlatformCollectionMysqlTest.Fixture()) {
            var pending = f.service.create(f.request("A"));
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
            var handoffs =
                    new PlatformMoneyHandoffServiceImpl(
                            f.ds, new DataSourceTransactionManager(f.ds));
            var core = mock(PaymentFacade.class);
            when(core.refresh(any()))
                    .thenAnswer(
                            call -> {
                                assertFalse(
                                        TransactionSynchronizationManager
                                                .isActualTransactionActive());
                                assertEquals(
                                        "SUCCESS",
                                        f.jdbc.queryForObject(
                                                "SELECT status FROM stl_payment", String.class));
                                assertEquals(1, handoffs.pending(20).size());
                                throw new IllegalStateException(
                                        "failure after cash commit before core dispatch");
                            });
            var recovery = new ShopMallPaymentRecovery(f.service, null, handoffs, core, null);
            assertTrue(recovery.recover(external));
            assertEquals(1, handoffs.pending(20).size());
            assertEquals(1, f.sends.get());
            assertTrue(handoffs.pending(pending.paymentNo(), 20).isEmpty());
            reset(core);
            when(core.refresh(any()))
                    .thenReturn(
                            new FulfillmentResult(
                                    "order", AsyncStatus.SUCCEEDED, AsyncStatus.SUCCEEDED, 1));
            assertTrue(recovery.recover(pending.paymentNo()));
            assertTrue(handoffs.pending(20).isEmpty());
            assertEquals(1, f.sends.get());
            assertEquals(
                    10000L,
                    f.jdbc.queryForObject("SELECT succeeded_at FROM stl_payment", Long.class));
        }
    }
}
