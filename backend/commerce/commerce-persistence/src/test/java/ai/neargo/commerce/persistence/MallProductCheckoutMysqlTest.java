package ai.neargo.commerce.persistence;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.OfflineCollectionTerms;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.mall.DefaultMallFacade;
import ai.neargo.commerce.core.mall.DefaultStorefrontFacade;
import ai.neargo.commerce.core.spi.Period;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.util.*;

class MallProductCheckoutMysqlTest {
    @Test
    void publishedHardwareCanBeOrderedAndStoppedWithoutChangingTheOrder() {
        try (var mall = new MysqlDatabase();
                var foundation = new MysqlDatabase()) {
            CategoryMysqlTest.mallCatalogSchema(mall, foundation);
            for (var slice : List.of("order", "subscription", "fulfillment", "refund")) {
                new ResourceDatabasePopulator(
                                new ClassPathResource(
                                        "db/mall/" + slice + "/V1__mall_" + slice + ".sql"))
                        .execute(mall.ds);
            }
            var f = new JdbcCatalogFixture(mall, new JdbcCatalogRepository(mall.ds, foundation.ds));
            var store = MallOrderConcurrencyMysqlTest.STORE;
            var buyer = MallOrderConcurrencyMysqlTest.BUYER;
            var snapshot =
                    new StoreSnapshot(
                            store,
                            "merchant",
                            "RETAIL",
                            StoreStatus.ACTIVE,
                            CommerceModel.STORE_V1);
            StoreDirectoryPort stores =
                    ref -> Optional.of(snapshot).filter(s -> s.store().equals(ref));
            ActorAuthorizationPort auth =
                    (actor, ref, cap) ->
                            new AuthorizationDecision(ref.equals(store), "OWNER", Optional.empty());
            var subscriptions = new JdbcSubscriptionRepository(mall.ds, f.ids);
            f.uow.required(
                    () -> {
                        subscriptions.ensureAccount(snapshot, "owner");
                        subscriptions.insert(
                                subscriptions.lock(store),
                                new Period(
                                        "free",
                                        "initial",
                                        "FREE",
                                        "RETAIL:FREE",
                                        "free-1",
                                        "FREE",
                                        1000,
                                        1000,
                                        null,
                                        Map.of(),
                                        0,
                                        0,
                                        "CNY",
                                        "policy",
                                        false),
                                1,
                                "owner");
                        return null;
                    });
            var repo =
                    new JdbcMallRepository(
                            mall.ds, new JdbcCommerceEventPersistence(mall.ds), f.ids);
            var accounts = new JdbcSubscriptionAccountRepository(mall.ds);
            var catalog =
                    new DefaultStorefrontFacade(
                            f.repo,
                            repo,
                            accounts,
                            f.uow,
                            stores,
                            auth,
                            (s, m, skus) ->
                                    new CheckoutReadinessPort.Readiness(
                                            true, List.of(), List.of()));
            var orders =
                    new DefaultMallFacade(
                            repo, f.repo, accounts, f.uow, stores, auth, Clock.systemUTC(), f.ids);
            f.category("hardware");
            f.facade.saveProduct(
                    f.ops,
                    new ProductDraft(
                            "POS",
                            "hardware",
                            ProductKind.HARDWARE,
                            "POS terminal",
                            null,
                            List.of("RETAIL"),
                            0,
                            0,
                            "create"));
            f.requestedSkuVersion = "pos-v1";
            var price = new Money("CNY", 9000);
            var components =
                    List.of(
                            f.component(
                                    null,
                                    ComponentKind.HARDWARE,
                                    null,
                                    null,
                                    "DEVICE",
                                    null,
                                    1,
                                    1,
                                    false));
            f.facade.saveSku(
                    f.ops,
                    new SkuDraft(
                            "POS",
                            "POS-1",
                            null,
                            "Terminal",
                            price,
                            price,
                            List.of("RETAIL"),
                            "{}",
                            components,
                            4,
                            0,
                            "create"));
            var draft = f.facade.overview(f.ops).skuVersions().getFirst();
            f.facade.saveSku(
                    f.ops,
                    new SkuDraft(
                            "POS",
                            "POS-1",
                            "pos-v1",
                            "Terminal V1",
                            price,
                            price,
                            List.of("RETAIL"),
                            "{}",
                            draft.draft().components(),
                            4,
                            draft.version(),
                            "edit"));
            assertTrue(catalog.catalog(buyer, store).products().isEmpty());
            var overview = f.facade.overview(f.ops);
            f.facade.publishSku(
                    f.ops,
                    new SkuPublicationCommand(
                            "publish",
                            "pos-v1",
                            overview.skuVersions().getFirst().version(),
                            overview.skuDefinitions().getFirst().version(),
                            "sell"));
            var listed =
                    catalog.catalog(buyer, store).products().getFirst().skus().getFirst().sku();
            assertEquals("Terminal V1", listed.title());
            var terms =
                    new OfflineCollectionTerms(
                            "bank",
                            1,
                            "platform",
                            "CNY",
                            "account",
                            "window",
                            86400000,
                            Map.of("bank", "test"));
            var quote =
                    orders.quoteOffline(
                            buyer,
                            new QuoteCommand(
                                    store, List.of(new QuoteLine(listed.skuVersionNo(), 2)), "CNY"),
                            terms);
            var address =
                    new ShippingAddress(
                            "Buyer", "123456", "CN", null, "Shanghai", "Store address", null);
            var command =
                    new PlaceOrderCommand(quote.quoteNo(), "purchase", Optional.of(address), store);
            var order = orders.placeOrder(buyer, command);
            assertEquals(new Money("CNY", 18000), order.payable());
            assertEquals(order, orders.placeOrder(buyer, command));
            var sku = f.facade.overview(f.ops).skuDefinitions().getFirst();
            f.facade.stopSale(
                    f.ops,
                    new StopSaleCommand(
                            "stop", SaleTarget.SKU, sku.skuNo(), sku.version(), "stop"));
            assertTrue(catalog.catalog(buyer, store).products().isEmpty());
            var queries =
                    new ai.neargo.commerce.core.query.DefaultMallQueryFacade(
                            repo,
                            new JdbcFulfillmentRepository(mall.ds, true),
                            new JdbcRefundRepository(mall.ds, f.ids, subscriptions, true),
                            accounts,
                            f.uow,
                            stores,
                            auth);
            assertEquals(order, queries.orders(buyer, store, null, 10).getFirst().order());
            var detail = queries.order(buyer, new OrderQuery(store, order.orderNo()));
            assertEquals(address, detail.shippingAddress());
            assertEquals(listed.title(), detail.lines().getFirst().title());
            assertEquals(2, detail.lines().getFirst().quantity());
            assertEquals(quote.quoteNo(), detail.quote().quoteNo());
            assertEquals(order, detail.summary().order());
            assertEquals(
                    1,
                    mall.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mall_order WHERE store_no=? AND stage='ORDERED'",
                            Integer.class,
                            store.storeNo()));
        }
    }
}
