package ai.neargo.commerce.persistence;

import static ai.neargo.commerce.persistence.MallOrderConcurrencyMysqlTest.*;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.ProductKind;
import ai.neargo.commerce.api.catalog.SkuComponent;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.fulfillment.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.offline.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.access.EndScheduleService;
import ai.neargo.commerce.core.fulfillment.CommerceFulfillmentService;
import ai.neargo.commerce.core.mall.DefaultMallFacade;
import ai.neargo.commerce.core.offline.DefaultOfflinePurchaseFacade;
import ai.neargo.commerce.core.query.DefaultMallQueryFacade;
import ai.neargo.commerce.core.spi.Period;
import ai.neargo.commerce.core.subscription.SubscriptionService;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.*;
import java.util.*;

class MallOfflineOrderDeliveryMysqlTest {
    @ParameterizedTest
    @CsvSource({"HARDWARE,false", "PLAN,false", "BUNDLE,false", "BUNDLE,true"})
    void confirmedTransferFulfillsOriginalOrder(ProductKind kind, boolean delayGrant) {
        boolean hardware = kind != ProductKind.PLAN;
        boolean plan = kind != ProductKind.HARDWARE;
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
            var components = new ArrayList<SkuComponent>();
            if (plan) components.addAll(f.sku().components());
            if (hardware)
                components.add(
                        f.component(
                                null,
                                ComponentKind.HARDWARE,
                                null,
                                null,
                                "device",
                                null,
                                2,
                                1,
                                false));
            var sku = f.sku("sku-1", components);
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
            var activation = new JdbcEndActivation(mall.ds, platform.ds, f.ids);
            var subs = new JdbcSubscriptionRepository(mall.ds, f.ids, activation);
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
            var bridge =
                    new MallFulfillmentMysqlTest.Bridge() {
                        boolean lostResponse = true;
                        final Map<String, PaymentRefundCommand> commands = new HashMap<>();
                        final Map<String, VerifiedRefundFact> paidOut = new HashMap<>();

                        public PaymentRefundOutcome refund(PaymentRefundCommand cmd) {
                            assertNull(commands.putIfAbsent(cmd.refundNo(), cmd));
                            throw new IllegalStateException("response lost after refund accepted");
                        }

                        public PaymentRefundOutcome queryRefund(PaymentRefundQuery query) {
                            var fact = paidOut.get(query.refundNo());
                            return fact == null
                                    ? null
                                    : new PaymentRefundOutcome(
                                            query.refundNo(),
                                            AsyncStatus.SUCCEEDED,
                                            Optional.of(fact.providerRefundNo()),
                                            Optional.empty(),
                                            fact);
                        }

                        public InventoryOutcome ship(InventoryCommand c) {
                            var sent = super.ship(c);
                            if (lostResponse) {
                                lostResponse = false;
                                throw new IllegalStateException(
                                        "lost warehouse response after dispatch");
                            }
                            return sent;
                        }
                    };
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
                        public void payout(RefundPayout payout) {
                            var c = bridge.commands.get(payout.refundNo());
                            assertEquals(c.orderNo(), payout.orderNo());
                            assertEquals(c.amount(), payout.actualAmount());
                            bridge.paidOut.putIfAbsent(
                                    c.refundNo(),
                                    new VerifiedRefundFact(
                                            c.tenantNo(),
                                            "event-" + c.refundNo(),
                                            c.orderNo(),
                                            c.refundNo(),
                                            c.store(),
                                            c.paymentAttemptNo(),
                                            c.originalPaymentNo(),
                                            c.platformMerchantNo(),
                                            c.originalPaid(),
                                            c.amount(),
                                            payout.bankReference(),
                                            AsyncStatus.SUCCEEDED,
                                            true,
                                            payout.paidOutAt(),
                                            payout.evidenceReference()));
                        }

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
            var refundRepo = new JdbcRefundRepository(mall.ds, f.ids, subs, true);
            var refunds =
                    new ai.neargo.commerce.core.fulfillment.CommerceRefundService(
                            orders,
                            refundRepo,
                            ops,
                            subs,
                            accounts,
                            schedule,
                            f.uow,
                            auth,
                            bridge,
                            clock);
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
                            refunds,
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
            if (delayGrant) {
                platform.jdbc.execute(
                        "CREATE TRIGGER grant_down BEFORE INSERT ON mch_store_end_grant FOR EACH"
                            + " ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='grant unavailable'");
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
            String grants =
                    "SELECT COUNT(*) FROM mch_store_end_grant WHERE tenant_no=? AND store_no=? AND"
                        + " end_code='POS' AND entitlement_code='POS_USE' AND state IN"
                        + " ('ACTIVE','SCHEDULED')";
            assertEquals(
                    plan && !delayGrant ? 1 : 0,
                    platform.jdbc.queryForObject(
                            grants, Integer.class, STORE.tenantNo(), STORE.storeNo()));
            if (hardware) {
                var queries =
                        new DefaultMallQueryFacade(
                                orders,
                                ops,
                                new JdbcRefundRepository(mall.ds, f.ids, subs, true),
                                accounts,
                                f.uow,
                                stores,
                                auth);
                var item = queries.order(f.ops, target).hardwareItems().getFirst();
                assertEquals("device", item.hardwareSkuNo());
                assertEquals(2, item.quantity());
                var part = item.orderComponentNo();
                var first =
                        new HardwareShipmentCommand(
                                STORE.tenantNo(),
                                order.orderNo(),
                                part,
                                "first",
                                "carrier",
                                "track-1",
                                1,
                                f.ops.principalNo());
                payments.confirmShipment(first);
                assertEquals(
                        AsyncStatus.PENDING,
                        queries.order(BUYER, target).shipments().getFirst().status());
                var second =
                        new HardwareShipmentCommand(
                                STORE.tenantNo(),
                                order.orderNo(),
                                part,
                                "second",
                                "carrier",
                                "track-2",
                                1,
                                f.ops.principalNo());
                assertEquals(
                        "SHIPMENT_UNKNOWN",
                        assertThrows(MallException.class, () -> payments.confirmShipment(second))
                                .code());
                payments.recover(new FulfillmentQuery(STORE.tenantNo(), order.orderNo()));
                payments.confirmShipment(first);
                assertEquals(1, bridge.ships);
                assertEquals("PARTIAL", queries.order(BUYER, target).summary().fulfillmentStatus());
                assertEquals(
                        "REFUND_LIMIT",
                        assertThrows(
                                        MallException.class,
                                        () ->
                                                refunds.preview(
                                                        BUYER,
                                                        new RefundPreviewCommand(
                                                                STORE,
                                                                order.orderNo(),
                                                                "too-many",
                                                                List.of(),
                                                                new HardwareRefund(part, 2))))
                                .code());
                assertEquals(
                        "INVALID_SOURCE",
                        assertThrows(
                                        MallException.class,
                                        () ->
                                                refunds.preview(
                                                        BUYER,
                                                        new RefundPreviewCommand(
                                                                STORE,
                                                                order.orderNo(),
                                                                "foreign",
                                                                List.of(),
                                                                new HardwareRefund("foreign", 1))))
                                .code());
                payments.confirmShipment(second);
                payments.confirmShipment(second);
                var detail = queries.order(BUYER, target);
                assertEquals("COMPLETED", detail.summary().fulfillmentStatus());
                assertEquals(
                        List.of("track-1", "track-2"),
                        detail.shipments().stream()
                                .map(MallOrderDetailView.Shipment::trackingNo)
                                .toList());
                assertEquals(
                        List.of("first", "second"),
                        detail.shipments().stream()
                                .map(MallOrderDetailView.Shipment::requestOperationNo)
                                .toList());
                assertTrue(
                        detail.shipments().stream()
                                .allMatch(
                                        x ->
                                                part.equals(x.orderComponentNo())
                                                        && "carrier".equals(x.carrierCode())
                                                        && x.quantity() == 1
                                                        && x.status() == AsyncStatus.SUCCEEDED));
                assertEquals(2, bridge.ships);
                assertEquals(
                        2,
                        mall.jdbc.queryForObject(
                                "SELECT COUNT(*) FROM mall_shipment WHERE order_no=? AND"
                                    + " status='SHIPPED'",
                                Integer.class,
                                order.orderNo()));
                if (delayGrant) {
                    platform.jdbc.execute("DROP TRIGGER grant_down");
                    mall.jdbc.update(
                            "UPDATE mall_fulfillment_task SET next_retry_at=0 WHERE"
                                + " task_type='END_ACTIVATION' AND status='PENDING'");
                    assertEquals(0, activation.retryPendingAll(10));
                }
                if (plan) {
                    payments.recover(new FulfillmentQuery(STORE.tenantNo(), order.orderNo()));
                    assertEquals(
                            1,
                            platform.jdbc.queryForObject(
                                    grants, Integer.class, STORE.tenantNo(), STORE.storeNo()));
                    assertEquals(
                            1,
                            mall.jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM mch_subscription_period WHERE"
                                        + " source_type='ORDER'",
                                    Integer.class));
                    assertEquals(2, bridge.ships);
                }
                long total = 0;
                for (String selection : List.of("hardware", plan ? "plan" : "last-hardware")) {
                    String refundNo = "refund-" + selection;
                    var current = queries.order(BUYER, target);
                    boolean device = !"plan".equals(selection);
                    boolean lastUnit = "last-hardware".equals(selection);
                    var hw = device ? new HardwareRefund(part, 1) : null;
                    List<String> periods =
                            device
                                    ? List.of()
                                    : List.of(current.deliveredSources().getFirst().periodNo());
                    var preview =
                            refunds.preview(
                                    BUYER,
                                    new RefundPreviewCommand(
                                            STORE, order.orderNo(), refundNo, periods, hw));
                    var cmd =
                            new RefundCommand(
                                    STORE,
                                    order.orderNo(),
                                    refundNo,
                                    preview.cashLimit(),
                                    "after-sale",
                                    periods,
                                    hw);
                    var requested = refunds.request(BUYER, cmd);
                    assertEquals(requested, refunds.request(BUYER, cmd));
                    var reviewed =
                            refunds.reviewQuote(
                                    f.ops,
                                    new RefundReviewQuoteCommand(
                                            STORE,
                                            order.orderNo(),
                                            refundNo,
                                            requested.requestDigest(),
                                            "review-" + selection));
                    var price = reviewed.preview();
                    var approval =
                            new RefundApprovalCommand(
                                    STORE,
                                    order.orderNo(),
                                    refundNo,
                                    price.approvalDigest(),
                                    price.cashLimit(),
                                    price.creditLimit(),
                                    "matched original component",
                                    reviewed.reviewQuoteNo());
                    refunds.approve(f.ops, approval);
                    var q = new RefundQuery(STORE.tenantNo(), order.orderNo(), refundNo);
                    assertTrue(price.cashLimit().minor() > 0);
                    assertEquals(AsyncStatus.UNKNOWN, refunds.recover(q).status());
                    assertEquals(AsyncStatus.UNKNOWN, refunds.recover(q).status());
                    var key = offline.reserveEvidence(f.ops, target, "REFUND_PROOF").assetKey();
                    offline.upload(f.ops, target, key, bytes);
                    var payout =
                            new OfflinePurchaseFacade.RefundPayoutCommand(
                                    target,
                                    refundNo,
                                    price.cashLimit(),
                                    paidAt,
                                    "buyer",
                                    "bank-" + selection,
                                    key);
                    var done = offline.confirmRefundPayout(f.ops, payout);
                    assertEquals(AsyncStatus.SUCCEEDED, done.status());
                    String versions =
                            "SELECT COALESCE(SUM(version),0) FROM mch_subscription_period WHERE"
                                + " source_type='ORDER'";
                    var version = mall.jdbc.queryForObject(versions, Long.class);
                    assertEquals(done, offline.confirmRefundPayout(f.ops, payout));
                    assertEquals(done, refunds.recover(q));
                    assertEquals(version, mall.jdbc.queryForObject(versions, Long.class));
                    assertEquals(lastUnit ? "REFUNDED" : "PARTIAL", queries.order(BUYER, target).summary().refundStatus());
                    total += price.cashLimit().minor();
                    assertEquals(
                            total,
                            mall.jdbc.queryForObject(
                                    "SELECT cash_refunded_minor FROM mall_order WHERE order_no=?",
                                    Long.class,
                                    order.orderNo()));
                    assertEquals(
                            lastUnit ? 2 : 1,
                            mall.jdbc.queryForObject(
                                    "SELECT SUM(quantity) FROM mall_refund_item WHERE"
                                        + " order_component_no=? AND rights_action='NONE' AND"
                                        + " status='SUCCEEDED'",
                                    Integer.class,
                                    part));
                    assertEquals(
                            "REFUND_LIMIT",
                            assertThrows(
                                            MallException.class,
                                            () ->
                                                    refunds.preview(
                                                            BUYER,
                                                            new RefundPreviewCommand(
                                                                    STORE,
                                                                    order.orderNo(),
                                                                    "excess",
                                                                    List.of(),
                                                                    new HardwareRefund(part, 2))))
                                    .code());
                    assertEquals(
                            device && plan ? 1 : 0,
                            platform.jdbc.queryForObject(
                                    grants, Integer.class, STORE.tenantNo(), STORE.storeNo()));
                    assertEquals(2, bridge.ships);
                    if (plan) {
                        var source = queries.order(BUYER, target).deliveredSources().getFirst();
                        assertEquals(List.of("POS_USE"), source.entitlementCodes());
                        assertEquals(device ? "NORMAL" : "REFUNDED", source.lifecycle());
                    }
                }
                assertEquals(2, bridge.paidOut.size());
                if (!plan) assertEquals(order.payable().minor(), total);
            }
        }
    }
}
