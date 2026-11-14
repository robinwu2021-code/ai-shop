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
            assertEquals(
                    List.of("subscriptions"),
                    view.categories().stream().map(c -> c.code()).toList());
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

    /** P3-1: offline bank transfer purchasability is additive; online readiness stays untouched. */
    @Test
    void offlineReadinessIsAdditiveAndUsesPublishedTermsOutsideTheCommerceTransaction() {
        try (var db = new MysqlDatabase()) {
            db.migrate();
            var f = new MallOrderConcurrencyMysqlTest.Fixture(db);
            var procure = new java.util.concurrent.atomic.AtomicBoolean(true);
            var terms =
                    new ai.neargo.commerce.api.offline.OfflineCollectionTerms(
                            "p", 1, "platform", "CNY", "acct", "w1", 86400000, Map.of("bank", "t"));
            var requested = new ArrayList<String>();
            OfflineCollectionPort collection =
                    new OfflineCollectionPort() {
                        public Optional<ai.neargo.commerce.api.offline.OfflineCollectionTerms> terms(
                                String tenant, String profile, String currency) {
                            f.c.uow.requireOutsideTransaction();
                            requested.add(tenant + "/" + profile + "/" + currency);
                            return Optional.of(terms);
                        }
                    };
            CheckoutReadinessPort readiness =
                    new CheckoutReadinessPort() {
                        public Readiness evaluate(StoreSnapshot s, Money m, List<ai.neargo.commerce.api.catalog.SkuDraft> k) {
                            return new Readiness(false, List.of("PAYER_BINDING_UNPROVEN"), List.of());
                        }

                        public Readiness evaluateOffline(StoreSnapshot s, Money m,
                                List<ai.neargo.commerce.api.catalog.SkuDraft> k,
                                ai.neargo.commerce.api.offline.OfflineCollectionTerms t) {
                            return t == terms
                                    ? new Readiness(true, List.of(), List.of(new PaymentOption("OFFLINE", "BANK_TRANSFER", m.currency(), 9007199254740991L)))
                                    : new Readiness(false, List.of("OFFLINE_UNAVAILABLE"), List.of());
                        }
                    };
            StoreDirectoryPort stores =
                    st -> Optional.of(new StoreSnapshot(st, "merchant", "RETAIL", StoreStatus.ACTIVE, CommerceModel.STORE_V1));
            ActorAuthorizationPort auth =
                    (a, st, cap) -> new AuthorizationDecision(
                            cap.equals("commerce:mall:browse") || procure.get(), "MEMBER", Optional.empty());
            var mall = new JdbcMallRepository(db.ds, new JdbcCommerceEventPersistence(db.ds));
            var accounts = new JdbcSubscriptionAccountRepository(db.ds);
            var api = new DefaultStorefrontFacade(f.c.repo, mall, accounts, f.c.uow, stores, auth, readiness, collection, "p");
            var offlineView = api.catalog(MallOrderConcurrencyMysqlTest.BUYER, MallOrderConcurrencyMysqlTest.STORE);
            assertEquals(1, offlineView.categories().size());
            var sku = offlineView.products().getFirst().skus().getFirst();
            assertEquals(List.of("tenant-a/p/CNY", "tenant-a/p-sales/CNY"), requested);
            assertFalse(sku.readiness().available());
            assertEquals(List.of("PAYER_BINDING_UNPROVEN"), sku.readiness().reasons());
            assertTrue(sku.offlineReadiness().available());
            assertEquals("BANK_TRANSFER", sku.offlineReadiness().paymentOptions().getFirst().payMethod());

            procure.set(false);
            var browseOnly = api.catalog(MallOrderConcurrencyMysqlTest.BUYER, MallOrderConcurrencyMysqlTest.STORE)
                    .products().getFirst().skus().getFirst();
            assertFalse(browseOnly.offlineReadiness().available());
            assertEquals(List.of("PROCUREMENT_REQUIRED"), browseOnly.offlineReadiness().reasons());

            var disabled = new DefaultStorefrontFacade(f.c.repo, mall, accounts, f.c.uow, stores, auth, readiness);
            assertNull(disabled.catalog(MallOrderConcurrencyMysqlTest.BUYER, MallOrderConcurrencyMysqlTest.STORE)
                    .products().getFirst().skus().getFirst().offlineReadiness());
        }
    }
}
