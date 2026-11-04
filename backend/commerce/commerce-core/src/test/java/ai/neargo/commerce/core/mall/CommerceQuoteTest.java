package ai.neargo.commerce.core.mall;

import static ai.neargo.commerce.core.mall.MallSnapshot.*;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.*;
import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.*;
import ai.neargo.commerce.core.spi.*;

import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.*;

class CommerceQuoteTest {
    final StoreRef store = new StoreRef("t", "s");
    final ActorContext buyer = new ActorContext("t", SubjectKind.ACCOUNT, "buyer", "trusted");
    final CatalogState catalog = new CatalogState();
    long now = 1000, entitlement = 1, balanceVersion = 1, available = 50;
    String pending;
    boolean allowed = true;
    boolean forceDuplicateClaim;
    String storeCurrency = "CNY";
    String storeMerchant = "merchant";
    List<BasePlan> plans = new ArrayList<>();
    List<ConversionSource> conversions = List.of();
    final Map<String, Quote> quotes = new HashMap<>();
    final Map<String, Order> orders = new HashMap<>();
    final MallRepository repo =
            new MallRepository() {
                public CreditBalance lockBalance(StoreRef s, String c) {
                    return new CreditBalance(balanceVersion, available);
                }

                public Sources lockSources(StoreRef s, long at) {
                    return new Sources(storeCurrency, plans, Set.of("BASE"), conversions);
                }

                public List<CreditLot> lockLots(StoreRef s, String c, long at) {
                    return List.of(new CreditLot("lot", 1, available, null));
                }

                public void saveQuote(Quote q, String a) {
                    quotes.put(q.quoteNo(), q);
                }

                public Optional<Quote> quote(String t, String n) {
                    return Optional.ofNullable(quotes.get(n))
                            .filter(q -> q.store().tenantNo().equals(t));
                }

                public boolean claimOrder(
                        String tenant, String buyer, String key, String hash, long at) {
                    if (forceDuplicateClaim) return false;
                    var existing = byIdempotency(tenant, buyer, key);
                    if (existing.isPresent() && !hash.equals(existing.get().requestHash()))
                        throw new MallException("IDEMPOTENCY_CONFLICT");
                    return existing.isEmpty();
                }

                public Optional<Order> byIdempotency(String tenant, String buyer, String key) {
                    return orders.values().stream()
                            .filter(
                                    o ->
                                            o.quote().store().tenantNo().equals(tenant)
                                                    && o.quote().buyerNo().equals(buyer)
                                                    && o.idempotencyKey().equals(key))
                            .findFirst();
                }

                public void consumeQuote(String tenant, String quoteNo, String actor, long at) {
                    if (orders.values().stream()
                            .anyMatch(
                                    o ->
                                            o.quote().store().tenantNo().equals(tenant)
                                                    && o.quote().quoteNo().equals(quoteNo)))
                        throw new MallException("QUOTE_USED");
                }

                public Optional<Order> order(String t, String n) {
                    return Optional.ofNullable(orders.get(n))
                            .filter(o -> o.quote().store().tenantNo().equals(t));
                }

                public List<OrderComponent> components(StoreRef s, String no) {
                    return List.of();
                }

                public void create(Order o, AccountSnapshot a, CreditBalance b, String actor) {
                    orders.put(o.orderNo(), o);
                    if (o.quote().digital()) pending = o.orderNo();
                }

                public Order transition(Order o, OrderState.Axes s, String actor) {
                    var next = o.withAxes(s);
                    orders.put(o.orderNo(), next);
                    return next;
                }
            };
    final CatalogRepository catalogs =
            new CatalogRepository() {
                public CatalogState load(String t, CatalogLockMode m) {
                    return catalog;
                }

                public void save(String t, CatalogState a, CatalogState b, CatalogWriteContext c) {
                    throw new UnsupportedOperationException();
                }
            };
    final SubscriptionAccountRepository accounts =
            new SubscriptionAccountRepository() {
                public long readEntitlementVersion(StoreRef s) {
                    return entitlement;
                }

                public AccountSnapshot lockSubscriptionAccount(StoreRef s) {
                    return new AccountSnapshot(s, "merchant", "RETAIL", entitlement, pending, 1);
                }
            };
    final CommerceUnitOfWork uow =
            new CommerceUnitOfWork() {
                public <T> T required(java.util.function.Supplier<T> s) {
                    return s.get();
                }
            };
    final Clock clock =
            new Clock() {
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                public Clock withZone(ZoneId z) {
                    return this;
                }

                public Instant instant() {
                    return Instant.ofEpochMilli(now);
                }
            };
    final DefaultMallFacade facade =
            new DefaultMallFacade(
                    repo,
                    catalogs,
                    accounts,
                    uow,
                    s ->
                            Optional.of(
                                    new StoreSnapshot(
                                            s,
                                            storeMerchant,
                                            "RETAIL",
                                            StoreStatus.ACTIVE,
                                            CommerceModel.STORE_V1)),
                    (a, s, c) ->
                            new AuthorizationDecision(
                                    allowed && "trusted".equals(a.accountSessionFingerprint()),
                                    "OWNER",
                                    Optional.empty()),
                    clock,
                    n -> UUID.randomUUID().toString());

