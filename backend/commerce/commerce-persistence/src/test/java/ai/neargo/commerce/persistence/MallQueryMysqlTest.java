package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository.Operation;
import ai.neargo.commerce.core.mall.*;
import ai.neargo.commerce.core.query.DefaultMallQueryFacade;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

class MallQueryMysqlTest {
    @Test
    void rawTrustedBuyerIdentitySurvivesFrozenQuoteRoundTripWithoutLeakingIntoPublicView() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db);
            var raw = new RawPrincipalIdentity("MERCHANT", "USR", "u".repeat(64));
            var actor = new ActorContext("tenant-a", SubjectKind.ACCOUNT, "buyer", "trusted", raw);
            var view =
                    f.api.quote(
                            actor,
                            new QuoteCommand(
                                    MallOrderConcurrencyMysqlTest.STORE,
                                    List.of(new QuoteLine("sku-1", 1)),
                                    "CNY"));
            var repo = new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds));
            var json = tools.jackson.databind.json.JsonMapper.builder().build();
            var frozen =
                    f.c.uow.required(() -> repo.quote("tenant-a", view.quoteNo()).orElseThrow());
            var snapshot = json.valueToTree(frozen);
            assertEquals(raw.rawNo(), snapshot.at("/buyerIdentity/rawNo").asText());
            assertEquals("USR", snapshot.at("/buyerIdentity/kind").asText());
            assertFalse(json.writeValueAsString(view).contains(raw.rawNo()));
            assertFalse(json.writeValueAsString(view).contains("trusted"));
        }
    }

    @Test
    void authorizedDetailsReadFrozenAmountsAndIndependentAxesWithoutPaymentConfiguration() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db);
            var quote = f.quote();
            assertThrows(
                    MallException.class,
                    () ->
                            f.api.placeOrder(
                                    MallOrderConcurrencyMysqlTest.BUYER,
                                    new PlaceOrderCommand(
                                            quote.quoteNo(),
                                            "wrong-path",
                                            Optional.empty(),
                                            new StoreRef("tenant-a", "other-store"))));
            var placed = f.place(quote, "query-order");
            var repo = new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds));
            var operations = new JdbcFulfillmentRepository(db.ds);
            var api =
                    new DefaultMallQueryFacade(
                            repo,
                            operations,
                            new JdbcRefundRepository(db.ds, f.c.ids),
                            new JdbcSubscriptionAccountRepository(db.ds),
                            f.c.uow,
                            s ->
                                    Optional.of(
                                            new StoreSnapshot(
                                                    s,
                                                    "merchant",
                                                    "RETAIL",
                                                    StoreStatus.ACTIVE,
                                                    CommerceModel.STORE_V1)),
                            (a, s, c) ->
                                    new AuthorizationDecision(
                                            "trusted".equals(a.accountSessionFingerprint()),
                                            "OWNER",
                                            Optional.empty()));
            var query = new OrderQuery(MallOrderConcurrencyMysqlTest.STORE, placed.orderNo());
            var detail = api.order(MallOrderConcurrencyMysqlTest.BUYER, query);
            assertNotNull(detail);
            assertEquals(quote, detail.quote());
            assertEquals("UNPAID", detail.summary().paymentStatus());
            assertEquals("PENDING", detail.summary().fulfillmentStatus());
            assertEquals("NONE", detail.summary().refundStatus());
            assertTrue(detail.paymentAttempts().isEmpty());
            assertEquals(
                    List.of(detail.summary()),
                    api.orders(MallOrderConcurrencyMysqlTest.BUYER, query.store(), null, 20));
            assertTrue(
                    api.orders(
                                    MallOrderConcurrencyMysqlTest.BUYER,
                                    query.store(),
                                    placed.orderNo(),
                                    20)
                            .isEmpty());
            assertThrows(
                    MallException.class,
                    () ->
                            api.order(
                                    new ActorContext(
                                            "tenant-a", SubjectKind.ACCOUNT, "buyer", "revoked"),
                                    query));
            assertThrows(
                    MallException.class,
                    () ->
                            api.order(
                                    MallOrderConcurrencyMysqlTest.BUYER,
                                    new OrderQuery(
                                            new StoreRef("other", "store"), placed.orderNo())));
            assertThrows(
                    MallException.class,
                    () ->
                            api.orders(
                                    MallOrderConcurrencyMysqlTest.BUYER,
                                    query.store(),
                                    null,
                                    1000));
            f.c.uow.required(
                    () -> {
                        new JdbcSubscriptionAccountRepository(db.ds)
                                .lockSubscriptionAccount(query.store());
                        var order = repo.order("tenant-a", placed.orderNo()).orElseThrow();
                        repo.transition(
                                order,
                                new OrderState.Axes(
                                        OrderState.Payment.PAYMENT_UNKNOWN,
                                        OrderState.Fulfillment.PENDING,
                                        OrderState.Refund.NONE),
                                "test");
                        operations.save(
                                "tenant-a",
                                new Operation(
                                        "attempt",
                                        placed.orderNo(),
                                        null,
                                        "PAYMENT",
                                        new PaymentIntentCommand(
                                                "tenant-a",
                                                placed.orderNo(),
                                                "platform",
                                                placed.payable(),
                                                Instant.ofEpochMilli(900000),
                                                "attempt",
                                                "buyer",
                                                "WECHAT",
                                                "JSAPI",
                                                "private-payer",
                                                Instant.ofEpochMilli(2000)),
                                        null,
                                        null,
                                        new PaymentOutcome(
                                                placed.orderNo(),
                                                Optional.of("pay"),
                                                "platform",
                                                placed.payable(),
                                                AsyncStatus.UNKNOWN,
                                                Optional.empty(),
                                                Optional.of("TIMEOUT")),
                                        null));
                        return null;
                    });
            var unknown = api.order(MallOrderConcurrencyMysqlTest.BUYER, query);
            assertEquals("PAYMENT_UNKNOWN", unknown.summary().paymentStatus());
            assertEquals(1, unknown.paymentAttempts().size());
            assertEquals(AsyncStatus.UNKNOWN, unknown.paymentAttempts().getFirst().status());
            assertEquals("TIMEOUT", unknown.paymentAttempts().getFirst().errorCode());
            assertNull(unknown.paymentAttempts().getFirst().paidAt());
            assertEquals(quote, unknown.quote());
        }
    }
}
