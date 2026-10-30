package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.trade.order.service.create.CheckoutDigests;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.UncategorizedSQLException;

import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class CheckoutNativeMysqlTest extends CheckoutFlowFixture {
    @Test
    void sameKeyCreatesOneOrderAndStableSourceLines() throws Exception {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        var outcomes = race(q, c, false);
        assertThat(outcomes).allMatch(v -> v instanceof CheckoutPort.CheckoutResult);
        var a = (CheckoutPort.CheckoutResult) outcomes.get(0);
        var b = (CheckoutPort.CheckoutResult) outcomes.get(1);
        assertThat(a.orderNo()).isEqualTo(b.orderNo());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE user_no=?",
                                Long.class,
                                buyer))
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_line WHERE operation_no=?",
                                Long.class,
                                q.operation().operationNo()))
                .isEqualTo(1);
    }

    @Test
    void differentKeysCannotConsumeConfirmationTwice() throws Exception {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        var outcomes = race(q, c, true);
        assertThat(outcomes.stream().filter(v -> v instanceof CheckoutPort.CheckoutResult).count())
                .isEqualTo(1);
        assertThat(
                        outcomes.stream()
                                .filter(
                                        v ->
                                                v instanceof BizException e
                                                        && e.errorCode() == ErrorCode.CONFLICT)
                                .count())
                .isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE user_no=?",
                                Long.class,
                                buyer))
                .isEqualTo(1);
    }

    @Test
    void confirmationFirstKeyIsPermanent() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        assertThat(asBuyer(() -> confirm(q)).confirmationNo()).isEqualTo(c.confirmationNo());
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                checkout.confirm(
                                                        new CheckoutPort.ConfirmCheckoutReq(
                                                                q.operation(),
                                                                q.quoteNo(),
                                                                q.quoteRevision(),
                                                                q.quoteHash(),
                                                                "another-key"))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void expiredIdempotencyCacheDoesNotCreateAgain() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        var created = asBuyer(() -> checkout.place(place(q, c)));
        jdbc.update("DELETE FROM sys_idempotent WHERE user_no=?", buyer);
        jdbc.update("UPDATE prd_sku SET price=999 WHERE sku_no=?", sku);
        testClock.at = q.expiresAt().plusSeconds(100);
        var replay = asBuyer(() -> checkout.place(place(q, c)));
        assertThat(replay.orderNo()).isEqualTo(created.orderNo());
        assertThat(asMerchant(() -> checkout.findOperation(scope, q.operation())).orderNo())
                .isEqualTo(created.orderNo());
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                checkout.place(
                                                        new CheckoutPort.PlaceCheckoutReq(
                                                                q.operation(),
                                                                c.confirmationNo(),
                                                                "changed-place"))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }

    @Test
    void expiryBeforePlaceRequiresRequoteAndNeverRegistersIntent() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        long before = count("ord_create_intent");
        testClock.at = q.expiresAt();
        assertThatThrownBy(() -> asBuyer(() -> checkout.place(place(q, c))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CHECKOUT_QUOTE_STALE));
        assertThat(count("ord_create_intent")).isEqualTo(before);
        assertThat(asMerchant(() -> checkout.findOperation(scope, q.operation())).phase())
                .isEqualTo("REQUOTE_REQUIRED");
    }

    @Test
    void nativeConstraintsRejectInvalidAuthorityPairs() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        var created = asBuyer(() -> checkout.place(place(q, c)));
        for (String values :
                List.of(
                        "fulfillment_authority=NULL",
                        "source_operation_no=NULL",
                        "fulfillment=NULL",
                        "fulfillment='EXPRESS'")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE ord_sub_order SET "
                                                    + values
                                                    + " WHERE sub_order_no=?",
                                            created.subOrderNo()))
                    .isInstanceOfSatisfying(
                            UncategorizedSQLException.class,
                            failure ->
                                    assertThat(failure.getSQLException().getErrorCode())
                                            .isEqualTo(3819));
        }
    }

    private List<Object> race(
            CheckoutPort.CheckoutQuote q, CheckoutPort.BuyerConfirmation c, boolean different)
            throws Exception {
        var start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var futures = new ArrayList<Future<Object>>();
            for (int i = 0; i < 2; i++) {
                String key = different ? "race-" + i + "-" + sku : "same-" + sku;
                futures.add(
                        threads.submit(
                                () -> {
                                    start.await();
                                    try {
                                        return asBuyer(
                                                () ->
                                                        checkout.place(
                                                                new CheckoutPort.PlaceCheckoutReq(
                                                                        q.operation(),
                                                                        c.confirmationNo(),
                                                                        key)));
                                    } catch (BizException failure) {
                                        return failure;
                                    }
                                }));
            }
            start.countDown();
            return List.of(
                    futures.get(0).get(20, TimeUnit.SECONDS),
                    futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }

    @Test
    void placeClaimSurvivesWriterRollbackAndReleasedIntentCannotRevive() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        String trigger = "checkout_fail_" + sku.replace("-", "_");
        jdbc.execute(
                "CREATE TRIGGER "
                        + trigger
                        + " BEFORE INSERT ON ord_item FOR EACH ROW "
                        + "BEGIN IF NEW.sku_no='"
                        + sku
                        + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled checkout"
                        + " rollback'; END IF; END");
        try {
            assertThatThrownBy(() -> asBuyer(() -> checkout.place(place(q, c))))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT phase FROM ord_checkout_operation WHERE operation_no=?",
                                String.class,
                                q.operation().operationNo()))
                .isEqualTo("CONFIRMED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT place_key_hash FROM ord_checkout_operation WHERE"
                                        + " operation_no=?",
                                String.class,
                                q.operation().operationNo()))
                .isNotNull();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM ord_create_intent WHERE user_no=?",
                                String.class,
                                buyer))
                .isEqualTo("RELEASED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE user_no=?",
                                Long.class,
                                buyer))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT locked_stock FROM prd_sku WHERE sku_no=?",
                                Integer.class,
                                sku))
                .isZero();
        assertThatThrownBy(
                        () ->
                                asBuyer(
                                        () ->
                                                checkout.place(
                                                        new CheckoutPort.PlaceCheckoutReq(
                                                                q.operation(),
                                                                c.confirmationNo(),
                                                                "replacement"))))
                .isInstanceOfSatisfying(
                        BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        assertThat(asBuyer(() -> checkout.place(place(q, c))).phase()).isEqualTo("REJECTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_order WHERE user_no=?",
                                Long.class,
                                buyer))
                .isZero();
    }

    @Test
    void prepareReplayUsesOriginalResolvedBuyerAfterReferenceExpiry() {
        var request = request(null);
        var first = asMerchant(() -> checkout.prepare(scope, request));
        long tickets = count("mbr_identity_ticket");
        jdbc.update(
                "UPDATE mbr_identity_ticket SET issued_at=DATE_SUB(NOW(),INTERVAL 2"
                        + " DAY),expires_at=DATE_SUB(NOW(),INTERVAL 1 DAY) WHERE user_no=?",
                buyer);
        jdbc.update("UPDATE prd_sku SET price=800 WHERE sku_no=?", sku);
        var replay = asMerchant(() -> checkout.prepare(scope, request));
        assertThat(replay.buyerNo()).isEqualTo(buyer);
        assertThat(replay.quoteNo()).isEqualTo(first.quoteNo());
        assertThat(replay.dueMinor()).isEqualTo(303);
        assertThat(count("mbr_identity_ticket")).isEqualTo(tickets);
        var fresh =
                new CheckoutPort.PrepareCheckoutReq(
                        new CheckoutPort.OperationRef("BEAUTY", "fresh-" + sku),
                        "fresh-key-" + sku,
                        reference,
                        1,
                        "a".repeat(64),
                        request.lines(),
                        "ONLINE",
                        null,
                        Map.of(),
                        null);
        assertThatThrownBy(() -> asMerchant(() -> checkout.prepare(scope, fresh)))
                .isInstanceOf(BizException.class);
    }

    @Autowired private ObjectMapper json;

    @Test
    void preparedAndRecoveringOriginalIntentNeverAllocateAnotherOrder() {
        var q = asMerchant(() -> checkout.prepare(scope, request(null)));
        var c = asBuyer(() -> confirm(q));
        String key =
                "CHK_"
                        + CheckoutDigests.hash(
                                json,
                                List.of(
                                        "MAIN",
                                        q.operation().caller(),
                                        q.operation().operationNo()));
        String pay = "original-" + sku;
        jdbc.update(
                "INSERT INTO"
                        + " ord_create_intent(pay_no,user_no,endpoint,idem_key,request_hash,"
                          + "stock_authority,stock_needs_json,status,next_attempt_at,created_at,updated_at)"
                        + " VALUES (?,?,'POST /mp/order',?,?,'PLATFORM','[]','PREPARED',0,NOW(),NOW())",
                pay,
                buyer,
                key,
                "a".repeat(64));
        for (String state : List.of("PREPARED", "RECOVERING", "RECOVERY_REQUIRED")) {
            jdbc.update("UPDATE ord_create_intent SET status=? WHERE pay_no=?", state, pay);
            assertThat(asMerchant(() -> checkout.findOperation(scope, q.operation())).phase())
                    .isEqualTo("PLACING");
            assertThat(asBuyer(() -> checkout.place(place(q, c))).phase()).isEqualTo("PLACING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT pay_no FROM ord_create_intent WHERE idem_key=?",
                                    String.class,
                                    key))
                    .isEqualTo(pay);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_order WHERE user_no=?",
                                    Long.class,
                                    buyer))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT locked_stock FROM prd_sku WHERE sku_no=?",
                                    Integer.class,
                                    sku))
                    .isZero();
        }
    }

    @Test
    void prepareKeyIsScopedByActualMerchantActorNamespace() {
        var firstRequest = request(null);
        var first = asMerchant(() -> checkout.prepare(scope, firstRequest));
        actor = actor + "-second";
        jdbc.update(
                "INSERT INTO"
                        + " mch_account(entity_no,mch_account_no,user_no,is_owner,status,created_at,updated_at)"
                        + " VALUES (?,?,?,1,'ACTIVE',NOW(),NOW())",
                entity,
                actor,
                actor + "-user");
        scope = new CheckoutPort.CheckoutScope("MAIN", entity, store, actor);
        String code = identities.issue("MAIN", buyer).memberCode();
        reference =
                asMerchant(
                                () ->
                                        identities.resolve(
                                                new MemberCodeScope("MAIN", entity, store, actor),
                                                code))
                        .customerRef();
        var secondRequest =
                new CheckoutPort.PrepareCheckoutReq(
                        new CheckoutPort.OperationRef("BEAUTY", "second-" + sku),
                        firstRequest.requestKey(),
                        reference,
                        1,
                        "a".repeat(64),
                        firstRequest.lines(),
                        "ONLINE",
                        null,
                        Map.of(),
                        null);
        var second = asMerchant(() -> checkout.prepare(scope, secondRequest));
        assertThat(second.quoteNo()).isNotEqualTo(first.quoteNo());
        assertThat(second.buyerNo()).isEqualTo(first.buyerNo());
    }

    @Test
    void frozenOwnerRowsAndDiagnosticRequestsDoNotRetainIdentityCredentials() {
        var request = request(null);
        var q = asMerchant(() -> checkout.prepare(scope, request));
        var c = asBuyer(() -> confirm(q));
        asBuyer(() -> checkout.place(place(q, c)));
        String rows =
                jdbc.queryForMap(
                                        "SELECT * FROM ord_checkout_operation WHERE operation_no=?",
                                        q.operation().operationNo())
                                .toString()
                        + jdbc.queryForMap(
                                "SELECT * FROM ord_checkout_quote WHERE operation_no=?",
                                q.operation().operationNo())
                        + jdbc.queryForMap(
                                "SELECT * FROM ord_checkout_confirmation WHERE operation_no=?",
                                q.operation().operationNo());
        assertThat(rows)
                .doesNotContain(
                        reference,
                        request.requestKey(),
                        "cr1.",
                        "mc1.",
                        "claimToken",
                        "customerRef");
        assertThat(request.toString()).doesNotContain(reference, request.requestKey());
    }
}