    CommerceQuoteTest() {
        catalog.ends.put(
                "POS",
                new CatalogState.End(
                        new BusinessEndDraft(
                                "POS",
                                "POS",
                                "POS",
                                EndScope.STORE,
                                List.of("RETAIL"),
                                ReleaseStatus.VERIFIED,
                                true,
                                false,
                                true,
                                "client",
                                "routes",
                                "accept",
                                0,
                                ""),
                        1));
        sku("v", ComponentKind.PLAN, "p", PlanTier.PRO, 101);
        sku("hw", ComponentKind.HARDWARE, null, null, 80);
    }

    void sku(String no, ComponentKind kind, String planNo, PlanTier tier, long amount) {
        String pv = planNo == null ? null : planNo + "v";
        if (pv != null)
            catalog.planVersions.put(
                    pv,
                    new CatalogState.PlanVersion(
                            new PlanDraft(
                                    planNo,
                                    pv,
                                    "RETAIL",
                                    tier,
                                    "Plan",
                                    "",
                                    List.of("POS"),
                                    "policy",
                                    0,
                                    ""),
                            1,
                            "PUBLISHED",
                            1,
                            0L,
                            "ops"));
        if (pv != null)
            catalog.plans.put(
                    planNo, new CatalogState.Plan(planNo, "RETAIL", tier, pv, "ON_SALE", 1));
        var c =
                new SkuComponent(
                        no + "c",
                        kind,
                        pv,
                        null,
                        kind == ComponentKind.HARDWARE ? "hw" : null,
                        kind == ComponentKind.HARDWARE ? null : 30,
                        2,
                        1,
                        false,
                        "ON_FULFILLMENT");
        var d =
                new SkuDraft(
                        "product" + no,
                        no,
                        no,
                        "SKU",
                        new Money("CNY", amount),
                        new Money("CNY", amount),
                        List.of("RETAIL"),
                        "{}",
                        List.of(c),
                        5,
                        0,
                        "");
        catalog.types.put(
                "RETAIL",
                new CatalogState.Type(
                        new BusinessTypeDraft("RETAIL", "Retail", 0, true, 0, ""), 1, 1));
        catalog.products.put(
                d.productNo(),
                new CatalogState.Product(
                        new ProductDraft(
                                d.productNo(),
                                "cat",
                                kind == ComponentKind.HARDWARE
                                        ? ProductKind.HARDWARE
                                        : ProductKind.PLAN,
                                "Product",
                                "",
                                List.of("RETAIL"),
                                0,
                                0,
                                ""),
                        "ON_SALE",
                        1));
        catalog.skus.put(no, new CatalogState.Sku(no, d.productNo(), "SKU", 5, no, "ON_SALE", 1));
        catalog.skuVersions.put(no, new CatalogState.SkuVersion(d, 1, "PUBLISHED", 1, 0L));
    }

    QuoteView quote(String no) {
        return facade.quote(buyer, new QuoteCommand(store, List.of(new QuoteLine(no, 1)), "CNY"));
    }

