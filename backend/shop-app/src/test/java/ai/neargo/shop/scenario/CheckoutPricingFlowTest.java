package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.marketing.CampaignPort;
import ai.neargo.shop.spi.product.StoreSalesCatalogPort;
import ai.neargo.shop.spi.trade.CheckoutPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutPricingFlowTest extends CheckoutFlowFixture {
    @Test
    void prepareQuotesActualServicePriceWithoutCreatingOrdersOrReservingStock() {
        long beforeOrders = count("ord_order");
        long beforeIntents = count("ord_create_intent");
        var request =
                new CheckoutPort.PrepareCheckoutReq(
                        new CheckoutPort.OperationRef("BEAUTY", "op-" + sku),
                        "prepare-" + sku,
                        reference,
                        1,
                        "a".repeat(64),
                        List.of(new CheckoutPort.CheckoutLineReq("service-line", sku, 3)),
                        "ONLINE",
                        null,
                        Map.of(),
                        null);
        var quote = asMerchant(() -> checkout.prepare(scope, request));
        assertThat(quote).as("prepare must return an owner-backed economic quote").isNotNull();
        assertThat(quote.buyerNo()).isEqualTo(buyer);
        assertThat(quote.goodsMinor()).isEqualTo(303);
        assertThat(quote.dueMinor()).isEqualTo(303);
        assertThat(quote.lines())
                .singleElement()
                .satisfies(
                        line -> {
                            assertThat(line.clientLineRef()).isEqualTo("service-line");
                            assertThat(line.quantity()).isEqualTo(3);
                            assertThat(line.uom()).isEqualTo("COUNT");
                            assertThat(line.unitPriceMinor()).isEqualTo(101);
                            assertThat(line.dueMinor()).isEqualTo(303);
                        });
        assertThat(count("ord_order")).isEqualTo(beforeOrders);
        assertThat(count("ord_create_intent")).isEqualTo(beforeIntents);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT locked_stock FROM prd_sku WHERE sku_no=?",
                                Integer.class,
                                sku))
                .isZero();
        assertThat(
                        jdbc.queryForList(
                                        "SELECT * FROM ord_checkout_operation WHERE operation_no=?",
                                        "op-" + sku)
                                .toString())
                .doesNotContain(reference, request.requestKey());
    }

    @Test
    void ordinaryLocalAmountCouponIsQuotedWithoutConsumption() {
        String coupon = promoCoupon("ALL", 0, 1);
        var quote = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        assertThat(quote.discountMinor()).isEqualTo(23);
        assertThat(quote.dueMinor()).isEqualTo(280);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM pmt_user_coupon WHERE user_coupon_no=?",
                                String.class,
                                coupon))
                .isEqualTo("UNUSED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT times_used FROM pmt_user_coupon WHERE user_coupon_no=?",
                                Integer.class,
                                coupon))
                .isZero();
    }

    @Test
    void unsupportedCouponScopeCannotBeSilentlyIgnoredByOrdinaryAllocator() {
        String coupon = promoCoupon("GOODS", 0, 1);
        unsupported(() -> checkout.prepare(scope, request(coupon)));
    }

    @Test
    void unsupportedQuantityAndMultiUseCouponsAreRejected() {
        String coupon = promoCoupon("ALL", 4, 1);
        unsupported(() -> checkout.prepare(scope, request(coupon)));
        jdbc.update("UPDATE pmt_coupon SET min_qty=0,times_total=2 WHERE coupon_no=?", "CT-" + sku);
        unsupported(() -> checkout.prepare(scope, request(coupon)));
    }

    @Test
    void migrationFenceIsRejectedAsUnsupportedBeforeAQuoteIsPublished() {
        String coupon = promoCoupon("ALL", 0, 1);
        jdbc.update(
                "UPDATE pmt_coupon SET migration_state='DRAINING',migration_batch_no='batch' WHERE"
                        + " coupon_no=?",
                "CT-" + sku);
        unsupported(() -> checkout.prepare(scope, request(coupon)));
    }

    @Test
    void zeroMoneyPointsComboCannotDisappearFromTheConfirmedContents() {
        String activity = "ACT-" + sku;
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO"
                        + " pmt_activity(activity_no,entity_no,store_no,name,trigger_type,benefit_type,"
                          + "status,start_at,end_at,created_at,updated_at)"
                        + " VALUES (?,?,?,'points combo','NONE','COMBO','RUNNING',?,?,NOW(),NOW())",
                activity,
                entity,
                store,
                now - 1000,
                now + 600000);
        jdbc.update(
                "INSERT INTO"
                        + " pmt_activity_rule(activity_no,kind,seq,rule_type,params,created_at,updated_at)"
                        + " VALUES"
                        + " (?,'BENEFIT',0,'POINTS','{\"kind\":\"BENEFIT\",\"type\":\"POINTS\",\"n\":10}',NOW(),NOW())",
                activity);
        unsupported(() -> checkout.prepare(scope, request(null)));
    }

    @Test
    void committedReplayKeepsTheOriginalQuoteWhileNewPayloadConflicts() {
        var request = request(null);
        var first = asMerchant(() -> checkout.prepare(scope, request));
        jdbc.update("UPDATE prd_sku SET price=999 WHERE sku_no=?", sku);
        var replay = asMerchant(() -> checkout.prepare(scope, request));
        assertThat(replay.quoteNo()).isEqualTo(first.quoteNo());
        assertThat(replay.dueMinor()).isEqualTo(303);
        var changed =
                new CheckoutPort.PrepareCheckoutReq(
                        request.operation(),
                        request.requestKey(),
                        request.customerRef(),
                        request.scopeRevision(),
                        request.scopeHash(),
                        List.of(new CheckoutPort.CheckoutLineReq("service-line", sku, 2)),
                        "ONLINE",
                        null,
                        Map.of(),
                        null);
        assertThatThrownBy(() -> asMerchant(() -> checkout.prepare(scope, changed)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void overflowThatWrapsPositiveIsRejectedBeforeQuoteOrIntent() {
        jdbc.update("UPDATE prd_sku SET price=? WHERE sku_no=?", Long.MAX_VALUE, sku);
        long before = count("ord_create_intent");
        assertThatThrownBy(() -> asMerchant(() -> checkout.prepare(scope, request(null))))
                .isInstanceOf(BizException.class);
        assertThat(count("ord_create_intent")).isEqualTo(before);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_operation WHERE operation_no=?",
                                Integer.class,
                                "op-" + sku))
                .isZero();
    }

    @Test
    void currentBuyerConfirmationAuthorizesExactlyOneActualOrder() {
        var quote = asMerchant(() -> checkout.prepare(scope, request(null)));
        var user = LoginUser.consumer(buyer, "checkout buyer");
        String token = tokens.of(Realm.CONSUMER).issue(new SessionData(user));
        var auth = UsernamePasswordAuthenticationToken.authenticated(user, null, List.of());
        auth.setDetails(token);
        SecurityContextHolder.getContext().setAuthentication(auth);
        try {
            var confirmation =
                    checkout.confirm(
                            new CheckoutPort.ConfirmCheckoutReq(
                                    quote.operation(),
                                    quote.quoteNo(),
                                    quote.quoteRevision(),
                                    quote.quoteHash(),
                                    "confirm-" + sku));
            assertThat(confirmation).as("the owner must persist current buyer consent").isNotNull();
            var placed =
                    checkout.place(
                            new CheckoutPort.PlaceCheckoutReq(
                                    quote.operation(),
                                    confirmation.confirmationNo(),
                                    "place-" + sku));
            assertThat(placed)
                    .as("authorized checkout must call the real ordinary writer")
                    .isNotNull();
            assertThat(placed.phase()).isEqualTo("CREATED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT source_operation_no FROM ord_sub_order WHERE"
                                            + " sub_order_no=?",
                                    String.class,
                                    placed.subOrderNo()))
                    .isEqualTo(quote.operation().operationNo());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_line WHERE operation_no=?",
                                    Integer.class,
                                    quote.operation().operationNo()))
                    .isEqualTo(1);
        } finally {
            SecurityContextHolder.clearContext();
            tokens.of(Realm.CONSUMER).revoke(token);
        }
    }

    @Test
    void priceMutationAfterPrepareIsRejectedAtWriterSealBeforeStock() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var confirmation = asBuyer(() -> confirm(q));
        jdbc.update("UPDATE prd_sku SET price=102 WHERE sku_no=?", sku);
        assertStaleWithoutEffects(q, confirmation);
    }

    @Test
    void sameAmountDifferentDiscountFundingFailsSeal() {
        String coupon = promoCoupon("ALL", 0, 1);
        var q = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        assertThat(q.dueMinor()).isEqualTo(280);
        var confirmation = asBuyer(() -> confirm(q));
        jdbc.update("UPDATE pmt_coupon SET funder='PLATFORM' WHERE coupon_no=?", "CT-" + sku);
        assertStaleWithoutEffects(q, confirmation);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM pmt_user_coupon WHERE user_coupon_no=?",
                                String.class,
                                coupon))
                .isEqualTo("UNUSED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM pmt_apply WHERE promo_no=?",
                                Integer.class,
                                coupon))
                .isZero();
    }

    @Test
    void catalogMutationIsRecheckedInFinalWriterTransaction() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var confirmation = asBuyer(() -> confirm(q));
        asMerchant(
                () ->
                        catalogs.publish(
                                entity,
                                store,
                                actor,
                                new StoreSalesCatalogPort.PublishReq(
                                        1L,
                                        OffsetDateTime.ofInstant(
                                                        testClock.instant(), ZoneOffset.UTC)
                                                .minusSeconds(1),
                                        "UTC",
                                        List.of(),
                                        List.of(
                                                new StoreSalesCatalogPort.Entry(
                                                        sku,
                                                        null,
                                                        1,
                                                        true,
                                                        StoreSalesCatalogPort.SalesMode
                                                                .UNRESTRICTED,
                                                        List.of()))),
                                "republish-" + sku));
        assertStaleWithoutEffects(q, confirmation);
    }

    @Test
    void ordinaryLocalCouponStillUsesOwnerAllocation() {
        String coupon = promoCoupon("ALL", 0, 1);
        var q = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        var c = asBuyer(() -> confirm(q));
        var placed = asBuyer(() -> checkout.place(place(q, c)));
        assertThat(placed.phase()).isEqualTo("CREATED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT paid_share_minor FROM ord_item WHERE sub_order_no=?",
                                Long.class,
                                placed.subOrderNo()))
                .isEqualTo(280);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT amount_minor FROM pmt_apply WHERE promo_no=?",
                                Long.class,
                                coupon))
                .isEqualTo(23);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM pmt_user_coupon WHERE user_coupon_no=?",
                                String.class,
                                coupon))
                .isEqualTo("USED");
    }

    @Test
    void couponLockWaitAcrossExpiryRejectsBeforeIntent() throws Exception {
        String coupon = promoCoupon("ALL", 0, 1);
        var q = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        var c = asBuyer(() -> confirm(q));
        long beforeOrders = count("ord_order"), beforeIntents = count("ord_create_intent");
        try (var connection = dataSource.getConnection();
                var threads = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "SELECT id FROM pmt_coupon WHERE coupon_no=? FOR UPDATE")) {
                statement.setString(1, "CT-" + sku);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            var worker = new AtomicReference<Thread>();
            var placing =
                    threads.submit(
                            () -> {
                                worker.set(Thread.currentThread());
                                return asBuyer(() -> checkout.place(place(q, c)));
                            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                boolean waiting = false;
                while (System.nanoTime() < deadline && !placing.isDone()) {
                    var trace =
                            worker.get() == null
                                    ? List.<StackTraceElement>of()
                                    : List.of(worker.get().getStackTrace());
                    waiting =
                            trace.stream().anyMatch(f -> f.getMethodName().equals("lockPromo"))
                                    && trace.stream()
                                            .anyMatch(
                                                    f ->
                                                            f.getClassName()
                                                                            .equals(
                                                                                    "ai.neargo.shop.portal.port."
                            + "CouponPortRouter")
                                                                    && f.getMethodName()
                                                                            .equals("lockOrdinary"))
                                    && trace.stream()
                                            .anyMatch(
                                                    f ->
                                                            f.getClassName()
                                                                            .equals(
                                                                                    "sun.nio.ch.Net")
                                                                    && f.getMethodName()
                                                                            .equals("poll"));
                    if (waiting) {
                        break;
                    }
                    Thread.sleep(10);
                }
                if (placing.isDone()) {
                    placing.get();
                }
                assertThat(waiting)
                        .as("actual lockPromo JDBC wait inside checkout lockOrdinary")
                        .isTrue();
                testClock.at = q.expiresAt().plusMillis(1);
            } finally {
                connection.rollback();
            }
            assertThatThrownBy(() -> placing.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BizException.class)
                    .satisfies(
                            failure ->
                                    assertThat(((BizException) failure.getCause()).errorCode())
                                            .isEqualTo(ErrorCode.CHECKOUT_QUOTE_STALE));
        }
        noWriterEffects(beforeOrders, beforeIntents);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM pmt_user_coupon WHERE user_coupon_no=?",
                                String.class,
                                coupon))
                .isEqualTo("UNUSED");
    }

    private void assertStaleWithoutEffects(
            CheckoutPort.CheckoutQuote q, CheckoutPort.BuyerConfirmation c) {
        long orders = count("ord_order"), intents = count("ord_create_intent");
        assertThatThrownBy(() -> asBuyer(() -> checkout.place(place(q, c))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CHECKOUT_QUOTE_STALE));
        noWriterEffects(orders, intents);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT phase FROM ord_checkout_operation WHERE operation_no=?",
                                String.class,
                                q.operation().operationNo()))
                .isEqualTo("REQUOTE_REQUIRED");
    }

    private void noWriterEffects(long orders, long intents) {
        assertThat(count("ord_order")).isEqualTo(orders);
        assertThat(count("ord_create_intent")).isEqualTo(intents);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT locked_stock FROM prd_sku WHERE sku_no=?",
                                Integer.class,
                                sku))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_line WHERE sku_no=?",
                                Integer.class,
                                sku))
                .isZero();
    }

    @Test
    void frozenEconomicEvidenceRetainsCatalogScopeAndCommandHash() throws Exception {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        String stored =
                jdbc.queryForObject(
                        "SELECT pricing_json FROM ord_checkout_quote WHERE quote_no=?",
                        String.class,
                        q.quoteNo());
        var tree = new ObjectMapper().readTree(stored);
        assertThat(tree.path("evidence").isMissingNode())
                .as("frozen pricing must retain its actual evidence, not only a digest")
                .isFalse();
        assertThat(tree.path("evidence").path("tenantNo").asString()).isEqualTo("MAIN");
        assertThat(tree.path("evidence").path("scopeHash").asString()).isEqualTo("a".repeat(64));
        assertThat(tree.path("evidence").path("catalogVersionNo").asString()).isNotBlank();
        assertThat(tree.path("evidence").path("catalogHash").asString()).matches("[a-f0-9]{64}");
        assertThat(tree.path("evidence").path("commandHash").asString()).matches("[a-f0-9]{64}");
    }

    @Autowired private CampaignPort campaignPort;

    @Test
    void merchantPrepareUsesBuyerAudienceForFlashAndDiscount() {
        buyerMember();
        activity("FLASH", "NONE", "PRICE", 80, true);
        activity("CUT", "NONE", "CUT", 23, true);
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        assertThat(q.lines())
                .singleElement()
                .satisfies(line -> assertThat(line.unitPriceMinor()).isEqualTo(80));
        assertThat(q.goodsMinor()).isEqualTo(240);
        assertThat(q.dueMinor()).isEqualTo(217);
    }

    @Test
    void buyerAudienceGiftCannotSilentlyDisappearInMerchantPrepare() {
        buyerMember();
        activity("GIFT", "QTY", "GIFT", 0, true);
        jdbc.update(
                "UPDATE pmt_activity SET trigger_qty=2,benefit_qty=1 WHERE activity_no=?",
                "GIFT-" + sku);
        unsupported(() -> checkout.prepare(scope, request(null)));
    }

    @Test
    void explicitBuyerFlashCannotAcceptCutoffPriceWhileOldOrdinaryApiKeepsIt() {
        activity("CUTOFF", "CUTOFF", "PRICE", 80, false);
        assertThat(asBuyer(() -> campaignPort.flashPrices(List.of(goods))))
                .containsEntry(goods, 80L);
        assertThatThrownBy(
                        () -> asMerchant(() -> campaignPort.flashPricesFor(buyer, List.of(goods))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(ErrorCode.CHECKOUT_UNSUPPORTED_SELECTION));
    }

    private void buyerMember() {
        jdbc.update(
                "INSERT INTO usr_person(person_no,phone_hash,user_no,created_at,updated_at)"
                        + " VALUES (?,?,?,NOW(),NOW())",
                "PER-" + sku,
                "hash-" + sku,
                buyer);
        jdbc.update(
                "INSERT INTO"
                        + " mbr_member(member_no,entity_no,person_no,source,level,joined_at,created_at,updated_at)"
                        + " VALUES (?,?,?,'ORDER','GOLD',?,NOW(),NOW())",
                "MEM-" + sku,
                entity,
                "PER-" + sku,
                System.currentTimeMillis());
    }

    private void activity(
            String name, String trigger, String benefit, long amount, boolean audience) {
        String no = name + "-" + sku;
        long now = System.currentTimeMillis();
        jdbc.update(
                "INSERT INTO"
                        + " pmt_activity(activity_no,entity_no,store_no,name,trigger_type,benefit_type,"
                        + "benefit_amount_minor,status,start_at,end_at,created_at,updated_at) VALUES"
                        + " (?,?,?,?,?,?,?,'RUNNING',?,?,NOW(),NOW())",
                no,
                entity,
                store,
                name,
                trigger,
                benefit,
                amount,
                now - 10000,
                now + 600000);
        jdbc.update(
                "INSERT INTO"
                        + " pmt_activity_goods(activity_no,entity_no,scope_type,ref_no,created_at,updated_at)"
                        + " VALUES (?,?,'GOODS',?,NOW(),NOW())",
                no,
                entity,
                goods);
        if (audience) {
            jdbc.update(
                    "INSERT INTO"
                            + " pmt_activity_audience(activity_no,entity_no,audience_type,audience_value,"
                            + "created_at,updated_at)"
                            + " VALUES (?,?,'LEVEL','GOLD',NOW(),NOW())",
                    no,
                    entity);
        }
    }

    @Test
    void couponWaitCrossingScheduledCatalogVersionRequiresNewQuote() throws Exception {
        String coupon = promoCoupon("ALL", 0, 1);
        var q = asMerchant(() -> checkout.prepare(scope, request(coupon)));
        var c = asBuyer(() -> confirm(q));
        var effective = testClock.at.plusSeconds(30);
        asMerchant(
                () ->
                        catalogs.publish(
                                entity,
                                store,
                                actor,
                                new StoreSalesCatalogPort.PublishReq(
                                        1L,
                                        OffsetDateTime.ofInstant(effective, ZoneOffset.UTC),
                                        "UTC",
                                        List.of(),
                                        List.of(
                                                new StoreSalesCatalogPort.Entry(
                                                        sku,
                                                        null,
                                                        1,
                                                        true,
                                                        StoreSalesCatalogPort.SalesMode
                                                                .UNRESTRICTED,
                                                        List.of()))),
                                "scheduled-" + sku));
        long orders = count("ord_order"), intents = count("ord_create_intent");
        try (var connection = dataSource.getConnection();
                var threads = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "SELECT id FROM pmt_coupon WHERE coupon_no=? FOR UPDATE")) {
                statement.setString(1, "CT-" + sku);
                try (var rows = statement.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                }
            }
            var worker = new AtomicReference<Thread>();
            var future =
                    threads.submit(
                            () -> {
                                worker.set(Thread.currentThread());
                                return asBuyer(() -> checkout.place(place(q, c)));
                            });
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                boolean waiting = false;
                while (!future.isDone() && System.nanoTime() < deadline) {
                    var trace =
                            worker.get() == null
                                    ? List.<StackTraceElement>of()
                                    : List.of(worker.get().getStackTrace());
                    waiting =
                            trace.stream().anyMatch(f -> f.getMethodName().equals("lockPromo"))
                                    && trace.stream()
                                            .anyMatch(
                                                    f ->
                                                            f.getClassName()
                                                                            .equals(
                                                                                    "sun.nio.ch.Net")
                                                                    && f.getMethodName()
                                                                            .equals("poll"));
                    if (waiting) {
                        break;
                    }
                    Thread.sleep(10);
                }
                if (future.isDone()) {
                    future.get();
                }
                assertThat(waiting).isTrue();
                testClock.at = effective.plusMillis(1);
            } finally {
                connection.rollback();
            }
            assertThatThrownBy(() -> future.get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(BizException.class)
                    .satisfies(
                            f ->
                                    assertThat(((BizException) f.getCause()).errorCode())
                                            .isEqualTo(ErrorCode.CHECKOUT_QUOTE_STALE));
        }
        noWriterEffects(orders, intents);
    }

    @Test
    void persistedItemSharesKeepActualLineOrderForEqualRemainders() {
        String second = sku + "-2";
        jdbc.update(
                "INSERT INTO"
                        + " prd_sku(sku_no,goods_no,entity_no,market,price,stock,locked_stock,created_at,updated_at)"
                        + " VALUES (?,?,?,'CN',101,100,0,NOW(),NOW())",
                second,
                goods,
                entity);
        asMerchant(
                () ->
                        catalogs.publish(
                                entity,
                                store,
                                actor,
                                new StoreSalesCatalogPort.PublishReq(
                                        1L,
                                        OffsetDateTime.ofInstant(
                                                        testClock.instant(), ZoneOffset.UTC)
                                                .minusSeconds(1),
                                        "UTC",
                                        List.of(),
                                        List.of(
                                                new StoreSalesCatalogPort.Entry(
                                                        sku,
                                                        null,
                                                        0,
                                                        true,
                                                        StoreSalesCatalogPort.SalesMode
                                                                .UNRESTRICTED,
                                                        List.of()),
                                                new StoreSalesCatalogPort.Entry(
                                                        second,
                                                        null,
                                                        1,
                                                        true,
                                                        StoreSalesCatalogPort.SalesMode
                                                                .UNRESTRICTED,
                                                        List.of()))),
                                "two-lines-" + sku));
        String coupon = promoCoupon("ALL", 0, 1);
        var original = request(coupon);
        var request =
                new CheckoutPort.PrepareCheckoutReq(
                        original.operation(),
                        original.requestKey(),
                        reference,
                        1,
                        "a".repeat(64),
                        List.of(
                                new CheckoutPort.CheckoutLineReq("second-first", second, 1),
                                new CheckoutPort.CheckoutLineReq("first-second", sku, 1)),
                        "ONLINE",
                        coupon,
                        Map.of(),
                        null);
        var q = asMerchant(() -> checkout.prepare(scope, request));
        assertThat(q.dueMinor()).isEqualTo(179);
        assertThat(q.lines())
                .extracting(CheckoutPort.QuoteLine::dueMinor)
                .containsExactly(90L, 89L);
        var c = asBuyer(() -> confirm(q));
        var created = asBuyer(() -> checkout.place(place(q, c)));
        assertThat(
                        jdbc.queryForList(
                                "SELECT paid_share_minor FROM ord_item WHERE sub_order_no=? ORDER"
                                        + " BY id",
                                Long.class,
                                created.subOrderNo()))
                .containsExactly(90L, 89L);
        assertThat(
                        jdbc.queryForList(
                                "SELECT client_line_ref FROM ord_checkout_line WHERE operation_no=?"
                                        + " ORDER BY order_item_id",
                                String.class,
                                q.operation().operationNo()))
                .containsExactly("second-first", "first-second");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT discount_merchant FROM ord_sub_order WHERE sub_order_no=?",
                                Long.class,
                                created.subOrderNo()))
                .isEqualTo(23);
    }
}
