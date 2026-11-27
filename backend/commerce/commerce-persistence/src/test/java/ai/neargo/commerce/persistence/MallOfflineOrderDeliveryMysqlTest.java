package ai.neargo.commerce.persistence;

import static ai.neargo.commerce.persistence.MallOrderConcurrencyMysqlTest.*;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.fulfillment.CommerceFulfillmentService;
import ai.neargo.commerce.core.mall.DefaultMallFacade;
import ai.neargo.commerce.core.offline.DefaultOfflinePurchaseFacade;
import ai.neargo.commerce.core.spi.Period;
import ai.neargo.commerce.core.subscription.SubscriptionService;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.*;
import java.util.*;

class MallOfflineOrderDeliveryMysqlTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void confirmedTransferFulfillsOriginalOrder(boolean hardware) {
        try (var mall = new MysqlDatabase();
                var foundation = new MysqlDatabase();
                var platform = new MysqlDatabase()) {
            CategoryMysqlTest.mallCatalogSchema(mall, foundation);
            for (var file :
                    List.of(
                            "order/V1__mall_order",
                            "subscription/V1__mall_subscription",
                            "fulfillment/V1__mall_fulfillment",
                            "refund/V1__mall_refund",
                            "offline/V1__offline_payment",
                            "event/V1__mall_events"))
                new ResourceDatabasePopulator(new ClassPathResource("db/mall/" + file + ".sql"))
                        .execute(mall.ds);
            PlatformEndActivationMysqlTest.platformSchema(platform);
            platform.jdbc.update(
                    "INSERT INTO mch_store(tenant_no,store_no,entity_no,business_type_code) VALUES"
                            + " ('tenant-a','store','merchant','RETAIL')");
            var f = new JdbcCatalogFixture(mall, new JdbcCatalogRepository(mall.ds, foundation.ds));
            MallCatalogPublicationMysqlTest.seedEnds(mall);
            mall.jdbc.update(
                    "UPDATE mall_end_entitlement SET entitlement_code='POS_USE' WHERE"
                            + " end_code='POS'");
            f.accepted.add("POS_USE");
            f.facade.savePlan(f.ops, f.plan("FREE", "free-1", List.of("BASE")));
            f.facade.savePlan(f.ops, f.plan("PRO", "pro-1", List.of("BASE", "POS_USE")));
            f.facade.savePlan(f.ops, f.plan("CHAIN", "chain-1", List.of("BASE", "POS_USE", "KDS")));
            f.product();
            var sku =
                    hardware
                            ? f.sku(
                                    "sku-1",
                                    List.of(
                                            f.component(
                                                    null,
                                                    ComponentKind.HARDWARE,
                                                    null,
                                                    null,
                                                    "device",
                                                    null,
                                                    1,
                                                    1,
                                                    false)))
                            : f.sku();
            f.facade.saveSku(f.ops, sku);
            f.facade.publishIndustry(f.ops, f.publication(List.of("sku-1"), 7));
            var clock = Clock.systemUTC();
            var store =
                    new StoreSnapshot(
                            STORE,
                            "merchant",
                            "RETAIL",
                            StoreStatus.ACTIVE,
                            CommerceModel.STORE_V1);
            StoreDirectoryPort stores =
                    ref -> Optional.of(store).filter(s -> s.store().equals(ref));
            ActorAuthorizationPort auth =
                    (a, s, cap) -> new AuthorizationDecision(true, "OWNER", Optional.empty());
            var events = new JdbcCommerceEventPersistence(mall.ds);
            var subs =
                    new JdbcSubscriptionRepository(
                            mall.ds, f.ids, new JdbcEndActivation(mall.ds, platform.ds, f.ids));
            var schedule = new EndScheduleService(subs, f.uow, events, f.ids, clock);
            f.uow.required(
                    () -> {
                        subs.ensureAccount(store, "buyer");
                        subs.insert(
                                subs.lock(STORE),
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
                                        Map.of("BASE", "BASE"),
                                        0,
                                        0,
                                        "CNY",
                                        "p",
                                        false),
                                1,
                                "buyer");
                        schedule.persist(
                                subs.lock(STORE),
                                Set.of("BASE"),
                                "initial",
                                Instant.ofEpochMilli(1000),
                                Set.of("free"),
                                Map.of(),
                                "buyer");
                        return null;
                    });
            var delivery =
                    new SubscriptionService(subs, f.repo, f.uow, schedule, events, f.ids, clock);
            var orders = new JdbcMallRepository(mall.ds, events, f.ids);
            var accounts = new JdbcSubscriptionAccountRepository(mall.ds);
            var trade =
                    new DefaultMallFacade(
                            orders, f.repo, accounts, f.uow, stores, auth, clock, f.ids);
            var terms =
                    new OfflineCollectionTerms(
                            "bank",
                            1,
                            "platform",
                            "CNY",
                            "bank-account",
                            "w",
                            86400000,
                            Map.of("bank", "test"));
            var quote =
                    trade.quoteOffline(
                            BUYER,
                            new QuoteCommand(STORE, List.of(new QuoteLine("sku-1", 1)), "CNY"),
                            terms);
            var address = new ShippingAddress("Buyer", "123456", "CN", null, null, "Address", null);
            var order =
                    trade.placeOrder(
                            BUYER,
                            new PlaceOrderCommand(
                                    quote.quoteNo(),
                                    "buy",
                                    hardware ? Optional.of(address) : Optional.empty(),
                                    STORE));
            var target = new OrderQuery(STORE, order.orderNo());
            var bridge = new MallFulfillmentMysqlTest.Bridge();
            var ops = new JdbcFulfillmentRepository(mall.ds, true);
            var payments =
                    new CommerceFulfillmentService(
                            orders,
                            ops,
                            accounts,
                            f.uow,
                            delivery,
                            bridge,
                            bridge,
                            events,
                            clock,
                            f.ids,
                            "platform",
                            Set.of("OFFLINE:BANK_TRANSFER"),
                            auth,
                            (s, e, o, at, now) -> {});
            var visible = new java.util.concurrent.atomic.AtomicBoolean(hardware);
            var money =
                    new OfflineCollectionPort() {
                        public ManualResult confirm(ManualPayment cmd) {
                            assertEquals(order.orderNo(), cmd.original().orderNo());
                            assertEquals(order.payable(), cmd.actualAmount());
                            bridge.payment =
                                    new PaymentOutcome(
                                            order.orderNo(),
                                            Optional.of("pay"),
                                            "platform",
                                            order.payable(),
                                            visible.get()
                                                    ? AsyncStatus.SUCCEEDED
                                                    : AsyncStatus.PENDING,
                                            visible.get()
                                                    ? Optional.of(cmd.actualPaidAt())
                                                    : Optional.empty(),
                                            Optional.empty());
                            return new ManualResult("MATCHED", "pay");
                        }
                    };
            var media =
                    new PrivateEvidencePort() {
                        final Map<String, Scope> stored = new HashMap<>();

                        public void store(Scope scope, String key, Upload bytes) {
                            stored.put(key, scope);
                        }

                        public boolean stored(Scope scope, String key) {
                            return scope.equals(stored.get(key));
                        }

                        public String signedRead(Scope scope, String key) {
                            assertTrue(stored(scope, key));
                            return key;
                        }
                    };
            var offline =
                    new DefaultOfflinePurchaseFacade(
                            orders,
                            ops,
                            new JdbcMallOfflineRepository(mall.ds),
                            accounts,
                            f.uow,
                            stores,
                            auth,
                            trade,
                            payments,
                            null,
                            money,
                            media,
                            clock,
                            f.ids);
            assertEquals(order.payable(), offline.issue(BUYER, target).payable());
            var bytes =
                    new PrivateEvidencePort.Upload(
                            () -> new java.io.ByteArrayInputStream(new byte[] {1}),
                            1,
                            "image/jpeg");
            var proof = offline.reserveEvidence(BUYER, target, "BUYER_RECEIPT").assetKey();
            offline.upload(BUYER, target, proof, bytes);
            var paidAt = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            var claim =
                    new OfflinePurchaseFacade.ClaimCommand(
                            target, "claim", order.payable(), paidAt, "transfer", List.of(proof));
            offline.submit(BUYER, claim);
            assertEquals(
                    order.orderNo(),
                    offline.pendingClaims(f.ops, null, null, 10).getFirst().orderNo());
            assertEquals(List.of(proof), offline.claims(f.ops, target).getFirst().evidenceKeys());
            assertEquals(proof, offline.evidence(f.ops, target, proof));
            assertEquals(
                    "PAYING",
                    mall.jdbc.queryForObject(
                            "SELECT pay_status FROM mall_order WHERE order_no=?",
                            String.class,
                            order.orderNo()));
            var receipt = offline.reserveEvidence(f.ops, target, "PLATFORM_RECEIPT").assetKey();
            offline.upload(f.ops, target, receipt, bytes);
            var review =
                    new OfflinePurchaseFacade.ReviewCommand(
                            target,
                            "confirm",
                            "claim",
                            order.payable(),
                            paidAt,
                            "platform",
                            "statement",
                            receipt,
                            false,
                            "bank matched");
            if (!hardware) {
                assertEquals("ACCEPTED", offline.confirm(f.ops, review).status());
                assertEquals("REVIEWING", offline.claims(BUYER, target).getFirst().status());
                assertEquals(
                        0,
                        platform.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mch_store_end_grant WHERE"
                                        + " entitlement_code='POS_USE'",
                                Integer.class));
                visible.set(true);
                offline.recover(
                        new ai.neargo.commerce.api.fulfillment.FulfillmentQuery(
                                STORE.tenantNo(), order.orderNo()));
            }
            var result = offline.confirm(f.ops, review);
            assertEquals("APPLIED", result.status());
            assertEquals(result, offline.confirm(f.ops, review));
            assertEquals("CONFIRMED", offline.claims(BUYER, target).getFirst().status());
            assertEquals(
                    "PAID",
                    mall.jdbc.queryForObject(
                            "SELECT pay_status FROM mall_order WHERE order_no=?",
                            String.class,
                            order.orderNo()));
            assertEquals(
                    hardware ? "PARTIAL" : "COMPLETED",
                    mall.jdbc.queryForObject(
                            "SELECT fulfillment_status FROM mall_order WHERE order_no=?",
                            String.class,
                            order.orderNo()));
            assertEquals(hardware ? 1 : 0, bridge.reserves);
            assertEquals(0, bridge.ships);
            assertEquals(
                    hardware ? 0 : 1,
                    platform.jdbc.queryForObject(
                            "SELECT COUNT(*) FROM mch_store_end_grant WHERE tenant_no=? AND"
                                + " store_no=? AND end_code='POS' AND entitlement_code='POS_USE'"
                                + " AND state IN ('ACTIVE','SCHEDULED')",
                            Integer.class,
                            STORE.tenantNo(),
                            STORE.storeNo()));
        }
    }
}