    PlaceOrderCommand place(QuoteView q, String key) {
        return new PlaceOrderCommand(q.quoteNo(), key, Optional.empty());
    }

    void error(String code, Runnable action) {
        assertEquals(code, assertThrows(MallException.class, action::run).code());
    }

    @Test
    void snapshotsPricesAndTargetsAndSameKeyReturnsSameOrderButDifferentParametersConflict() {
        var q = quote("v");
        assertEquals(50, q.credit().minor());
        assertEquals(51, q.payable().minor());
        assertEquals(now + 900000, q.expiresAt().toEpochMilli());
        var command = place(q, "key");
        var first = facade.placeOrder(buyer, command);
        now += 1000000;
        allowed = true;
        assertEquals(first, facade.placeOrder(buyer, command));
        error(
                "IDEMPOTENCY_CONFLICT",
                () ->
                        facade.placeOrder(
                                buyer,
                                new PlaceOrderCommand(
                                        q.quoteNo(),
                                        "key",
                                        Optional.of(
                                                new ShippingAddress(
                                                        "x", "p", "CN", "r", "c", "a", "z")))));
        assertEquals(
                "pv",
                quotes.get(q.quoteNo())
                        .lines()
                        .getFirst()
                        .components()
                        .getFirst()
                        .plan()
                        .planVersionNo());
    }

    @Test
    void expiryVersionsBuyerTenantAndTrustedAuthorityAreEnforced() {
        var q = quote("v");
        now = q.expiresAt().toEpochMilli();
        error("QUOTE_EXPIRED", () -> facade.placeOrder(buyer, place(q, "k")));
        now = 1000;
        entitlement++;
        error("VERSION_CONFLICT", () -> facade.placeOrder(buyer, place(q, "k")));
        entitlement--;
        balanceVersion++;
        error("VERSION_CONFLICT", () -> facade.placeOrder(buyer, place(q, "k")));
        balanceVersion--;
        error(
                "NOT_FOUND",
                () ->
                        facade.placeOrder(
                                new ActorContext("t", SubjectKind.ACCOUNT, "other", "trusted"),
                                place(q, "k")));
        error(
                "NOT_FOUND",
                () ->
                        facade.placeOrder(
                                new ActorContext("other", SubjectKind.ACCOUNT, "buyer", "trusted"),
                                place(q, "k")));
        allowed = false;
        error("FORBIDDEN", () -> quote("v"));
    }

    @Test
    void twoDigitalOrdersShareOccupancyButHardwareDoesNotAndCancelRetainsIt() {
        var a = quote("v");
        var b = quote("v");
        var first = facade.placeOrder(buyer, place(a, "a"));
        error("ACCOUNT_BUSY", () -> facade.placeOrder(buyer, place(b, "b")));
        var hardware = quote("hw");
        assertEquals(0, hardware.credit().minor());
        var h =
                facade.placeOrder(
                        buyer,
                        new PlaceOrderCommand(
                                hardware.quoteNo(),
                                "h",
                                Optional.of(
                                        new ShippingAddress("r", "p", "CN", "r", "c", "a", "z"))));
        assertEquals(OrderStatus.PENDING_INVENTORY, h.status());
        assertEquals(first.orderNo(), pending);
        assertEquals(
                OrderStatus.CANCEL_PENDING,
                facade.cancelOrder(
                                buyer,
                                new CancelOrderCommand(store, first.orderNo(), "cancel", "reason"))
                        .status());
        assertEquals(first.orderNo(), pending);
    }

    @Test
    void freeDoesNotBlockPaidBuyAndRenewalRequiresSameFamilyAndTier() {
        plans.add(new BasePlan("free", PlanTier.FREE));
        var q = quote("v");
        assertEquals("BUY", quotes.get(q.quoteNo()).intent());
        plans.add(new BasePlan("p", PlanTier.PRO));
        q = quote("v");
        assertEquals("RENEW", quotes.get(q.quoteNo()).intent());
        plans.clear();
        plans.add(new BasePlan("other", PlanTier.PRO));
        error("UPGRADE_REQUIRED", () -> quote("v"));
        plans.clear();
        plans.add(new BasePlan("chain", PlanTier.CHAIN));
        error("UPGRADE_REQUIRED", () -> quote("v"));
    }

