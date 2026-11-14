package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.mall.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class MallCheckoutReadinessMysqlTest {
    @Test
    void exactFinalCashReadinessPreventsUnpayableHoldButPreservesCreatedOrderReplay() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db);
            var available = new java.util.concurrent.atomic.AtomicBoolean(false);
            CheckoutReadinessPort readiness =
                    (store, amount, skus) ->
                            new CheckoutReadinessPort.Readiness(
                                    available.get() || amount.minor() == 0,
                                    List.of("PAYMENT_CURRENCY_UNSUPPORTED"),
                                    List.of());
            var api =
                    new DefaultMallFacade(
                            new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds)),
                            f.c.repo,
                            new JdbcSubscriptionAccountRepository(db.ds),
                            f.c.uow,
                            st ->
                                    Optional.of(
                                            new StoreSnapshot(
                                                    st,
                                                    "merchant",
                                                    "RETAIL",
                                                    StoreStatus.ACTIVE,
                                                    CommerceModel.STORE_V1)),
                            (a, st, cap) ->
                                    new AuthorizationDecision(true, "OWNER", Optional.empty()),
                            Clock.fixed(Instant.ofEpochMilli(2000), ZoneOffset.UTC),
                            f.c.ids,
                            null,
                            readiness);
            var command =
                    new QuoteCommand(
                            MallOrderConcurrencyMysqlTest.STORE,
                            List.of(new QuoteLine("sku-1", 1)),
                            "CNY");
            assertEquals(
                    "CHECKOUT_UNAVAILABLE",
                    assertThrows(
                                    MallException.class,
                                    () -> api.quote(MallOrderConcurrencyMysqlTest.BUYER, command))
                            .code());
            available.set(true);
            var quote = api.quote(MallOrderConcurrencyMysqlTest.BUYER, command);
            var place = new PlaceOrderCommand(quote.quoteNo(), "ready-place", Optional.empty());
            available.set(false);
            assertEquals(
                    "CHECKOUT_UNAVAILABLE",
                    assertThrows(
                                    MallException.class,
                                    () ->
                                            api.placeOrder(
                                                    MallOrderConcurrencyMysqlTest.BUYER, place))
                            .code());
            assertNull(
                    db.jdbc.queryForObject(
                            "SELECT pending_change_order_no FROM mch_subscription_account",
                            String.class));
            available.set(true);
            var order = api.placeOrder(MallOrderConcurrencyMysqlTest.BUYER, place);
            available.set(false);
            assertEquals(order, api.placeOrder(MallOrderConcurrencyMysqlTest.BUYER, place));
        }
    }
}
