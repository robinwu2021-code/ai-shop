package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.media.MediaUploadService;
import ai.neargo.shop.spi.settle.PaymentOperationPort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.CheckoutPort.CheckoutResult;
import ai.neargo.shop.spi.trade.CheckoutPort.PrepareCheckoutReq;
import ai.neargo.shop.trade.service.OrderService;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.WebApplicationContext;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;

/**
 * Actual current cashier HTTP, frozen consumer consent and authoritative offline ledger receipts.
 */
@EnabledIfSystemProperty(named = "checkout.test.native", matches = "true")
class OfflineReceiptFlowTest extends CheckoutFlowFixture {
    @Autowired private WebApplicationContext context;
    @Autowired private ObjectMapper json;
    @Autowired private OrderService orders;
    @Autowired private MediaUploadService uploads;
    @MockitoSpyBean private PaymentOperationPort financial;
    private static final Path DIRECTORY =
            Path.of(
                    System.getProperty("java.io.tmpdir"),
                    "task57-offline-evidence-" + UUID.randomUUID());

    @DynamicPropertySource
    static void privateStorage(DynamicPropertyRegistry properties) {
        properties.add("shop.upload.dir", DIRECTORY::toString);
    }

    @AfterAll
    static void cleanupOwnedDirectory() throws Exception {
        if (Files.exists(DIRECTORY)) {
            try (var paths = Files.walk(DIRECTORY)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void authorizedCashCreatesOneRealReceiptAndReplaysOriginalKeyAndNewAlias() throws Exception {
        var placed = placedOffline();
        var receipt = cashBody();
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        try {
            var first = confirm(token, placed, "cash-first", receipt);
            assertThat(first.path("code").asInt()).isZero();
            var actual = first.path("data");
            assertThat(actual.path("phase").asString()).isEqualTo("SUCCEEDED");
            assertThat(actual.path("receiptNo").asString()).startsWith("RC");
            assertThat(actual.path("payParams").size()).isZero();
            var replay = confirm(token, placed, "cash-first", receipt).path("data");
            assertThat(replay.path("paymentOperationNo").asString())
                    .isEqualTo(actual.path("paymentOperationNo").asString());
            assertThat(replay.path("receiptNo").asString())
                    .isEqualTo(actual.path("receiptNo").asString());
            var alias = confirm(token, placed, "cash-alias", receipt).path("data");
            assertThat(alias.path("paymentOperationNo").asString())
                    .isNotEqualTo(actual.path("paymentOperationNo").asString());
            assertThat(alias.path("receiptNo").asString())
                    .isEqualTo(actual.path("receiptNo").asString());
            assertThat(alias.path("outTradeNo").asString())
                    .isEqualTo(actual.path("outTradeNo").asString());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND"
                                            + " direction='PAY'",
                                    Long.class,
                                    placed.orderNo()))
                    .isEqualTo(1L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM stl_payment WHERE order_no=? AND"
                                            + " direction='PAY'",
                                    String.class,
                                    placed.orderNo()))
                    .isEqualTo("SUCCESS");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM ord_order WHERE order_no=?",
                                    String.class,
                                    placed.orderNo()))
                    .isEqualTo("PAID");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM ord_sub_order WHERE sub_order_no=?",
                                    String.class,
                                    placed.subOrderNo()))
                    .isEqualTo("FULFILLING");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT verify_code FROM ord_sub_order WHERE sub_order_no=?",
                                    String.class,
                                    placed.subOrderNo()))
                    .isNull();
            var bill =
                    jdbc.queryForMap(
                            "SELECT"
                                + " status,commission_minor,waived_commission_minor,"
                                    + "channel_fee_minor,split_amount_minor,gross_minor,net_minor"
                                + " FROM stl_bill WHERE sub_order_no=?",
                            placed.subOrderNo());
            assertThat(bill.get("status")).isEqualTo("OFFLINE_SETTLED");
            for (String field :
                    List.of("commission_minor", "channel_fee_minor", "split_amount_minor")) {
                assertThat(((Number) bill.get(field)).longValue()).isZero();
            }
            assertThat(((Number) bill.get("gross_minor")).longValue()).isEqualTo(303);
            assertThat(((Number) bill.get("net_minor")).longValue()).isEqualTo(303);
            assertThat(((Number) bill.get("waived_commission_minor")).longValue())
                    .isGreaterThanOrEqualTo(0);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_split_log WHERE sub_order_no=?",
                                    Long.class,
                                    placed.subOrderNo()))
                    .isZero();
        } finally {
            tokens.of(Realm.MERCHANT).revoke(token);
        }
    }

    @Test
    void
            firstAlreadyReceivedCashAfterActualCancellationAndPolicyChangePreservesReceiptAndLateResolution()
                    throws Exception {
        var placed = placedOffline();
        asBuyer(() -> orders.cancel(placed.orderNo(), "buyer cancelled"));
        jdbc.update("UPDATE mch_store SET offline_pay_enabled=0 WHERE store_no=?", store);
        jdbc.update("UPDATE prd_goods SET on_sale=0,deleted=1 WHERE goods_no=?", goods);
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        try {
            var result = confirm(token, placed, "late-first", cashBody());
            assertThat(result.path("code").asInt()).isZero();
            assertThat(result.path("data").path("phase").asString()).isEqualTo("MANUAL_REQUIRED");
            assertThat(result.path("data").path("receiptNo").asString()).startsWith("RC");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT status FROM ord_order WHERE order_no=?",
                                    String.class,
                                    placed.orderNo()))
                    .isEqualTo("CANCELLED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT amount_minor FROM stl_payment WHERE order_no=? AND"
                                            + " direction='PAY'",
                                    Long.class,
                                    placed.orderNo()))
                    .isEqualTo(303L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT reason FROM ord_payment_resolution WHERE pay_no=?",
                                    String.class,
                                    placed.orderNo()))
                    .isEqualTo("LATE_PAYMENT");
            var source =
                    asMerchant(
                            () ->
                                    checkout.source(
                                            scope,
                                            new CheckoutPort.SourceLookup(
                                                    placed.operation(), placed.subOrderNo())));
            assertThat(source.receivedMinor()).isEqualTo(303);
            assertThat(source.executionState()).isEqualTo("BLOCKED");
            assertThat(source.lines())
                    .allSatisfy(line -> assertThat(line.refundableQuantity()).isZero());
            assertThat(source.payments())
                    .singleElement()
                    .satisfies(
                            payment ->
                                    assertThat(payment.receiptNo())
                                            .isEqualTo(
                                                    result.path("data")
                                                            .path("receiptNo")
                                                            .asString()));
        } finally {
            tokens.of(Realm.MERCHANT).revoke(token);
        }
    }

    @Test
    void strictAmountAndOriginalConsumedTimeBoundaryCannotCreateFinancialFacts() throws Exception {
        var placed = placedOffline();
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        try {
            for (Object invalid : new Object[] {"303", 303.0, 302L, -1L}) {
                var body = new HashMap<>(cashBody());
                body.put("amountMinor", invalid);
                assertThat(
                                confirm(
                                                token,
                                                placed,
                                                "bad-amount-"
                                                        + invalid.getClass().getSimpleName()
                                                        + invalid,
                                                body)
                                        .path("code")
                                        .asInt())
                        .isNotZero();
            }
            var negative = new HashMap<>(cashBody());
            negative.put("amountMinor", -1L);
            assertThat(confirm(token, placed, "negative-typed", negative).path("code").asInt())
                    .isEqualTo(10400);
            for (String invalidField : List.of("missing", "null", "actorAccountNo", "payment")) {
                var invalid = new HashMap<>(cashBody());
                if ("missing".equals(invalidField)) {
                    invalid.remove("amountMinor");
                } else if ("null".equals(invalidField)) {
                    invalid.put("amountMinor", null);
                } else {
                    invalid.put(invalidField, "forged");
                }
                assertThat(
                                confirm(token, placed, "invalid-field-" + invalidField, invalid)
                                        .path("code")
                                        .asInt())
                        .isEqualTo(10400);
            }
            var consumed =
                    jdbc.queryForObject(
                                    "SELECT consumed_at FROM ord_checkout_confirmation WHERE"
                                            + " order_no=?",
                                    LocalDateTime.class,
                                    placed.orderNo())
                            .toInstant(ZoneOffset.UTC);
            var early = new HashMap<>(cashBody());
            early.put("receivedAt", consumed.minusMillis(1).toString());
            assertThat(confirm(token, placed, "early", early).path("code").asInt()).isNotZero();
            var future = new HashMap<>(cashBody());
            future.put(
                    "receivedAt",
                    testClock.instant().plusSeconds(121).truncatedTo(ChronoUnit.MILLIS).toString());
            assertThat(confirm(token, placed, "future", future).path("code").asInt()).isNotZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                    Long.class,
                                    placed.orderNo()))
                    .isZero();
            var boundary = new HashMap<>(cashBody());
            boundary.put("receivedAt", consumed.toString());
            assertThat(confirm(token, placed, "exact-consumed", boundary).path("code").asInt())
                    .isZero();
        } finally {
            tokens.of(Realm.MERCHANT).revoke(token);
        }
    }

    @Test
    void
            bankTransferBindsActualEvidenceAndCannotReuseItAcrossOrdersButKeepsOriginalReceiptOnReplay()
                    throws Exception {
        var firstOrder = placedOffline("one");
        var secondOrder = placedOffline("two");
        String evidence = uploadEvidence();
        var body = new HashMap<>(cashBody());
        body.put("method", "BANK_TRANSFER");
        body.put("evidenceRef", evidence);
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        try {
            var first = confirm(token, firstOrder, "bank-original", body);
            assertThat(first.path("code").asInt()).isZero();
            String receipt = first.path("data").path("receiptNo").asString();
            assertThat(receipt).startsWith("RC");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT actor_account_no FROM stl_payment_operation"
                                            + " WHERE evidence_ref=?",
                                    String.class,
                                    evidence))
                    .isEqualTo(actor);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT method FROM stl_payment_operation WHERE evidence_ref=?",
                                    String.class,
                                    evidence))
                    .isEqualTo("BANK_TRANSFER");
            var alias = confirm(token, firstOrder, "bank-alias", body);
            assertThat(alias.path("code").asInt()).isZero();
            assertThat(alias.path("data").path("receiptNo").asString()).isEqualTo(receipt);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment_operation WHERE"
                                            + " evidence_ref=?",
                                    Long.class,
                                    evidence))
                    .isEqualTo(1L);
            jdbc.update("UPDATE sys_media_asset SET status='PENDING' WHERE asset_key=?", evidence);
            assertThat(
                            confirm(token, firstOrder, "bank-original", body)
                                    .path("data")
                                    .path("receiptNo")
                                    .asString())
                    .isEqualTo(receipt);
            jdbc.update("UPDATE sys_media_asset SET status='ACTIVE' WHERE asset_key=?", evidence);
            assertThat(confirm(token, secondOrder, "bank-reuse", body).path("code").asInt())
                    .isEqualTo(20021);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                    Long.class,
                                    secondOrder.orderNo()))
                    .isZero();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND"
                                            + " status='SUCCESS'",
                                    Long.class,
                                    firstOrder.orderNo()))
                    .isEqualTo(1L);
        } finally {
            tokens.of(Realm.MERCHANT).revoke(token);
        }
    }

    @Test
    void sourceExposesOneCanonicalOriginalReceiptAndCallsFinancialOwnerOnlyOutsideSnapshot()
            throws Exception {
        var placed = placedOffline();
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        JsonNode first;
        try {
            var body = cashBody();
            first = confirm(token, placed, "source-cash", body).path("data");
            assertThat(first.path("phase").asString()).isEqualTo("SUCCEEDED");
            assertThat(
                            confirm(token, placed, "source-alias", body)
                                    .path("data")
                                    .path("receiptNo")
                                    .asString())
                    .isEqualTo(first.path("receiptNo").asString());
        } finally {
            tokens.of(Realm.MERCHANT).revoke(token);
        }
        var underTransaction = new AtomicBoolean();
        var queries = new AtomicInteger();
        doAnswer(
                        call -> {
                            underTransaction.set(
                                    underTransaction.get()
                                            || TransactionSynchronizationManager
                                                    .isActualTransactionActive());
                            queries.incrementAndGet();
                            return call.callRealMethod();
                        })
                .when(financial)
                .query(anyString());
        var source =
                asMerchant(
                        () ->
                                checkout.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                placed.operation(), placed.subOrderNo())));
        assertThat(source.receivedMinor()).isEqualTo(303);
        assertThat(source.payments())
                .singleElement()
                .satisfies(
                        payment -> {
                            assertThat(payment.paymentOperationNo())
                                    .isEqualTo(first.path("paymentOperationNo").asString());
                            assertThat(payment.outTradeNo())
                                    .isEqualTo(first.path("outTradeNo").asString());
                            assertThat(payment.receiptNo())
                                    .isEqualTo(first.path("receiptNo").asString());
                        });
        assertThat(queries.get()).isPositive();
        assertThat(underTransaction).isFalse();
    }

    @Test
    void twoBankRequestsPreparedBeforeOpenShareOneEvidenceBindingAndRealReceipt() throws Exception {
        var placed = placedOffline();
        String evidence = uploadEvidence();
        var body = new HashMap<>(cashBody());
        body.put("method", "BANK_TRANSFER");
        body.put("evidenceRef", evidence);
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        jdbc.update(
                "INSERT INTO stl_payment_order_guard(order_no,created_at,updated_at) VALUES"
                        + " (?,NOW(),NOW())",
                placed.orderNo());
        try (var blocker = dataSource.getConnection();
                var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var lock =
                    blocker.prepareStatement(
                            "SELECT order_no FROM stl_payment_order_guard"
                                    + " WHERE order_no=? FOR UPDATE")) {
                lock.setString(1, placed.orderNo());
                try (var row = lock.executeQuery()) {
                    assertThat(row.next()).isTrue();
                }
            }
            var first = executor.submit(() -> confirm(token, placed, "bank-before-open-a", body));
            var second = executor.submit(() -> confirm(token, placed, "bank-before-open-b", body));
            try {
                long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
                long count;
                do {
                    count =
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment_operation WHERE order_no=?"
                                            + " AND phase='PREPARED' AND payment_no IS NULL",
                                    Long.class,
                                    placed.orderNo());
                    if (count == 2) {
                        break;
                    }
                    Thread.sleep(25);
                } while (System.nanoTime() < until);
                assertThat(count)
                        .as("both durable P1 instructions exist before either ledger open")
                        .isEqualTo(2);
                assertThat(
                                jdbc.queryForObject(
                                        "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                        Long.class,
                                        placed.orderNo()))
                        .isZero();
            } finally {
                blocker.rollback();
            }
            var a = first.get(15, TimeUnit.SECONDS);
            var b = second.get(15, TimeUnit.SECONDS);
            assertThat(a.path("code").asInt()).isZero();
            assertThat(b.path("code").asInt()).isZero();
            assertThat(a.path("data").path("paymentOperationNo").asString())
                    .isNotEqualTo(b.path("data").path("paymentOperationNo").asString());
            assertThat(a.path("data").path("receiptNo").asString())
                    .isEqualTo(b.path("data").path("receiptNo").asString())
                    .startsWith("RC");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE order_no=? AND"
                                            + " status='SUCCESS'",
                                    Long.class,
                                    placed.orderNo()))
                    .isEqualTo(1L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment_operation WHERE"
                                            + " evidence_ref=?",
                                    Long.class,
                                    evidence))
                    .isEqualTo(1L);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment_operation WHERE order_no=?"
                                            + " AND phase='ALIASED'",
                                    Long.class,
                                    placed.orderNo()))
                    .isEqualTo(1L);
            String root =
                    jdbc.queryForObject(
                            "SELECT operation_no FROM stl_payment_operation WHERE evidence_ref=?",
                            String.class,
                            evidence);
            for (String column :
                    List.of(
                            "method",
                            "actor_realm",
                            "actor_kind",
                            "actor_no",
                            "actor_account_no",
                            "received_at",
                            "evidence_ref",
                            "evidence_json")) {
                assertThatThrownBy(
                                () ->
                                        jdbc.update(
                                                "UPDATE stl_payment_operation SET "
                                                        + column
                                                        + "=NULL WHERE operation_no=?",
                                                root))
                        .as("native NOT NULL/CHECK: %s", column)
                        .isInstanceOf(DataAccessException.class)
                        .satisfies(
                                error -> {
                                    Throwable cause = error;
                                    while (cause.getCause() != null) {
                                        cause = cause.getCause();
                                    }
                                    assertThat(cause).isInstanceOf(SQLException.class);
                                    assertThat(((SQLException) cause).getErrorCode())
                                            .isEqualTo(3819);
                                });
            }
        } finally {
            tokens.of(Realm.MERCHANT).revoke(token);
        }
    }

    @Test
    void offlineP0SurvivesUnavailableFinancialOwnerAndReusesTheSameOriginalOperation()
            throws Exception {
        var placed = placedOffline();
        var body = cashBody();
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        try {
            doThrow(new IllegalStateException("ISOLATED_OWNER_UNAVAILABLE"))
                    .when(financial)
                    .confirmOffline(any());
            assertThat(confirm(token, placed, "offline-p0", body).path("code").asInt()).isNotZero();
            String original =
                    jdbc.queryForObject(
                            "SELECT payment_operation_no FROM ord_checkout_payment_request"
                                    + " WHERE order_no=?",
                            String.class,
                            placed.orderNo());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment_operation WHERE order_no=?",
                                    Long.class,
                                    placed.orderNo()))
                    .isZero();
            reset(financial);
            var recovered = confirm(token, placed, "offline-p0", body);
            assertThat(recovered.path("code").asInt()).isZero();
            assertThat(recovered.path("data").path("paymentOperationNo").asString())
                    .isEqualTo(original);
            assertThat(recovered.path("data").path("receiptNo").asString()).startsWith("RC");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_payment_request WHERE"
                                            + " order_no=?",
                                    Long.class,
                                    placed.orderNo()))
                    .isEqualTo(1L);
        } finally {
            reset(financial);
            tokens.of(Realm.MERCHANT).revoke(token);
        }
    }

    @Test
    void mappedOwnerUnavailablePreservesLastVerifiedRevisionAndHashWithoutInventingZeroMoney()
            throws Exception {
        var placed = placedOffline();
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        try {
            assertThat(confirm(token, placed, "source-original", cashBody()).path("code").asInt())
                    .isZero();
        } finally {
            tokens.of(Realm.MERCHANT).revoke(token);
        }
        var lookup = new CheckoutPort.SourceLookup(placed.operation(), placed.subOrderNo());
        var verified = asMerchant(() -> checkout.source(scope, lookup));
        assertThat(verified.receivedMinor()).isEqualTo(303);
        try {
            doReturn(null).when(financial).query(anyString());
            var unknown = asMerchant(() -> checkout.source(scope, lookup));
            assertThat(unknown.financialState()).isEqualTo("UNKNOWN");
            assertThat(unknown.receivedMinor()).isNull();
            assertThat(unknown.outstandingMinor()).isNull();
            assertThat(unknown.sourceRevision()).isEqualTo(verified.sourceRevision());
            assertThat(unknown.sourceHash()).isEqualTo(verified.sourceHash());
            assertThat(unknown.executionState()).isEqualTo("BLOCKED");
            assertThat(unknown.payments()).isEmpty();
        } finally {
            reset(financial);
        }
    }

    @Test
    void cashReplayRequiresCurrentPermissionAndRawKeyLookupIsCurrentActorAndStoreScoped()
            throws Exception {
        var placed = placedOffline();
        var body = cashBody();
        String token =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(actor, "cashier")));
        String other = actor + "-other";
        String otherStore = store + "-other";
        jdbc.update(
                "INSERT INTO mch_store(entity_no,store_no,name,status,created_at,updated_at)"
                        + " VALUES (?,?,'other actual store','ACTIVE',NOW(),NOW())",
                entity,
                otherStore);
        jdbc.update(
                "INSERT INTO"
                    + " mch_account(mch_account_no,entity_no,is_owner,status,created_at,updated_at)"
                    + " VALUES (?,?,1,'ACTIVE',NOW(),NOW())",
                other,
                entity);
        String otherToken =
                tokens.of(Realm.MERCHANT)
                        .issue(new SessionData(LoginUser.merchant(other, "other cashier")));
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        String base = "/biz/checkout/" + placed.operation().operationNo() + "/offline-receipts";
        try {
            var original = confirm(token, placed, "private-actor-key", body);
            assertThat(original.path("code").asInt()).isZero();
            String paymentNo = original.path("data").path("paymentOperationNo").asString();
            var wrongStore =
                    json.readTree(
                            mvc.perform(
                                            post(base)
                                                    .header("Authorization", "Bearer " + token)
                                                    .header("X-Store-No", otherStore)
                                                    .header("Idempotency-Key", "wrong-store")
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content(json.writeValueAsString(body)))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(wrongStore.path("code").asInt()).isNotZero();
            var foreignKey =
                    json.readTree(
                            mvc.perform(
                                            post(base + "/lookup")
                                                    .header("Authorization", "Bearer " + otherToken)
                                                    .header("X-Store-No", store)
                                                    .header("Idempotency-Key", "private-actor-key"))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(foreignKey.path("code").asInt()).isNotZero();
            var allowedRead =
                    json.readTree(
                            mvc.perform(
                                            MockMvcRequestBuilders.get(base + "/" + paymentNo)
                                                    .header("Authorization", "Bearer " + otherToken)
                                                    .header("X-Store-No", store))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(allowedRead.path("code").asInt()).isZero();
            assertThat(allowedRead.path("data").path("payParams").size()).isZero();
            jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", actor);
            jdbc.update(
                    "INSERT INTO mch_role(entity_no,role_code,name,perms,created_at,updated_at)"
                        + " VALUES"
                        + " (?,'VIEWER','viewer','[\"biz:receive\",\"biz:order:view\"]',NOW(),NOW())",
                    entity);
            jdbc.update(
                    "INSERT INTO mch_store_role(mch_account_no,store_no,role,created_at,updated_at)"
                            + " VALUES (?,?,'VIEWER',NOW(),NOW())",
                    actor,
                    store);
            assertThat(confirm(token, placed, "private-actor-key", body).path("code").asInt())
                    .isNotZero();
            var readonly =
                    json.readTree(
                            mvc.perform(
                                            post(base + "/lookup")
                                                    .header("Authorization", "Bearer " + token)
                                                    .header("X-Store-No", store)
                                                    .header("Idempotency-Key", "private-actor-key"))
                                    .andReturn()
                                    .getResponse()
                                    .getContentAsString());
            assertThat(readonly.path("code").asInt()).isZero();
            assertThat(readonly.path("data").path("receiptNo").asString())
                    .isEqualTo(original.path("data").path("receiptNo").asString());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM stl_payment WHERE order_no=?",
                                    Long.class,
                                    placed.orderNo()))
                    .isEqualTo(1L);
        } finally {
            tokens.of(Realm.MERCHANT).revoke(token);
            tokens.of(Realm.MERCHANT).revoke(otherToken);
        }
    }

    private String uploadEvidence() throws Exception {
        String key = entity + "/" + store + "/offline_receipt/202610/" + UUID.randomUUID() + ".png";
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        byte[] content = output.toByteArray();
        uploads.store(
                key,
                () -> new ByteArrayInputStream(content),
                content.length,
                "image/png",
                "OFFLINE_RECEIPT",
                entity,
                store,
                actor);
        return key;
    }

    private CheckoutResult placedOffline() {
        return placedOffline("default");
    }

    private CheckoutResult placedOffline(String suffix) {
        jdbc.update("UPDATE mch_store SET offline_pay_enabled=1 WHERE store_no=?", store);
        jdbc.update(
                "UPDATE prd_goods SET pay_modes='[\"ONLINE\",\"OFFLINE\"]' WHERE goods_no=?",
                goods);
        jdbc.update(
                "INSERT IGNORE INTO"
                    + " mch_qualification(qual_no,entity_no,qual_type,qual_name,status,created_at,updated_at)"
                    + " VALUES (?,?,'BUSINESS_LICENSE','license','VALID',NOW(),NOW())",
                "QL-" + sku,
                entity);
        var source = request(null);
        var quote =
                asMerchant(
                        () ->
                                checkout.prepare(
                                        scope,
                                        new PrepareCheckoutReq(
                                                new CheckoutPort.OperationRef(
                                                        "BEAUTY",
                                                        source.operation().operationNo() + suffix),
                                                source.requestKey() + suffix,
                                                source.customerRef(),
                                                source.scopeRevision(),
                                                source.scopeHash(),
                                                source.lines(),
                                                "OFFLINE",
                                                null,
                                                Map.of(),
                                                null)));
        var confirmation =
                asBuyer(
                        () ->
                                checkout.confirm(
                                        new CheckoutPort.ConfirmCheckoutReq(
                                                quote.operation(),
                                                quote.quoteNo(),
                                                quote.quoteRevision(),
                                                quote.quoteHash(),
                                                "confirm-" + sku + suffix)));
        return asBuyer(
                () ->
                        checkout.place(
                                new CheckoutPort.PlaceCheckoutReq(
                                        quote.operation(),
                                        confirmation.confirmationNo(),
                                        "place-" + sku + suffix)));
    }

    private Map<String, Object> cashBody() {
        return Map.of(
                "method",
                "CASH",
                "amountMinor",
                303L,
                "currency",
                "CNY",
                "receivedAt",
                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                "note",
                "received in store");
    }

    private JsonNode confirm(String token, CheckoutResult placed, String key, Object body)
            throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        var response =
                mvc.perform(
                                post("/biz/checkout/"
                                                + placed.operation().operationNo()
                                                + "/offline-receipts")
                                        .header("Authorization", "Bearer " + token)
                                        .header("X-Store-No", store)
                                        .header("Idempotency-Key", key)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isEqualTo(200);
        return json.readTree(response.getContentAsString());
    }
}