    @Test
    void delistingRejectsNewPlacementButPreservesCreatedOrderAndItsReplay() {
        var q =
                facade.quote(
                        buyer,
                        new QuoteCommand(
                                store,
                                List.of(new QuoteLine("v", 1), new QuoteLine("v", 2)),
                                "CNY"));
        assertEquals(303, q.total().minor());
        assertEquals(6, q.components().getFirst().quantity());
        error(
                "INVALID_REQUEST",
                () ->
                        facade.quote(
                                buyer,
                                new QuoteCommand(
                                        store,
                                        List.of(new QuoteLine("v", 5), new QuoteLine("v", 1)),
                                        "CNY")));
        var notPlaced = quote("v");
        var created = facade.placeOrder(buyer, place(q, "created"));
        var d = catalog.skus.get("v");
        catalog.skus.put(
                "v",
                new CatalogState.Sku(
                        d.skuNo(),
                        d.productNo(),
                        d.title(),
                        d.maxPerOrder(),
                        d.currentVersionNo(),
                        "OFF_SALE",
                        2));
        error("NOT_SELLABLE", () -> quote("v"));
        error("NOT_SELLABLE", () -> facade.placeOrder(buyer, place(notPlaced, "stopped")));
        assertEquals(created, facade.placeOrder(buyer, place(q, "created")));
        assertEquals(created, facade.order(buyer, new OrderQuery(store, created.orderNo())));
    }

    @Test
    void missingUpgradeSourcesAndDisabledRefundsFailClosedAndCrossStoreReadDenied() {
        error(
                "NO_UPGRADE_SOURCE",
                () -> facade.quoteUpgrade(buyer, new UpgradeCommand(store, "pv", "op", 1)));
        error(
                "REFUND_UNAVAILABLE",
                () ->
                        facade.refund(
                                buyer,
                                new RefundCommand(
                                        store, "order", "refund", new Money("CNY", 1), "")));
        var o = facade.placeOrder(buyer, place(quote("v"), "k"));
        error(
                "NOT_FOUND",
                () -> facade.order(buyer, new OrderQuery(new StoreRef("t", "else"), o.orderNo())));
        allowed = false;
        error("FORBIDDEN", () -> facade.order(buyer, new OrderQuery(store, o.orderNo())));
    }

    @Test
    void storeCurrencyMultipleBasePlansAndAmountOverflowRejectBeforePersistence() {
        storeCurrency = "USD";
        error("CURRENCY_MISMATCH", () -> quote("v"));
        storeCurrency = "CNY";
        sku("v2", ComponentKind.PLAN, "p", PlanTier.PRO, 101);
        error(
                "MULTIPLE_BASE_PLANS",
                () ->
                        facade.quote(
                                buyer,
                                new QuoteCommand(
                                        store,
                                        List.of(new QuoteLine("v", 1), new QuoteLine("v2", 1)),
                                        "CNY")));
        sku("huge", ComponentKind.PLAN, "p", PlanTier.PRO, Long.MAX_VALUE);
        error(
                "INVALID_REQUEST",
                () ->
                        facade.quote(
                                buyer,
                                new QuoteCommand(store, List.of(new QuoteLine("huge", 2)), "CNY")));
        error(
                "INVALID_REQUEST",
                () ->
                        facade.quote(
                                buyer,
                                new QuoteCommand(
                                        store,
                                        List.of(
                                                new QuoteLine("v", Integer.MAX_VALUE),
                                                new QuoteLine("v", 1)),
                                        "CNY")));
        assertTrue(quotes.isEmpty());
    }

    @Test
    void quoteCannotBeConsumedWithAnotherKeyAndReadDoesNotLeakOtherTenants() {
        var q = quote("v");
        var order = facade.placeOrder(buyer, place(q, "k"));
        error("QUOTE_USED", () -> facade.placeOrder(buyer, place(q, "another")));
        error(
                "FORBIDDEN",
                () ->
                        facade.order(
                                new ActorContext("other", SubjectKind.ACCOUNT, "buyer", "trusted"),
                                new OrderQuery(store, order.orderNo())));
        error(
                "FORBIDDEN",
                () ->
                        facade.quote(
                                new ActorContext("t", SubjectKind.SYSTEM, "system", "trusted"),
                                new QuoteCommand(store, List.of(new QuoteLine("v", 1)), "CNY")));
    }

