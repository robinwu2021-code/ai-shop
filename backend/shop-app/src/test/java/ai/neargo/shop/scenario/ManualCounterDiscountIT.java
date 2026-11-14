package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.invbridge.port.OriginalStockDispositionPort;
import ai.neargo.shop.marketing.campaign.CounterCampaignFence;
import ai.neargo.shop.marketing.port.CampaignPortImpl;
import ai.neargo.shop.portal.port.CampaignPortRouter;
import ai.neargo.shop.product.port.GoodsQueryPortImpl;
import ai.neargo.shop.promotion.service.ActivityPricingService;
import ai.neargo.shop.promotion.service.impl.ActivityPricingServiceImpl;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.MemberQueryPort;
import tools.jackson.databind.node.ObjectNode;
import ai.neargo.shop.spi.product.StockDispositionPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.settle.SettlePort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutLineReq;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutScope;
import ai.neargo.shop.spi.trade.CheckoutPort.OperationRef;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.spi.trade.CheckoutPort.SourceLookup;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ConfirmManualReceipt;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterCheckoutQuote;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.CounterConfirmation;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.ManualMethod;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PlaceCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort.PrepareCounterReq;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.trade.order.service.create.CheckoutQuoteGuard;
import ai.neargo.shop.trade.order.service.create.CounterEconomicCodec;
import ai.neargo.shop.trade.service.CheckoutService;
import ai.neargo.shop.trade.service.ManualServiceAdmissionService;
import ai.neargo.shop.trade.service.OrderService;
import ai.neargo.shop.trade.service.impl.CheckoutServiceImpl;
import ai.neargo.shop.trade.service.impl.ManualServiceAdmissionServiceImpl;
import ai.neargo.shop.trade.service.impl.OrderServiceImpl;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Finite C02 native cases: actual current owners, canonical SQL membership and original stock/cash. */
class ManualCounterDiscountIT extends CheckoutFlowFixture {
    @Autowired ManualServiceAdmissionPort manual;
    @Autowired GuestRegistrationPort guests;
    @Autowired CampaignPort campaigns;
    @Autowired CampaignPortImpl legacy;
    @Autowired ActivityPricingService promotionOwner;
    @Autowired CheckoutService checkoutOwner;
    @Autowired OrderService orderOwner;
    @Autowired ManualServiceAdmissionService admissionOwner;
    @Autowired GoodsQueryPortImpl products;
    @MockitoSpyBean CounterCampaignFence fence;
    @Autowired ObjectMapper json;
    @MockitoSpyBean CheckoutQuoteGuard finalGuard;
    @MockitoSpyBean StockDispositionPort originalStock;
    @MockitoSpyBean MemberQueryPort memberQuery;
    @MockitoSpyBean SettlePort financial;
    @MockitoSpyBean PaymentOperationPort paymentOperations;
    ManualCounterDiscountFixture actual;

