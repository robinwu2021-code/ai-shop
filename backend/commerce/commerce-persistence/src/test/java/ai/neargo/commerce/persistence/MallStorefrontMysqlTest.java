package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.mall.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class MallStorefrontMysqlTest {
    @Test
    void storefrontIsPublishedOnlyMembershipReadAndProcurementAreDistinct() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db);
            var procure = new java.util.concurrent.atomic.AtomicBoolean(false);
            var read = new java.util.concurrent.atomic.AtomicBoolean(true);
            var api =
                    new DefaultStorefrontFacade(
                            f.c.repo,
                            new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds)),
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
                                    new AuthorizationDecision(
                                            cap.equals("commerce:mall:browse")
                                                    ? read.get()
                                                    : procure.get(),
                                            "MEMBER",
                                            Optional.empty()),
                            (st, money, skus) ->
                                    new CheckoutReadinessPort.Readiness(
                                            false,
                                            List.of("PAYMENT_CURRENCY_UNSUPPORTED"),
                                            List.of()));
            var view =
                    assertDoesNotThrow(
                            () ->
                                    api.catalog(
                                            MallOrderConcurrencyMysqlTest.BUYER,
                                            MallOrderConcurrencyMysqlTest.STORE));
            assertEquals("CNY", view.currency());
            assertFalse(view.canProcure());
            assertEquals(1, view.products().size());
            assertFalse(view.products().getFirst().skus().getFirst().readiness().available());
            assertEquals(
                    "sku-1", view.products().getFirst().skus().getFirst().sku().skuVersionNo());
            db.jdbc.update("UPDATE mall_sku_version SET status='DRAFT'");
            assertTrue(
                    api.catalog(
                                    MallOrderConcurrencyMysqlTest.BUYER,
                                    MallOrderConcurrencyMysqlTest.STORE)
                            .products()
                            .isEmpty());
            read.set(false);
            assertThrows(
                    MallException.class,
                    () ->
                            api.catalog(
                                    MallOrderConcurrencyMysqlTest.BUYER,
                                    MallOrderConcurrencyMysqlTest.STORE));
        }
    }
}