    @Test
    void idempotencyHashDistinguishesNullAddressFieldsFromLiteralText() {
        var q = quote("v");
        facade.placeOrder(
                buyer,
                new PlaceOrderCommand(
                        q.quoteNo(),
                        "hash",
                        Optional.of(
                                new ShippingAddress("r", "p", "CN", null, "city", "line", "zip"))));
        error(
                "IDEMPOTENCY_CONFLICT",
                () ->
                        facade.placeOrder(
                                buyer,
                                new PlaceOrderCommand(
                                        q.quoteNo(),
                                        "hash",
                                        Optional.of(
                                                new ShippingAddress(
                                                        "r", "p", "CN", "<null>", "city", "line",
                                                        "zip")))));
    }

    @Test
    void quoteExposesActualListSeparatelyFromDiscountedDeal() {
        var old = catalog.skuVersions.get("v");
        var d = old.draft();
        var next =
                new SkuDraft(
                        d.productNo(),
                        d.skuNo(),
                        d.skuVersionNo(),
                        d.title(),
                        new Money("CNY", 151),
                        d.price(),
                        d.allowedBusinessTypes(),
                        d.refundPolicySnapshot(),
                        d.components(),
                        d.maxPerOrder(),
                        d.expectedVersion(),
                        d.reason());
        catalog.skuVersions.put(
                "v",
                new CatalogState.SkuVersion(
                        next, old.revision(), old.status(), old.version(), old.publishedAt()));
        var q = quote("v");
        assertEquals(101, q.total().minor());
        assertEquals(151, q.components().getFirst().listAmount().minor());
        assertEquals(101, q.components().getFirst().dealAmount().minor());
    }

    @Test
    void authoritativeStoreEntityChangeInvalidatesStaleAccountAndQuote() {
        var q = quote("v");
        storeMerchant = "new-merchant";
        error("VERSION_CONFLICT", () -> facade.placeOrder(buyer, place(q, "changed-entity")));
    }

    @Test
    void typeProductAndPublicationChangesRejectUnplacedQuotes() {
        var q = quote("v");
        var type = catalog.types.get("RETAIL");
        var td = type.draft();
        catalog.types.put(
                "RETAIL",
                new CatalogState.Type(
                        new BusinessTypeDraft(
                                td.typeCode(), td.name(), td.sortOrder(), false, 0, ""),
                        type.catalogVersion(),
                        type.version() + 1));
        error("NOT_SELLABLE", () -> quote("v"));
        error("NOT_SELLABLE", () -> facade.placeOrder(buyer, place(q, "type-stop")));
        catalog.types.put(
                "RETAIL", new CatalogState.Type(td, type.catalogVersion() + 1, type.version()));
        error("VERSION_CONFLICT", () -> facade.placeOrder(buyer, place(q, "type-publish")));
        catalog.types.put("RETAIL", type);
        var product = catalog.products.get("productv");
        catalog.products.put(
                "productv",
                new CatalogState.Product(product.draft(), "OFF_SALE", product.version() + 1));
        error("NOT_SELLABLE", () -> facade.placeOrder(buyer, place(q, "product-stop")));
        catalog.products.put("productv", product);
        var sku = catalog.skus.get("v");
        catalog.skus.put(
                "v",
                new CatalogState.Sku(
                        sku.skuNo(),
                        sku.productNo(),
                        sku.title(),
                        sku.maxPerOrder(),
                        "new-version",
                        "ON_SALE",
                        sku.version() + 1));
        error("VERSION_CONFLICT", () -> facade.placeOrder(buyer, place(q, "new-publication")));
        catalog.skus.put("v", sku);
        catalog.products.put(
                "productv",
                new CatalogState.Product(
                        product.draft(), product.saleStatus(), product.version() + 1));
        error("VERSION_CONFLICT", () -> facade.placeOrder(buyer, place(q, "product-version")));
        assertTrue(orders.isEmpty());
    }