    @BeforeEach
    void actualCounterOwners() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("MySQL");
        }
        origin(campaigns, CampaignPortRouter.class);
        origin(legacy, CampaignPortImpl.class);
        origin(promotionOwner, ActivityPricingServiceImpl.class);
        origin(checkoutOwner, CheckoutServiceImpl.class);
        origin(orderOwner, OrderServiceImpl.class);
        origin(admissionOwner, ManualServiceAdmissionServiceImpl.class);
        origin(products, GoodsQueryPortImpl.class);
        origin(originalStock, OriginalStockDispositionPort.class);
        fence.validateSchema();
        actual = new ManualCounterDiscountFixture(this, manual, guests);
        doThrow(new AssertionError("counter cash must not open financial payment"))
                .when(financial).initPayment(any());
        doThrow(new AssertionError("counter cash must not open financial payment"))
                .when(financial).openPayment(any());
        doThrow(new AssertionError("counter cash must not prepare payment"))
                .when(paymentOperations).prepare(any());
        doThrow(new AssertionError("counter cash must not initiate payment"))
                .when(paymentOperations).initiate(anyString());
    }

    @Override
    protected <T> T asMerchant(Supplier<T> action) {
        return ManualServiceAdmissionIT.currentCashier(tokens, actor, action);
    }

    private static void origin(Object provider, Class<?> expected) {
        var type = AopUtils.getTargetClass(provider);
        assertThat(type).isEqualTo(expected);
        assertThat(type.getProtectionDomain().getCodeSource().getLocation().getPath())
                .contains("/beauty-data/ai-shop-codex/").contains("/target/classes/");
    }

    @Test
    void merchantFullCutSealsSameSharesForUserAndGuest() {
        String rule = actual.campaign("cash", 10000, 1000);
        int index = 0;
        for (var customer : List.of(actual.user(), actual.guest())) {
            jdbc.update("UPDATE mkt_campaign SET status='RUNNING',end_at=?,version=version+1 WHERE campaign_no=?",
                    System.currentTimeMillis() + 3600000, rule);
            var quote = actual.prepare("cash-" + index++, customer, Map.of());
            assertThat(quote.quote().goodsMinor()).isEqualTo(10000);
            assertThat(quote.quote().discountMinor()).isEqualTo(1000);
            assertThat(quote.quote().dueMinor()).isEqualTo(9000);
            var created = actual.place(actual.accept(quote));
            actual.assertCreated(quote, created);
            var raw = stored(quote);
            var money = new CounterEconomicCodec(json).readQuote(raw);
            assertThat(money.merchantMinor()).isEqualTo(1000);
            assertThat(money.platformMinor()).isZero();
            assertThat(money.activity().applied().getFirst().name()).isNull();
            jdbc.update("UPDATE mkt_campaign SET status='PAUSED',end_at=0,version=version+1 WHERE campaign_no=?", rule);
            actual.assertAdmitted(quote, created);
            jdbc.update("UPDATE mkt_campaign SET deleted=1 WHERE campaign_no=?", rule);
            assertThat(asMerchant(() -> manual.source(scope,
                    new SourceLookup(quote.quote().operation(), created.subOrderNo()))).lines()).hasSize(1);
            jdbc.update("UPDATE mkt_campaign SET deleted=0 WHERE campaign_no=?", rule);
        }
    }

    @Test
    void explicitNonBestNoneOmissionAndChoiceKeys() {
        String best = actual.campaign("best", 0, 1000);
        String lesser = actual.campaign("less", 0, 500);
        var auto = actual.prepare("auto", actual.user(), Map.of());
        var explicit = actual.prepare("explicit", actual.user(), Map.of(entity, lesser, store, lesser));
        var none = actual.prepare("none", actual.guest(), Map.of(store, "NONE"));
        assertThat(auto.quote().discountMinor()).isEqualTo(1000);
        assertThat(explicit.quote().discountMinor()).isEqualTo(500);
        assertThat(none.quote().discountMinor()).isZero();
        for (var quote : List.of(auto, explicit, none)) {
            assertThat(quote.quote().promotions()).extracting("promotionNo").containsExactly(best, lesser, "NONE");
        }
        actual.assertCreated(explicit, actual.place(actual.accept(explicit)));
        actual.assertCreated(none, actual.place(actual.accept(none)));
        rejected("foreign-none", Map.of("FOREIGN", "NONE"), ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
        rejected("case-key", Map.of(entity.toLowerCase(), "NONE"), ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
        rejected("conflict-alias", Map.of(entity, best, store, lesser), ErrorCode.BAD_REQUEST);
        rejected("missing-rule", Map.of(store, "missing"), ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
        jdbc.update("UPDATE mkt_campaign SET status='PAUSED' WHERE campaign_no IN (?,?)", best, lesser);
        var empty = actual.prepare("empty", actual.user(), Map.of());
        assertThat(empty.quote().promotions()).extracting("promotionNo").containsExactly("NONE");
    }

    @Test
    void counterOptionsRemainFrozenAcrossReadAndOriginalReplay() {
        String selected = actual.campaign("selected", 0, 500);
        String competitor = actual.campaign("competitor", 0, 1000);
        var request = actual.request("frozen", actual.user(), Map.of(store, selected));
        var original = actual.prepare(request);
        String pricing = stored(original);
        jdbc.update("UPDATE mkt_campaign SET name='changed competitor',status='PAUSED',version=version+1"
                + " WHERE campaign_no=?", competitor);
        doThrow(new AssertionError("frozen read/replay reacquired a live owner fence"))
                .when(fence).lock(anyString(), any());
        assertThat(asMerchant(() -> manual.readCounterQuote(scope, request.operation())).quote().promotions())
                .isEqualTo(original.quote().promotions());
        assertThat(actual.prepare(request).quote().promotions()).isEqualTo(original.quote().promotions());
        assertThat(actual.prepare(request).quote().quoteHash()).isEqualTo(original.quote().quoteHash());
        assertThat(stored(original)).isEqualTo(pricing);
        reset(fence);
        actual.assertCreated(original, actual.place(actual.accept(original)));
        var fresh = actual.prepare("fresh-options", actual.user(), Map.of(store, selected));
        assertThat(fresh.quote().promotions()).extracting("promotionNo").containsExactly(selected, "NONE");
    }

    @Test
    void unsupportedOffersNeverEnterCounterOptions() {
        String supported = actual.campaign("supported", 0, 500);
        actual.promotion("account", "CUT", 0, 2000);
        String scoped = actual.campaign("scoped", 0, 3000);
        jdbc.update("UPDATE mkt_campaign SET goods_nos=? WHERE campaign_no=?", "[\"" + goods + "\"]", scoped);
        for (var choices : List.of(Map.of(store, "NONE"), Map.of(entity, supported))) {
            var quote = actual.prepare("options-" + choices.values().iterator().next(), actual.user(), choices);
            assertThat(quote.quote().promotions()).extracting("promotionNo").containsExactly(supported, "NONE");
        }
        rejected("unsupported-auto", Map.of(), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        actual.mandatory("flash", "FLASH");
        rejected("mandatory-none", Map.of(store, "NONE"), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        rejected("mandatory-explicit", Map.of(store, supported), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
    }

    @Test
    void accountCouponPointsAndPromotionChoicesAreRejected() {
        doThrow(new AssertionError("counter must not query account audience"))
                .when(memberQuery).judge(anyString(), any());
        doThrow(new AssertionError("counter must not query account audience"))
                .when(memberQuery).matchesRule(anyString(), any(), anyString());
        String pmt = actual.promotion("account-only", "CUT", 0, 1000);
        jdbc.update("INSERT INTO pmt_activity_audience(activity_no,audience_type,audience_value,tenant_no,"
                + "created_at,updated_at) VALUES (?,'MEMBER','restricted','MAIN',NOW(),NOW())", pmt);
        rejected("account-auto", Map.of(), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        rejected("account-explicit", Map.of(store, pmt), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        var base = actual.request("coupon", actual.user(), Map.of(store, "NONE"));
        var coupon = new PrepareCounterReq(base.operation(), base.requestKey(), base.customer(), base.scopeRevision(),
                base.scopeHash(), base.lines(), "excluded-coupon", base.activityChoices(), null);
        error(() -> actual.prepare(coupon), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        var withPoints = (ObjectNode) json.valueToTree(base);
        withPoints.put("usePoints", 100);
        assertThatThrownBy(() -> json.treeToValue(withPoints, PrepareCounterReq.class))
                .isInstanceOf(RuntimeException.class);
        assertThat(actual.prepare("account-none", actual.guest(), Map.of(store, "NONE"))
                .quote().discountMinor()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM usr_account WHERE user_no=?", Long.class, actor)).isZero();
    }

    @Test
    void unsupportedAutomaticOfferIsNotSilentlyDropped() {
        String pmt = actual.promotion("objective", "CUT", 20000, 1000);
        assertThat(actual.prepare("below", actual.user(), Map.of()).quote().discountMinor()).isZero();
        jdbc.update("UPDATE pmt_activity SET trigger_amount_minor=10000 WHERE activity_no=?", pmt);
        rejected("hits", Map.of(), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        assertThat(actual.prepare("optout", actual.user(), Map.of(store, "NONE")).quote().discountMinor()).isZero();
        jdbc.update("UPDATE pmt_activity SET start_at=?,schedule_type='ONE_OFF',end_at=? WHERE activity_no=?",
                System.currentTimeMillis() + 3600000, System.currentTimeMillis() + 7200000, pmt);
        assertThat(actual.prepare("future", actual.user(), Map.of()).quote().discountMinor()).isZero();
        jdbc.update("UPDATE pmt_activity SET start_at=0,end_at=9999999999999 WHERE activity_no=?", pmt);
        jdbc.update("INSERT INTO pmt_activity_goods(activity_no,entity_no,scope_type,ref_no,tenant_no,created_at,"
                + "updated_at) VALUES (?,?,'GOODS','disjoint','MAIN',NOW(),NOW())", pmt, entity);
        assertThat(actual.prepare("disjoint", actual.user(), Map.of()).quote().discountMinor()).isZero();
        actual.mandatory("gift", "BUY_GIFT");
        rejected("mandatory-gift", Map.of(store, "NONE"), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
    }

    @Test
    void nonemptyGoodsScopeAndMalformedRuleFailClosed() {
        String scoped = actual.campaign("scope", 10000, 1000);
        jdbc.update("UPDATE mkt_campaign SET goods_nos=? WHERE campaign_no=?", "[\"other\"]", scoped);
        assertThat(actual.prepare("disjoint-legacy", actual.user(), Map.of()).quote().discountMinor()).isZero();
        jdbc.update("UPDATE mkt_campaign SET goods_nos=? WHERE campaign_no=?", "[\"" + goods + "\"]", scoped);
        rejected("scope-hit", Map.of(), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        rejected("scope-explicit", Map.of(store, scoped), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        jdbc.update("UPDATE mkt_campaign SET goods_nos=? WHERE campaign_no=?", "{}", scoped);
        rejected("malformed", Map.of(), ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
    }

    @Test
    void changedMerchantDiscountRequiresNewAcceptance() {
        String rule = actual.campaign("price-change", 0, 1000);
        var quote = actual.prepare("price-change", actual.user(), Map.of());
        var accepted = actual.accept(quote);
        jdbc.update("UPDATE mkt_campaign SET discount_minor=1500,version=version+1 WHERE campaign_no=?", rule);
        stale(quote, accepted);
        var base = actual.prepare("base-change", actual.user(), Map.of());
        var baseAccepted = actual.accept(base);
        jdbc.update("UPDATE prd_sku SET price=11000 WHERE sku_no=?", sku);
        stale(base, baseAccepted);
    }

    @Test
    void sameAmountRuleVersionThresholdAndWindowChangesAreStale() {
        String rule = actual.campaign("same-money", 0, 1000);
        int n = 0;
        for (String assignment : List.of("version=version+1", "threshold_minor=threshold_minor+1",
                "end_at=end_at+1000", "start_at=start_at-1000")) {
            var quote = actual.prepare("same-money-" + n++, actual.user(), Map.of());
            var accepted = actual.accept(quote);
            jdbc.update("UPDATE mkt_campaign SET " + assignment + " WHERE campaign_no=?", rule);
            stale(quote, accepted);
        }
    }

    @Test
    void discountCannotMakeManualOrderFree() {
        actual.campaign("lesser-payable", 0, 500);
        String free = actual.campaign("free", 0, 20000);
        rejected("free-auto", Map.of(), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        rejected("free-explicit", Map.of(store, free), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        assertThat(actual.prepare("free-none", actual.user(), Map.of(store, "NONE")).quote().promotions())
                .extracting("promotionNo").doesNotContain(free);
    }

    @Test
    void noneAndEmptyAutoAreVerifiedUnderOwnerFence() throws Exception {
        fencedRace("empty-auto", Map.of(), () -> actual.campaign("after-fence", 0, 1000));
        jdbc.update("UPDATE mkt_campaign SET status='PAUSED' WHERE entity_no=?", entity);
        fencedRace("empty-auto-unsupported", Map.of(), () -> actual.promotion("after-empty", "CUT", 0, 1000));
        rejected("new-auto-unsupported", Map.of(), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        jdbc.update("UPDATE pmt_activity SET status='PAUSED' WHERE entity_no=?", entity);
        fencedRace("none-discretionary", Map.of(store, "NONE"),
                () -> actual.campaign("after-none", 0, 1500));
        fencedRace("none-gift-after", Map.of(store, "NONE"), () -> {
            actual.mandatory("gift-after-fence", "BUY_GIFT");
            return "gift-after-fence";
        });
        jdbc.update("UPDATE mkt_campaign SET status='PAUSED' WHERE entity_no=?", entity);
        var quote = actual.prepare("none-before-flash", actual.user(), Map.of(store, "NONE"));
        var accepted = actual.accept(quote);
        actual.mandatory("before-final-flash", "FLASH");
        stale(quote, accepted);
    }

    @Test
    void platformEnrollmentAndPublishedVersionRaceIsFenced() throws Exception {
        String platform = actual.promotion("platform", "CUT", 0, 1000, "PLATFORM", "PLATFORM");
        String enrollment = "enroll-" + sku;
        jdbc.update("INSERT INTO pmt_enrollment(enrollment_no,activity_no,entity_no,quota,platform_max_minor,"
                + "merchant_max_minor,status,tenant_no,created_at,updated_at)"
                + " VALUES (?,?,?,1,1000,0,'SUBMITTED','MAIN',NOW(),NOW())", enrollment, platform, entity);
        fencedRace("platform-before-approval", Map.of(), () -> {
            jdbc.update("UPDATE pmt_enrollment SET status='APPROVED' WHERE enrollment_no=?", enrollment);
            jdbc.update("INSERT INTO pmt_enrollment_goods(enrollment_no,goods_no,tenant_no,"
                    + "created_at,updated_at) VALUES (?,?,'MAIN',NOW(),NOW())", enrollment, goods);
            return platform;
        });
        rejected("approved-platform", Map.of(), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        jdbc.update("UPDATE pmt_enrollment SET status='SUBMITTED' WHERE enrollment_no=?", enrollment);
        String published = published("published", 20000);
        fencedRace("published-after-fence", Map.of(), () -> {
            jdbc.update("UPDATE pmt_activity_version SET rule_snapshot=REPLACE(rule_snapshot,'20000','10000')"
                    + " WHERE activity_no=?", published);
            return published;
        });
        rejected("new-published-hit", Map.of(), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
        jdbc.update("UPDATE pmt_activity_version SET rule_snapshot=REPLACE(rule_snapshot,'10000','20000')"
                + " WHERE activity_no=?", published);
        var quote = actual.prepare("before-version", actual.user(), Map.of());
        var accepted = actual.accept(quote);
        jdbc.update("UPDATE pmt_activity_version SET rule_snapshot=REPLACE(rule_snapshot,'20000','10000')"
                + " WHERE activity_no=?", published);
        stale(quote, accepted);
    }

    @Test
    void oldPendingCounterKeepsZeroEconomicsAfterPromotionRollout() throws Exception {
        for (String kind : List.of("USER", "GUEST")) {
            String input = System.getenv("TASK68_OLD_PENDING_" + kind);
            if (input == null) {
                throw new IllegalStateException("phase B requires genuine old " + kind + " pending producer output");
            }
            var evidence = json.readTree(Files.readString(Path.of(input)));
            assertThat(evidence.get("baselineCommit").asString())
                    .isEqualTo("2c0708f752d747d88a9eda7478326a964d1984fa");
            var oldScope = json.treeToValue(evidence.get("scope"), CheckoutScope.class);
            var quote = json.treeToValue(evidence.get("quote"), CounterCheckoutQuote.class);
            var place = json.treeToValue(evidence.get("place"), PlaceCounterReq.class);
            var intent = evidence.get("intent");
            String order = intent.get("pay_no").asString();
            assertThat(new CounterEconomicCodec(json).readQuote(evidence.get("pricingJson").asString())
                    .counterActivitySeal()).isNull();
            assertThat(jdbc.queryForObject("SELECT counter_plan_json FROM ord_create_intent WHERE pay_no=?",
                    String.class, order)).isEqualTo(intent.get("counter_plan_json").asString());
            jdbc.update("INSERT INTO mkt_campaign(campaign_no,entity_no,store_no,type,name,status,threshold_minor,"
                    + "discount_minor,start_at,end_at,version,tenant_no,created_at,updated_at)"
                    + " VALUES (?,?,?,'FULL_CUT','rollout','RUNNING',0,1000,0,9999999999999,0,'MAIN',NOW(),NOW())",
                    "rollout-" + order, oldScope.entityNo(), oldScope.storeNo());
            for (String type : List.of("FLASH", "BUY_GIFT")) {
                String goodsNo = quote.quote().lines().getFirst().goodsNo();
                jdbc.update("INSERT INTO mkt_campaign(campaign_no,entity_no,store_no,type,name,status,goods_nos,"
                        + "flash_price_minor,buy_n,gift_m,start_at,end_at,version,tenant_no,created_at,updated_at)"
                        + " VALUES (?,?,?,?,'rollout','RUNNING',?,8000,1,1,0,9999999999999,0,'MAIN',NOW(),NOW())",
                        type + '-' + order, oldScope.entityNo(), oldScope.storeNo(), type, "[\"" + goodsNo + "\"]");
            }
            jdbc.update("INSERT INTO pmt_activity(activity_no,entity_no,store_no,owner,name,trigger_type,"
                    + "benefit_type,benefit_amount_minor,schedule_type,status,tenant_no,created_at,updated_at)"
                    + " VALUES (?,?,?,'MERCHANT','rollout','NONE','CUT',1000,'ALWAYS_ON','RUNNING','MAIN',NOW(),NOW())",
                    "pmt-" + order, oldScope.entityNo(), oldScope.storeNo());
            var created = resumeOld(oldScope, place);
            assertThat(created.phase()).isEqualTo("CREATED");
            assertThat(created.orderNo()).isEqualTo(order);
            verify(originalStock, never()).reserveOriginal(argThat(r -> r.original().orderNo().equals(order)));
            var originalReservation = json.treeToValue(evidence.get("reservationObservation"),
                    StockDispositionPort.ReservationView.class);
            assertThat(originalReservation.original().orderNo()).isEqualTo(order);
            assertThat(originalReservation.reservationOperationNo())
                    .isEqualTo(intent.get("reservation_operation_no").asString());
            assertThat(created.subOrderNo()).isEqualTo(intent.get("sub_order_no").asString());
            assertThat(jdbc.queryForObject("SELECT plan_hash FROM ord_create_intent WHERE pay_no=?",
                    String.class, order))
                    .isEqualTo(intent.get("plan_hash").asString());
            assertThat(jdbc.queryForObject("SELECT pay_amount FROM ord_order WHERE order_no=?", Long.class, order))
                    .isEqualTo(quote.quote().dueMinor());
            assertThat(ManualServiceAdmissionIT.currentCashier(tokens, oldScope.actorNo(),
                    () -> manual.placeCounter(oldScope, place)).orderNo()).isEqualTo(order);
            var oldPrepare = json.treeToValue(evidence.get("prepare"), PrepareCounterReq.class);
            var newPrepare = new PrepareCounterReq(new OperationRef(
                    "BEAUTY", "new-" + order), "new-" + order, oldPrepare.customer(), oldPrepare.scopeRevision(),
                    oldPrepare.scopeHash(), oldPrepare.lines(), null, Map.of(), null);
            error(() -> ManualServiceAdmissionIT.currentCashier(tokens, oldScope.actorNo(),
                    () -> manual.prepareCounter(oldScope, newPrepare)), ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION);
            var receipt = new ConfirmManualReceipt(quote.quote().operation(), "old-cash-" + order,
                    jdbc.queryForObject("SELECT version FROM ord_order WHERE order_no=?", Long.class, order),
                    ManualMethod.CASH, quote.quote().dueMinor(), "CNY",
                    Instant.now().truncatedTo(ChronoUnit.MILLIS), null);
            testClock.at = Instant.now();
            assertThat(ManualServiceAdmissionIT.currentCashier(tokens, oldScope.actorNo(),
                    () -> manual.confirm(oldScope, receipt)).admissionState()).isEqualTo("ADMITTED");
            jdbc.update("UPDATE ord_sub_order SET discount_merchant=1 WHERE sub_order_no=?", created.subOrderNo());
            assertThat(ManualServiceAdmissionIT.currentCashier(tokens, oldScope.actorNo(),
                    () -> manual.source(oldScope, new SourceLookup(quote.quote().operation(), created.subOrderNo())))
                    .lines()).isEmpty();
        }
        for (String control : List.of("BASE_CHANGE", "CATALOG_CHANGE")) {
            var evidence = oldEvidence(control);
            var oldScope = json.treeToValue(evidence.get("scope"), CheckoutScope.class);
            var quote = json.treeToValue(evidence.get("quote"), CounterCheckoutQuote.class);
            var request = json.treeToValue(evidence.get("place"), PlaceCounterReq.class);
            if ("BASE_CHANGE".equals(control)) {
                jdbc.update("UPDATE prd_sku SET price=price+100 WHERE sku_no=?",
                        quote.quote().lines().getFirst().skuNo());
            } else {
                var catalog = catalogs.read(oldScope.entityNo(), oldScope.storeNo(), Instant.now());
                ManualServiceAdmissionIT.currentCashier(tokens, oldScope.actorNo(), () -> catalogs.publish(
                        oldScope.entityNo(), oldScope.storeNo(), oldScope.actorNo(),
                        new StoreSalesCatalogPort.PublishReq(catalog.headRevision(), OffsetDateTime.now(ZoneOffset.UTC),
                                catalog.definition().timezone(), catalog.definition().groups(),
                                catalog.definition().entries()), "old-catalog-change-" + oldScope.entityNo()));
            }
            var result = resumeOld(oldScope, request);
            assertThat(result.phase()).isEqualTo("REQUOTE_REQUIRED");
            assertThat(result.reasonCode()).isEqualTo("CHECKOUT_QUOTE_STALE");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE operation_no=?", Long.class,
                    quote.quote().operation().operationNo())).isZero();
        }
    }

    private JsonNode oldEvidence(String suffix) throws Exception {
        String file = System.getenv("TASK68_OLD_PENDING_" + suffix);
        if (file == null) {
            throw new IllegalStateException("phase B requires genuine old pending " + suffix + " control");
        }
        return json.readTree(Files.readString(Path.of(file)));
    }

    private CheckoutResult resumeOld(CheckoutScope originalScope, PlaceCounterReq request) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        CheckoutResult result;
        do {
            testClock.at = Instant.now();
            result = ManualServiceAdmissionIT.currentCashier(tokens, originalScope.actorNo(),
                    () -> manual.placeCounter(originalScope, request));
            if (!"PLACING".equals(result.phase())) {
                return result;
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("original retry timing did not resolve before the bounded real deadline");
    }

    @Test
    void consumedCounterRejectsAllocationPartyAndAuthorityTampering() {
        actual.campaign("tamper", 0, 1000);
        int i = 0;
        for (String assignment : List.of("discount_merchant=999", "discount_platform=1", "discount_amount=999")) {
            var quote = actual.prepare("tamper-child-" + i++, actual.user(), Map.of());
            var created = actual.place(actual.accept(quote));
            jdbc.update("UPDATE ord_sub_order SET " + assignment + " WHERE sub_order_no=?", created.subOrderNo());
            error(() -> asMerchant(() -> manual.confirm(scope, actual.receipt(quote, created))), ErrorCode.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_manual_service_admission WHERE order_no=?",
                    Long.class, created.orderNo())).isZero();
        }
        for (String target : List.of("parent", "line", "party", "consent", "namespace", "quote")) {
            var quote = actual.prepare("tamper-" + target, actual.user(), Map.of());
            var created = actual.place(actual.accept(quote));
            try {
                switch (target) {
                    case "parent" -> jdbc.update("UPDATE ord_order SET discount_amount=999 WHERE order_no=?",
                            created.orderNo());
                    case "line" -> jdbc.update("UPDATE ord_checkout_line SET goods_no='foreign-goods'"
                            + " WHERE operation_no=?", quote.quote().operation().operationNo());
                    case "party" -> jdbc.update("UPDATE ord_sub_order SET user_no='foreign-party'"
                            + " WHERE sub_order_no=?", created.subOrderNo());
                    case "consent" -> jdbc.update("UPDATE ord_checkout_confirmation SET status='AUTHORIZED'"
                            + " WHERE operation_no=?", quote.quote().operation().operationNo());
                    case "namespace" -> jdbc.update("UPDATE ord_checkout_confirmation SET authority_namespace_hash=?"
                            + " WHERE operation_no=?", "f".repeat(64), quote.quote().operation().operationNo());
                    case "quote" -> jdbc.update("UPDATE ord_checkout_quote SET quote_hash=? WHERE quote_no=?",
                            "f".repeat(64), quote.quote().quoteNo());
                    default -> throw new AssertionError(target);
                }
            } catch (DataAccessException protectedRow) {
                Throwable cause = protectedRow.getMostSpecificCause();
                assertThat(cause).isInstanceOf(SQLException.class);
                String state = ((SQLException) cause).getSQLState();
                assertThat(state.startsWith("23") || state.startsWith("45")).isTrue();
                continue;
            }
            error(() -> asMerchant(() -> manual.confirm(scope, actual.receipt(quote, created))), ErrorCode.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_manual_service_admission WHERE order_no=?",
                    Long.class, created.orderNo())).isZero();
        }
        var quote = actual.prepare("tamper-after-cash", actual.guest(), Map.of());
        var created = actual.place(actual.accept(quote));
        var receipt = actual.receipt(quote, created);
        asMerchant(() -> manual.confirm(scope, receipt));
        jdbc.update("UPDATE ord_item SET paid_share_minor=paid_share_minor+1 WHERE sub_order_no=?",
                created.subOrderNo());
        error(() -> asMerchant(() -> manual.confirm(scope, receipt)), ErrorCode.CONFLICT);
        var source = asMerchant(() -> manual.source(scope,
                new SourceLookup(quote.quote().operation(), created.subOrderNo())));
        assertThat(source.admissionState()).isEqualTo("UNKNOWN");
        assertThat(source.lines()).isEmpty();

        var first = actual.prepare(actual.twoLineRequest("balanced-first", actual.user()));
        var firstOrder = actual.place(actual.accept(first));
        var firstReceipt = actual.receipt(first, firstOrder);
        assertThat(first.quote().lines()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_item WHERE sub_order_no=?", Long.class,
                firstOrder.subOrderNo())).isEqualTo(2);
        var originalStatus = jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?",
                String.class, firstOrder.orderNo());
        var stockBefore = jdbc.queryForList("SELECT sku_no,stock,locked_stock FROM prd_sku WHERE sku_no IN (?,?)"
                + " ORDER BY sku_no", first.quote().lines().get(0).skuNo(), first.quote().lines().get(1).skuNo());
        balancePaidShares(firstOrder);
        error(() -> asMerchant(() -> manual.confirm(scope, firstReceipt)), ErrorCode.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_manual_service_admission WHERE order_no=?",
                Long.class, firstOrder.orderNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM ord_order WHERE order_no=?",
                String.class, firstOrder.orderNo())).isEqualTo(originalStatus);
        assertThat(jdbc.queryForList("SELECT sku_no,stock,locked_stock FROM prd_sku WHERE sku_no IN (?,?)"
                + " ORDER BY sku_no", first.quote().lines().get(0).skuNo(), first.quote().lines().get(1).skuNo()))
                .isEqualTo(stockBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_outbox WHERE aggregate_id=?"
                + " AND event_type='MANUAL_SERVICE_RECEIPT_RECORDED'", Long.class, firstOrder.orderNo())).isZero();

        var after = actual.prepare(actual.twoLineRequest("balanced-after", actual.guest()));
        var afterOrder = actual.place(actual.accept(after));
        var afterReceipt = actual.receipt(after, afterOrder);
        assertThat(asMerchant(() -> manual.confirm(scope, afterReceipt)).admissionState()).isEqualTo("ADMITTED");
        balancePaidShares(afterOrder);
        error(() -> asMerchant(() -> manual.confirm(scope, afterReceipt)), ErrorCode.CONFLICT);
        var afterSource = asMerchant(() -> manual.source(scope,
                new SourceLookup(after.quote().operation(), afterOrder.subOrderNo())));
        assertThat(afterSource.admissionState()).isEqualTo("UNKNOWN");
        assertThat(afterSource.lines()).isEmpty();
    }

    private void balancePaidShares(CheckoutResult order) {
        var items = jdbc.queryForList("SELECT id,paid_share_minor FROM ord_item WHERE sub_order_no=? ORDER BY id",
                order.subOrderNo());
        assertThat(items).hasSize(2);
        long before = items.stream().mapToLong(item -> ((Number) item.get("paid_share_minor")).longValue()).sum();
        assertThat(before).isEqualTo(jdbc.queryForObject("SELECT pay_amount FROM ord_sub_order WHERE sub_order_no=?",
                Long.class, order.subOrderNo()));
        assertThat(((Number) items.get(1).get("paid_share_minor")).longValue()).isPositive();
        jdbc.update("UPDATE ord_item SET paid_share_minor=paid_share_minor+1 WHERE id=?", items.get(0).get("id"));
        jdbc.update("UPDATE ord_item SET paid_share_minor=paid_share_minor-1 WHERE id=?", items.get(1).get("id"));
        assertThat(jdbc.queryForObject("SELECT SUM(paid_share_minor) FROM ord_item WHERE sub_order_no=?",
                Long.class, order.subOrderNo())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT pay_amount FROM ord_sub_order WHERE sub_order_no=?",
                Long.class, order.subOrderNo())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT pay_amount FROM ord_order WHERE order_no=?", Long.class,
                order.orderNo())).isEqualTo(before);
    }

    @Test
    void discountExceptionRequiresSealedCounterAuthority() {
        var m1 = asMerchant(() -> manual.prepare(scope, new PrepareCheckoutReq(new OperationRef(
                "BEAUTY", "m1-control-" + sku), "m1-control-" + sku, reference, 1, "a".repeat(64),
                List.of(new CheckoutLineReq("service-line", sku, 1)),
                "OFFLINE", null, Map.of(), null)));
        var m1Consent = asBuyer(() -> confirm(m1));
        var m1Order = asBuyer(() -> checkout.place(place(m1, m1Consent)));
        jdbc.update("UPDATE ord_sub_order SET discount_merchant=1 WHERE sub_order_no=?", m1Order.subOrderNo());
        var m1Receipt = new ConfirmManualReceipt(m1.operation(), "m1-cash-" + sku,
                jdbc.queryForObject("SELECT version FROM ord_order WHERE order_no=?", Long.class, m1Order.orderNo()),
                ManualMethod.CASH, m1.dueMinor(), "CNY", testClock.instant().truncatedTo(ChronoUnit.MILLIS), null);
        error(() -> asMerchant(() -> manual.confirm(scope, m1Receipt)), ErrorCode.CONFLICT);
        var none = actual.prepare("new-none", actual.user(), Map.of(store, "NONE"));
        var createdNone = actual.place(actual.accept(none));
        actual.assertAdmitted(none, createdNone);
        actual.campaign("authority", 0, 1000);
        var quote = actual.prepare("wrong-consent", actual.user(), Map.of());
        var created = actual.place(actual.accept(quote));
        jdbc.update("UPDATE ord_checkout_confirmation SET quote_hash=? WHERE operation_no=?",
                "b".repeat(64), quote.quote().operation().operationNo());
        error(() -> asMerchant(() -> manual.confirm(scope, actual.receipt(quote, created))), ErrorCode.CONFLICT);
        jdbc.update("UPDATE ord_checkout_confirmation SET quote_hash=? WHERE operation_no=?",
                quote.quote().quoteHash(), quote.quote().operation().operationNo());
        jdbc.update("UPDATE ord_order SET discount_amount=999 WHERE order_no=?", created.orderNo());
        error(() -> asMerchant(() -> manual.confirm(scope, actual.receipt(quote, created))), ErrorCode.CONFLICT);
        jdbc.update("UPDATE ord_order SET discount_amount=1000 WHERE order_no=?", created.orderNo());
        var rawQuote = (ObjectNode) json.readTree(stored(quote));
        ((ObjectNode) rawQuote.get("economic")).remove("counterActivitySeal");
        jdbc.update("UPDATE ord_checkout_quote SET pricing_json=? WHERE quote_no=?",
                json.writeValueAsString(rawQuote), quote.quote().quoteNo());
        error(() -> asMerchant(() -> manual.confirm(scope, actual.receipt(quote, created))), ErrorCode.CONFLICT);
        jdbc.update("UPDATE ord_checkout_quote SET pricing_json=? WHERE quote_no=?", stored(none), m1.quoteNo());
        error(() -> asMerchant(() -> manual.confirm(scope, m1Receipt)), ErrorCode.CONFLICT);
        actualFinancialAndAfterSaleRemainExcluded();
    }

    private void actualFinancialAndAfterSaleRemainExcluded() {
        jdbc.update("UPDATE mkt_campaign SET status='PAUSED' WHERE entity_no=?", entity);
        for (String target : List.of("after-sale", "financial")) {
            var quote = actual.prepare("excluded-" + target, actual.user(), Map.of(store, "NONE"));
            var created = actual.place(actual.accept(quote));
            if ("after-sale".equals(target)) {
                jdbc.update("UPDATE ord_sub_order SET open_after_sale_no='actual-open-case' WHERE sub_order_no=?",
                        created.subOrderNo());
                error(() -> asMerchant(() -> manual.confirm(scope, actual.receipt(quote, created))),
                        ErrorCode.CONFLICT);
            } else {
                jdbc.update("INSERT INTO ord_payment_resolution(pay_no,payment_no,user_no,amount_minor,currency,"
                        + "pay_channel,trade_no,reason,status,tenant_no,created_at,updated_at)"
                        + " VALUES (?,? ,?,10000,'CNY','TEST','actual-conflict','OWNER_CONFLICT','PENDING',"
                        + "'MAIN',NOW(),NOW())", created.orderNo(), "payment-" + created.orderNo(), buyer);
                error(() -> asMerchant(() -> manual.confirm(scope, actual.receipt(quote, created))),
                        ErrorCode.CHECKOUT_PAYMENT_CONFLICT);
            }
        }
    }

    @Test
    void counterStillRequiresOriginalInPersonAcceptance() {
        actual.campaign("acceptance", 0, 1000);
        var quote = actual.prepare("no-accept", actual.user(), Map.of());
        error(() -> asMerchant(() -> manual.placeCounter(scope,
                new PlaceCounterReq(quote.quote().operation(), "invented-confirmation", "invented-place"))),
                ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        var accepted = actual.accept(quote);
        error(() -> asMerchant(() -> manual.placeCounter(scope,
                new PlaceCounterReq(accepted.operation(), "other-confirmation", "other-place"))),
                ErrorCode.CHECKOUT_CONFIRMATION_REQUIRED);
        actual.assertCreated(quote, actual.place(accepted));
    }

    @Test
    void sqlOwnerAliasesKeepCanonicalMoneyAndRawRuleFacts() {
        String foreignEntity = actual.campaign("foreign-entity", 0, 5000,
                "MAIN", "foreign-" + entity, store);
        String foreignStore = actual.campaign("foreign-store", 0, 5000,
                "MAIN", entity, "foreign-" + store);
        for (int index = 0; index < 3; index++) {
            String rawEntity = switch (index) {
                case 0 -> entity.toLowerCase();
                case 1 -> entity.replace("E-", "É-");
                default -> entity + " ";
            };
            String rawStore = switch (index) {
                case 0 -> store.toLowerCase();
                case 1 -> store.replace("S-", "Ś-");
                default -> store + " ";
            };
            String rawTenant = index == 0 ? "main" : "MAIN";
            String rule = actual.campaign("alias-" + index, 0, 1000, rawTenant, rawEntity, rawStore);
            assertThat(jdbc.queryForObject("SELECT tenant_no=? AND entity_no=? AND store_no=?"
                    + " FROM mkt_campaign WHERE campaign_no=?", Integer.class,
                    "MAIN", entity, store, rule)).isEqualTo(1);
            var customer = index == 1 ? actual.guest() : actual.user();
            var request = actual.request("aliases-" + index, customer, Map.of(store, rule));
            var quote = actual.prepare(request);
            assertThat(quote.quote().goodsMinor()).isEqualTo(10000);
            assertThat(quote.quote().discountMinor()).isEqualTo(1000);
            assertThat(quote.quote().dueMinor()).isEqualTo(9000);
            assertThat(quote.quote().promotions()).extracting("promotionNo")
                    .contains(rule, "NONE").doesNotContain(foreignEntity, foreignStore);
            var economic = new CounterEconomicCodec(json).readQuote(stored(quote));
            var seal = economic.counterActivitySeal();
            assertThat(seal.selectedRules()).hasSize(1);
            assertThat(seal.selectedRules().getFirst().tenantNo()).isEqualTo(rawTenant);
            assertThat(seal.selectedRules().getFirst().entityNo()).isEqualTo(rawEntity);
            assertThat(seal.selectedRules().getFirst().storeNo()).isEqualTo(rawStore);
            assertThat(seal.groups().getFirst().merchantNo()).isEqualTo(entity);
            assertThat(seal.groups().getFirst().storeNo()).isEqualTo(store);
            assertThat(economic.activity().applied().getFirst().merchantNo()).isEqualTo(entity);
            assertThat(economic.activity().applied().getFirst().storeNo()).isEqualTo(store);
            assertThat(economic.activity().shares().getFirst().merchantNo()).isEqualTo(entity);
            assertThat(economic.activity().shares().getFirst().storeNo()).isEqualTo(store);
            rejected("foreign-alias-key-" + index, Map.of(entity.toLowerCase(), rule),
                    ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
            if (index == 2) {
                error(() -> actual.request("malformed-trailing-key", actual.user(), Map.of(rawEntity, rule)),
                        ErrorCode.BAD_REQUEST);
            }
            rejected("foreign-alias-ref-" + index, Map.of(store, rule.toUpperCase()),
                    ErrorCode.ACTIVITY_CHOICE_UNAVAILABLE);
            var created = actual.place(actual.accept(quote));
            actual.assertCreated(quote, created);
            jdbc.update("UPDATE mkt_campaign SET status='PAUSED',name='changed',version=version+1"
                    + " WHERE campaign_no=?", rule);
            assertThat(asMerchant(() -> manual.readCounterQuote(scope, request.operation())).quote().quoteHash())
                    .isEqualTo(quote.quote().quoteHash());
            assertThat(actual.prepare(request).quote().promotions()).isEqualTo(quote.quote().promotions());
            actual.assertAdmitted(quote, created);
            jdbc.update("UPDATE mkt_campaign SET deleted=1 WHERE campaign_no=?", rule);
            var source = asMerchant(() -> manual.source(scope,
                    new SourceLookup(quote.quote().operation(), created.subOrderNo())));
            assertThat(source.admissionState()).isEqualTo("ADMITTED");
            assertThat(source.lines()).hasSize(1);
            assertThat(asMerchant(() -> manual.readCounterQuote(scope, request.operation())).quote().promotions())
                    .isEqualTo(quote.quote().promotions());
        }
        String changing = actual.campaign("alias-changing", 0, 1000, "main",
                entity.toLowerCase(), store.toLowerCase());
        var beforeChange = actual.prepare("alias-changing", actual.user(), Map.of(store, changing));
        var accepted = actual.accept(beforeChange);
        jdbc.update("UPDATE mkt_campaign SET store_no=? WHERE campaign_no=?", "foreign-" + store, changing);
        stale(beforeChange, accepted);

        String tamperRule = actual.campaign("alias-tamper", 0, 1000, "main",
                entity.toLowerCase(), store.toLowerCase());
        for (String target : List.of("group", "activity", "consent")) {
            var quote = actual.prepare("alias-tamper-" + target, actual.user(), Map.of(store, tamperRule));
            var created = actual.place(actual.accept(quote));
            if ("consent".equals(target)) {
                jdbc.update("UPDATE ord_checkout_confirmation SET quote_hash=? WHERE operation_no=?",
                        "f".repeat(64), quote.quote().operation().operationNo());
            } else {
                var frozen = (ObjectNode) json.readTree(stored(quote));
                var economic = frozen.get("economic");
                if ("group".equals(target)) {
                    ((ObjectNode) economic.get("counterActivitySeal").get("groups").get(0))
                            .put("storeNo", "foreign-" + store);
                } else {
                    ((ObjectNode) economic.get("activity").get("applied").get(0))
                            .put("activityNo", "foreign-activity");
                }
                jdbc.update("UPDATE ord_checkout_quote SET pricing_json=? WHERE quote_no=?",
                        json.writeValueAsString(frozen), quote.quote().quoteNo());
            }
            error(() -> asMerchant(() -> manual.confirm(scope, actual.receipt(quote, created))),
                    ErrorCode.CONFLICT);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_manual_service_admission WHERE order_no=?",
                    Long.class, created.orderNo())).isZero();
        }
    }

    private String stored(CounterCheckoutQuote quote) {
        return jdbc.queryForObject("SELECT pricing_json FROM ord_checkout_quote WHERE quote_no=?", String.class,
                quote.quote().quoteNo());
    }

    private void rejected(String suffix, Map<String, String> choices, ErrorCode expected) {
        var request = actual.request(suffix, actual.user(), choices);
        error(() -> actual.prepare(request), expected);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_checkout_quote WHERE operation_no=?",
                Long.class, request.operation().operationNo())).isZero();
    }

    private static void error(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                failure -> assertThat(failure.errorCode()).isEqualTo(expected));
    }

    private void stale(CounterCheckoutQuote quote, CounterConfirmation accepted) {
        var stale = actual.place(accepted);
        assertThat(stale.phase()).isEqualTo("REQUOTE_REQUIRED");
        assertThat(stale.reasonCode()).isEqualTo("CHECKOUT_QUOTE_STALE");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ord_order WHERE operation_no=?", Long.class,
                quote.quote().operation().operationNo())).isZero();
    }

    private void fencedRace(String suffix, Map<String, String> choices, Supplier<String> mutation) throws Exception {
        var quote = actual.prepare(suffix, actual.user(), choices);
        var accepted = actual.accept(quote);
        var finalReached = new CountDownLatch(1);
        var allowWrite = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(call -> {
            var result = call.callRealMethod();
            if (calls.incrementAndGet() == 2) {
                finalReached.countDown();
                assertThat(allowWrite.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return result;
        }).when(finalGuard).requireCounterMatches(any(), any());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var placing = pool.submit(() -> actual.place(accepted));
            assertThat(finalReached.await(10, TimeUnit.SECONDS)).isTrue();
            var changing = pool.submit(() -> {
                writerStarted.countDown();
                return mutation.get();
            });
            assertThat(writerStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> changing.get(250, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            allowWrite.countDown();
            var created = placing.get(10, TimeUnit.SECONDS);
            actual.assertCreated(quote, created);
            assertThat(changing.get(10, TimeUnit.SECONDS)).isNotBlank();
        } finally {
            allowWrite.countDown();
            reset(finalGuard);
        }
    }

    private String published(String suffix, long threshold) {
        String head = actual.promotion(suffix, "CUT", threshold, 1000);
        String version = "version-" + head;
        String definition = "{\"definition\":{\"name\":\"published\",\"scene\":\"ORDER\","
                + "\"templateCode\":\"AP001\",\"templateVersion\":1,\"storeParticipationScope\":{"
                + "\"mode\":\"ALL\",\"refs\":[]},\"audienceRef\":null,\"schedule\":{\"type\":\"ALWAYS_ON\","
                + "\"startAt\":\"2020-01-01T00:00:00Z\",\"endAt\":null,\"zone\":\"Asia/Shanghai\","
                + "\"weekdays\":[],\"from\":null,\"to\":null},\"rule\":{\"schemaVersion\":1,\"trigger\":{"
                + "\"type\":\"AMOUNT\",\"amountMinor\":" + threshold + ",\"quantity\":null},\"benefits\":[{"
                + "\"kind\":\"CUT\",\"amountMinor\":1000,\"templateVersionNo\":null,\"goodsNo\":null,"
                + "\"quantity\":null}],\"tiers\":[]},\"quotaLimit\":10,\"budgetLimitMinor\":null,"
                + "\"perUserLimit\":1,\"currency\":\"CNY\"},\"audienceRuleSnapshot\":null}";
        jdbc.update("INSERT INTO pmt_activity_version(version_no,activity_no,version_index,schema_version,currency,"
                + "schedule_type,starts_at,market_zone,rule_snapshot,rule_hash,published_by,published_at,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,1,1,'CNY','ALWAYS_ON',NOW(),'Asia/Shanghai',?,"
                + "REPEAT('a',64),'fixture',NOW(),'MAIN',NOW(),NOW())", version, head, definition);
        jdbc.update("UPDATE pmt_activity SET current_version_no=?,draft_snapshot='{}' WHERE activity_no=?",
                version, head);
        return head;
    }
}