    @Test
    void confirmedClaimWithoutOrderFailsConsistencyInsteadOfCreatingAnotherOrder() {
        var q = quote("v");
        forceDuplicateClaim = true;
        error("ORDER_CLAIM_INCOMPLETE", () -> facade.placeOrder(buyer, place(q, "incomplete")));
        assertTrue(orders.isEmpty());
    }

    @Test
    void duplicateAdapterResultStillChecksWinningOrderRequestHash() {
        var q = quote("v");
        facade.placeOrder(buyer, place(q, "adapter-key"));
        var another = quote("hw");
        forceDuplicateClaim = true;
        error(
                "IDEMPOTENCY_CONFLICT",
                () ->
                        facade.placeOrder(
                                buyer,
                                new PlaceOrderCommand(
                                        another.quoteNo(), "adapter-key", Optional.empty())));
    }

    void oneCycle(String version) {
        var v = catalog.skuVersions.get(version);
        var d = v.draft();
        var c = d.components().getFirst();
        var one =
                new SkuComponent(
                        c.componentNo(),
                        c.kind(),
                        c.planVersionNo(),
                        c.entitlementCode(),
                        c.hardwareSkuNo(),
                        c.periodDays(),
                        1,
                        c.allocationWeight(),
                        c.gift(),
                        c.activationPolicy());
        var draft =
                new SkuDraft(
                        d.productNo(),
                        d.skuNo(),
                        d.skuVersionNo(),
                        d.title(),
                        d.listPrice(),
                        d.price(),
                        d.allowedBusinessTypes(),
                        d.refundPolicySnapshot(),
                        List.of(one),
                        d.maxPerOrder(),
                        d.expectedVersion(),
                        d.reason());
        catalog.skuVersions.put(
                version,
                new CatalogState.SkuVersion(
                        draft, v.revision(), v.status(), v.version(), v.publishedAt()));
    }

    @Test
    void upgradeBindsOneTargetAndRejectsAmbiguousSkuOrWrongTier() {
        sku("upgrade", ComponentKind.PLAN, "chain", PlanTier.CHAIN, 69900);
        oneCycle("upgrade");
        available = 0;
        conversions =
                List.of(
                        new ConversionSource(
                                "old",
                                1,
                                "pro",
                                PlanTier.PRO,
                                "RETAIL",
                                36500,
                                365L * 86400000,
                                200L * 86400000,
                                20000,
                                "old-pay",
                                36500,
                                0));
        var quote =
                facade.quoteUpgrade(buyer, new UpgradeCommand(store, "chainv", "up", entitlement));
        assertEquals(49900, quote.payable().minor());
        assertEquals(20000, quote.credit().minor());
        sku("other", ComponentKind.PLAN, "chain", PlanTier.CHAIN, 70000);
        oneCycle("other");
        assertEquals(
                "AMBIGUOUS_UPGRADE_TARGET",
                assertThrows(
                                MallException.class,
                                () ->
                                        facade.quoteUpgrade(
                                                buyer,
                                                new UpgradeCommand(
                                                        store, "chainv", "up", entitlement)))
                        .code());
        assertEquals(
                49900,
                facade.quoteUpgrade(
                                buyer,
                                new UpgradeCommand(store, "chainv", "up", entitlement, "upgrade"))
                        .payable()
                        .minor());
        assertThrows(
                MallException.class,
                () ->
                        facade.quoteUpgrade(
                                buyer,
                                new UpgradeCommand(store, "pv", "wrong", entitlement, "upgrade")));
        oneCycle("v");
        assertThrows(
                MallException.class,
                () ->
                        facade.quoteUpgrade(
                                buyer,
                                new UpgradeCommand(store, "pv", "same-tier", entitlement, "v")));
    }

    @Test
    void upgradesRejectMultiplePaidCyclesAndHardwareCannotUsePlanBalance() {
        sku("upgrade", ComponentKind.PLAN, "chain", PlanTier.CHAIN, 69900);
        available = 80000;
        conversions =
                List.of(
                        new ConversionSource(
                                "gift",
                                1,
                                "pro",
                                PlanTier.PRO,
                                "RETAIL",
                                0,
                                100,
                                100,
                                0,
                                "gift",
                                0,
                                0));
        assertThrows(
                MallException.class,
                () ->
                        facade.quoteUpgrade(
                                buyer,
                                new UpgradeCommand(store, "chainv", "up", entitlement, "upgrade")));
        oneCycle("upgrade");
        var q =
                facade.quoteUpgrade(
                        buyer, new UpgradeCommand(store, "chainv", "up", entitlement, "upgrade"));
        assertEquals(69900, q.credit().minor());
        assertEquals(10100, available - quotes.get(q.quoteNo()).historicalCredit());
        var hardware =
                facade.quote(
                        buyer, new QuoteCommand(store, List.of(new QuoteLine("hw", 1)), "CNY"));
        assertEquals(0, hardware.credit().minor());
        assertEquals(80, hardware.payable().minor());
    }

    @Test
    void futureCyclesRetainFullValueAndMixedSkuOnlySpendsCreditOnItsOnePaidPlan() {
        sku("upgrade", ComponentKind.PLAN, "chain", PlanTier.CHAIN, 69900);
        oneCycle("upgrade");
        available = 0;
        conversions =
                List.of(
                        new ConversionSource(
                                "current",
                                1,
                                "pro",
                                PlanTier.PRO,
                                "RETAIL",
                                36500,
                                365L * 86400000,
                                200L * 86400000,
                                20000,
                                "old",
                                36500,
                                0),
                        new ConversionSource(
                                "future",
                                1,
                                "pro",
                                PlanTier.PRO,
                                "RETAIL",
                                36500,
                                365L * 86400000,
                                365L * 86400000,
                                36500,
                                "renew",
                                36500,
                                0),
                        new ConversionSource(
                                "gift",
                                1,
                                "pro",
                                PlanTier.PRO,
                                "RETAIL",
                                0,
                                30L * 86400000,
                                30L * 86400000,
                                0,
                                "gift",
                                0,
                                0));
        var q =
                facade.quoteUpgrade(
                        buyer,
                        new UpgradeCommand(store, "chainv", "future", entitlement, "upgrade"));
        assertEquals(56500, q.credit().minor());
        assertEquals(13400, q.payable().minor());
        var version = catalog.skuVersions.get("upgrade");
        var draft = version.draft();
        var mixed =
                new SkuDraft(
                        draft.productNo(),
                        draft.skuNo(),
                        draft.skuVersionNo(),
                        draft.title(),
                        draft.listPrice(),
                        draft.price(),
                        draft.allowedBusinessTypes(),
                        draft.refundPolicySnapshot(),
                        List.of(
                                draft.components().getFirst(),
                                new SkuComponent(
                                        "hardware",
                                        ComponentKind.HARDWARE,
                                        null,
                                        null,
                                        "device",
                                        null,
                                        1,
                                        1,
                                        false,
                                        "ON_FULFILLMENT"),
                                new SkuComponent(
                                        "pack",
                                        ComponentKind.END_PACKAGE,
                                        null,
                                        "POS",
                                        null,
                                        30,
                                        1,
                                        1,
                                        false,
                                        "ON_FULFILLMENT")),
                        draft.maxPerOrder(),
                        draft.expectedVersion(),
                        draft.reason());
        catalog.skuVersions.put(
                "upgrade",
                new CatalogState.SkuVersion(
                        mixed,
                        version.revision(),
                        version.status(),
                        version.version(),
                        version.publishedAt()));
        var bundle =
                facade.quoteUpgrade(
                        buyer,
                        new UpgradeCommand(store, "chainv", "mixed", entitlement, "upgrade"));
        assertEquals(23300, bundle.credit().minor());
        assertEquals(46600, bundle.payable().minor());
        assertTrue(
                bundle.components().stream()
                        .filter(c -> c.kind() != ComponentKind.PLAN)
                        .allMatch(c -> c.creditAmount().minor() == 0));
    }
}
