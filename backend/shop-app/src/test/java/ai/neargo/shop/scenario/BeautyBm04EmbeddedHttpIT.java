package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Native child-process acceptance is explicitly gated; no test authentication replaces HOST
 * sessions.
 */
@EnabledIfSystemProperty(named = "beauty.bm04.deployment", matches = "true")
class BeautyBm04EmbeddedHttpIT {
    @Test
    void realTokensRevocationNamespaceAndDeletedParentAreEnforced() throws Exception {
        new Fixture().authorization();
    }

    @Test
    void guestArrivalOpenPurchaseSourceAndNullableBillUseRealOwners() throws Exception {
        new Fixture().lifecycle(false);
    }

    /**
     * Shared HTTP driver uses real child owners; SQL only verifies facts or mutates explicit fault
     * prerequisites.
     */
    static final class Fixture {
        private static final Set<String> CONFIGURED = new HashSet<>();
        final JsonMapper json = JsonMapper.builder().build();
        final Path directory = Path.of(required("BEAUTY_BM04_FIXTURE_DIR"));
        final JsonNode host = json.readTree(Files.readString(directory.resolve("host.json")));
        final HttpClient http =
                HttpClient.newBuilder()
                        .version(HttpClient.Version.HTTP_1_1)
                        .connectTimeout(Duration.ofSeconds(5))
                        .build();
        final String store = host.path("store").asString();
        final String entity = host.path("entity").asString();
        final String sku = host.path("sku").asString();

        Fixture() throws Exception {
            configure();
        }

        void authorization() throws Exception {
            String key = key();
            Object body =
                    Map.of("contact", "namespace-" + key, "displayName", "访客", "consent", true);
            var merchant = post("merchant", "/biz/x/beauty/guest-registrations", key, body);
            var manager = post("manager", "/biz/x/beauty/guest-registrations", key, body);
            assertThat(merchant.path("guestNo").asString())
                    .isNotEqualTo(manager.path("guestNo").asString());
            var replayedGuest = post("merchant", "/biz/x/beauty/guest-registrations", key, body);
            assertThat(replayedGuest.equals(merchant))
                    .as("guest original-key result equality")
                    .isTrue();
            assertThat(replayedGuest.path("guestNo").asString())
                    .isEqualTo(merchant.path("guestNo").asString());
            assertThat(replayedGuest.path("revision").asLong())
                    .isEqualTo(merchant.path("revision").asLong());
            denied("consumer", "/biz/x/beauty/guest-registrations", key(), body, store);
            denied("merchant", "/biz/x/beauty/guest-registrations", key(), body, "foreign-store");
            var root = openGuest(merchant);
            String path = "/biz/x/beauty/work-orders/" + root.path("workOrderNo").asString();
            get("staff", path);
            sql(
                    false,
                    "UPDATE mch_role SET perms='[]' WHERE entity_no=? AND role_code='BM04_TEST'",
                    entity);
            try {
                denied("staff", path, null, null, store);
            } finally {
                sql(
                        false,
                        "UPDATE mch_role SET perms=? WHERE entity_no=? AND role_code='BM04_TEST'",
                        "[\"biz:booking:manage\",\"biz:customer\",\"biz:order:view\"]",
                        entity);
            }
            get("staff", path);
            sql(
                    false,
                    "UPDATE mch_account SET status='SUSPENDED' WHERE mch_account_no=?",
                    host.path("staffAccount").asString());
            try {
                denied("staff", path, null, null, store);
            } finally {
                sql(
                        false,
                        "UPDATE mch_account SET status='ACTIVE' WHERE mch_account_no=?",
                        host.path("staffAccount").asString());
            }
            get("staff", path);
            sql(
                    true,
                    "UPDATE bty_work_order SET deleted=1 WHERE work_order_no=?",
                    root.path("workOrderNo").asString());
            try {
                denied("merchant", path, null, null, store);
            } finally {
                sql(
                        true,
                        "UPDATE bty_work_order SET deleted=0 WHERE work_order_no=?",
                        root.path("workOrderNo").asString());
            }
            get("merchant", path);
            command("host", "REVOKE", "staff");
            denied("staff", path, null, null, store);
            get("manager", path);
            assertThat(host.path("checkoutBean").asString()).contains("CheckoutPortImpl");
        }

        JsonNode lifecycle(boolean loseResponses) throws Exception {
            long checkoutBefore = count(false, "SELECT COUNT(*) FROM ord_checkout_operation WHERE store_no=?", store);
            var registered =
                    post(
                            "merchant",
                            "/biz/x/beauty/guest-registrations",
                            key(),
                            Map.of(
                                    "contact",
                                    "private-contact-" + key(),
                                    "displayName",
                                    "顾客",
                                    "consent",
                                    true));
            JsonNode root;
            if (loseResponses) {
                root = openGuest(registered);
            } else {
                var preview =
                        post(
                                "merchant",
                                "/biz/x/beauty/appointments/previews",
                                null,
                                Map.of(
                                        "storeNo",
                                        store,
                                        "guestNo",
                                        registered.path("guestNo").asString(),
                                        "items",
                                        List.of(Map.of("skuNo", sku, "qty", 1)),
                                        "requestedAssignments",
                                        List.of(),
                                        "startAt",
                                        Instant.now().plusSeconds(600).toString()));
                var appointment =
                        post(
                                "merchant",
                                "/biz/x/beauty/appointments",
                                key(),
                                Map.of(
                                        "previewNo",
                                        preview.path("previewNo").asString(),
                                        "guestNo",
                                        registered.path("guestNo").asString(),
                                        "sourceRefs",
                                        List.of()));
                var arrival =
                        post(
                                "merchant",
                                "/biz/x/beauty/arrivals",
                                key(),
                                Map.of(
                                        "appointmentNo",
                                        appointment.path("appointmentNo").asString(),
                                        "expectedRevision",
                                        appointment.path("revision").asLong(),
                                        "identityProof",
                                        Map.of(
                                                "guestNo",
                                                registered.path("guestNo").asString(),
                                                "claimToken",
                                                registered.path("claimToken").asString())));
                root =
                        post(
                                "merchant",
                                "/biz/x/beauty/appointments/"
                                        + appointment.path("appointmentNo").asString()
                                        + "/work-orders",
                                key(),
                                Map.of(
                                        "expectedRevision",
                                        arrival.path("revision").asLong(),
                                        "assignments",
                                        List.of()));
            }
            String number = root.path("workOrderNo").asString();
            String reference = reference("consumer");
            post(
                    "merchant",
                    "/biz/x/beauty/guest-registrations/"
                            + registered.path("guestNo").asString()
                            + "/claims",
                    key(),
                    Map.of(
                            "verifiedIdentity",
                            reference,
                            "claimToken",
                            registered.path("claimToken").asString()));
            get("consumer", "/mp/x/beauty/work-orders/" + number);
            denied("otherConsumer", "/mp/x/beauty/work-orders/" + number, null, null, store);
            Object submit =
                    Map.of(
                            "expectedRevision",
                            0,
                            "expectedScopeRevision",
                            root.path("scopeRevision").asLong(),
                            "tasks",
                            List.of(
                                    Map.of(
                                            "taskNo",
                                            root.path("taskNos").get(0).asString(),
                                            "expectedRevision",
                                            0,
                                            "expectedBindingRevision",
                                            0)),
                            "additions",
                            List.of(),
                            "source",
                            Map.of(
                                    "mode",
                                    "PREPARE",
                                    "customerRef",
                                    reference,
                                    "payMode",
                                    "ONLINE",
                                    "activityChoices",
                                    Map.of(),
                                    "lines",
                                    List.of()));
            String submitKey = key(),
                    submitPath = "/biz/x/beauty/work-orders/" + number + "/service-submissions";
            if (loseResponses) {
                fault("platform", "/internal/trade/checkout/bound/prepare");
            }
            var submitted = post("merchant", submitPath, submitKey, submit);
            String submission = submitted.path("submissionNo").asString();
            assertThat(submission).isNotBlank();
            String recoveryPath = "/biz/x/beauty/operations/" + submission + "/recoveries";
            if (loseResponses) {
                assertDropped("platform", "/internal/trade/checkout/bound/prepare");
                fault("beauty", "/internal/beauty/reception/recoverOperation");
                denied("merchant", recoveryPath, null, Map.of(), store);
                assertDropped("beauty", "/internal/beauty/reception/recoverOperation");
            }
            post(loseResponses ? "manager" : "merchant", recoveryPath, null, Map.of());
            var observed = get("merchant", "/biz/x/beauty/service-submissions/" + submission);
            var quote = observed.path("quote");
            assertThat(quote.isObject()).isTrue();
            String operation = quote.path("operation").path("operationNo").asString();
            String checkout = "/mp/checkout/" + operation;
            denied("merchant", checkout + "/confirmations", key(), Map.of(), store);
            var confirmation =
                    post(
                            "consumer",
                            checkout + "/confirmations",
                            key(),
                            Map.of(
                                    "quoteNo",
                                    quote.path("quoteNo").asString(),
                                    "quoteRevision",
                                    quote.path("quoteRevision").asLong(),
                                    "quoteHash",
                                    quote.path("quoteHash").asString()));
            post(
                    "consumer",
                    checkout + "/orders",
                    key(),
                    Map.of("confirmationNo", confirmation.path("confirmationNo").asString()));
            String paymentKey = key();
            var payment =
                    post("consumer", checkout + "/payments", paymentKey, Map.of("channel", "TEST"));
            command("host", "PAY_PAID", payment.path("outTradeNo").asString());
            var paid = post("consumer", checkout + "/payments/lookup", paymentKey, Map.of());
            assertThat(paid.path("phase").asString()).isEqualTo("SUCCEEDED");
            post("merchant", recoveryPath, null, Map.of());
            var bound = get("merchant", "/biz/x/beauty/service-submissions/" + submission);
            assertThat(bound.path("status").asString()).isEqualTo("BOUND");
            assertThat(bound.path("checkoutOperationNo").asString()).isEqualTo(operation);
            var bill = get("merchant", "/biz/x/beauty/work-orders/" + number + "/bill");
            assertThat(bill.path("sources").get(0).path("receivedMinor").asLong()).isEqualTo(101);
            String paymentStatus =
                    scalar(
                            false,
                            "SELECT status FROM stl_payment WHERE payment_no=?",
                            paid.path("paymentNo").asString());
            sql(
                    false,
                    "UPDATE stl_payment SET status='FAILED' WHERE payment_no=?",
                    paid.path("paymentNo").asString());
            try {
                var unknown =
                        get("merchant", "/biz/x/beauty/work-orders/" + number + "/bill")
                                .path("sources")
                                .get(0);
                assertThat(unknown.path("observation").path("state").asString())
                        .isEqualTo("UNKNOWN");
                assertThat(unknown.has("receivedMinor")).isTrue();
                assertThat(unknown.path("receivedMinor").isNull()).isTrue();
                assertThat(unknown.path("outstandingMinor").isNull()).isTrue();
            } finally {
                sql(
                        false,
                        "UPDATE stl_payment SET status=? WHERE payment_no=?",
                        paymentStatus,
                        paid.path("paymentNo").asString());
            }
            assertThat(
                            count(
                                    false,
                                    "SELECT COUNT(*) FROM ord_checkout_operation WHERE"
                                        + " operation_no=?",
                                    operation))
                    .isEqualTo(1);
            assertThat(
                            count(
                                    true,
                                    "SELECT COUNT(*) FROM bty_service_submission WHERE"
                                        + " submission_no=?",
                                    submission))
                    .isEqualTo(1);
            assertThat(
                            get("merchant", "/biz/x/beauty/work-orders/" + number)
                                    .path("tasks")
                                    .get(0)
                                    .path("canStart")
                                    .asBoolean())
                    .isFalse();
            if (loseResponses) {
                assertThat(
                                post("merchant", submitPath, submitKey, submit)
                                        .path("submissionNo")
                                        .asString())
                        .isEqualTo(submission);
                denied("consumer", "/biz/x/beauty/work-orders/" + number, null, null, store);
                get("manager", "/biz/x/beauty/work-orders/" + number);
                get("consumer", "/mp/x/beauty/work-orders/" + number);
            }
            assertThat(
                            get("merchant", "/biz/x/beauty/traces/" + number)
                                    .path("workOrderNo")
                                    .asString())
                    .isEqualTo(number);
            assertThat(
                            get("merchant", "/biz/x/beauty/operations/" + submission)
                                    .path("operationNo")
                                    .asString())
                    .isEqualTo(submission);
            if (!loseResponses) {
                consumerArrival();
            }
            assertThat(count(false, "SELECT COUNT(*) FROM ord_checkout_operation WHERE store_no=?", store))
                    .isEqualTo(checkoutBefore + 1);
            return bound;
        }

        private void consumerArrival() throws Exception {
            var preview =
                    post(
                            "consumer",
                            "/mp/x/beauty/appointments/previews",
                            null,
                            Map.of(
                                    "storeNo",
                                    store,
                                    "items",
                                    List.of(Map.of("skuNo", sku, "qty", 1)),
                                    "startAt",
                                    Instant.now().plusSeconds(7200).toString()));
            var appointment =
                    post(
                            "consumer",
                            "/mp/x/beauty/appointments",
                            key(),
                            Map.of(
                                    "previewNo",
                                    preview.path("previewNo").asString(),
                                    "sourceRefs",
                                    List.of()));
            String challengeKey = key();
            var challenge =
                    post("merchant", "/biz/x/beauty/arrival-challenges", challengeKey, Map.of());
            var replayedChallenge =
                    post("merchant", "/biz/x/beauty/arrival-challenges", challengeKey, Map.of());
            assertThat(replayedChallenge.equals(challenge))
                    .as("arrival challenge original-key result equality")
                    .isTrue();
            assertThat(replayedChallenge.path("challengeNo").asString())
                    .isEqualTo(challenge.path("challengeNo").asString());
            assertThat(replayedChallenge.path("expiresAt").asString())
                    .isEqualTo(challenge.path("expiresAt").asString());
            var member = post("consumer", "/mp/member-code", null, Map.of());
            Object request =
                    Map.of(
                            "appointmentNo",
                            appointment.path("appointmentNo").asString(),
                            "expectedRevision",
                            appointment.path("revision").asLong(),
                            "checkInProof",
                            Map.of(
                                    "memberCode",
                                    member.path("memberCode").asString(),
                                    "arrivalChallenge",
                                    challenge.path("code").asString()));
            denied("otherConsumer", "/mp/x/beauty/arrivals", key(), request, store);
            var arrival = post("consumer", "/mp/x/beauty/arrivals", key(), request);
            assertThat(arrival.path("operationState").asString()).isEqualTo("SUCCEEDED");
        }

        JsonNode openGuest(JsonNode guest) throws Exception {
            return post(
                    "merchant",
                    "/biz/x/beauty/work-orders",
                    key(),
                    Map.of(
                            "storeNo",
                            store,
                            "guestNo",
                            guest.path("guestNo").asString(),
                            "items",
                            List.of(Map.of("skuNo", sku, "qty", 1))));
        }

        private void configure() throws Exception {
            if (CONFIGURED.contains(store)) {
                return;
            }
            post(
                    "merchant",
                    "/biz/x/beauty/store-policies/versions",
                    key(),
                    Map.of(
                            "expectedRevision",
                            0,
                            "validFrom",
                            Instant.now().minusSeconds(60).toString(),
                            "policy",
                            Map.of(
                                    "bookingAheadDays",
                                    30,
                                    "cancelRule",
                                    Map.of(
                                            "freeCancelBeforeMinutes",
                                            0,
                                            "allowCustomerCancel",
                                            true),
                                    "allowedModes",
                                    List.of("PREPAID_SINGLE"))));
            post(
                    "merchant",
                    "/biz/x/beauty/service-definitions/versions",
                    key(),
                    Map.of(
                            "skuNo",
                            sku,
                            "expectedRevision",
                            0,
                            "duration",
                            Map.of("minutes", 30, "bufferBeforeMin", 0, "bufferAfterMin", 0),
                            "requirements",
                            List.of(
                                    Map.of(
                                            "roleCode",
                                            "MAIN",
                                            "resourceType",
                                            "STAFF",
                                            "skillGoodsNo",
                                            host.path("goods").asString(),
                                            "qty",
                                            1,
                                            "sequenceNo",
                                            0,
                                            "offsetMin",
                                            0,
                                            "durationMin",
                                            30,
                                            "selectionRule",
                                            "ANY_QUALIFIED")),
                            "notes",
                            List.of()));
            var personnel =
                    post(
                            "merchant",
                            "/biz/personnel",
                            key(),
                            Map.of(
                                    "displayName",
                                    "BM04 technician",
                                    "assignment",
                                    Map.of(
                                            "expectedRevision",
                                            0,
                                            "storeNo",
                                            store,
                                            "validFrom",
                                            Instant.now().minusSeconds(86400).toString(),
                                            "validTo",
                                            Instant.now().plusSeconds(86400 * 60).toString(),
                                            "publicVisible",
                                            true,
                                            "sortOrder",
                                            0,
                                            "positions",
                                            List.of("TECHNICIAN"),
                                            "skills",
                                            List.of(
                                                    Map.of(
                                                            "goodsNo",
                                                            host.path("goods").asString(),
                                                            "skuNo",
                                                            sku)))));
            JsonNode resource = null;
            for (var row : get("merchant", "/biz/resources?type=STAFF").path("records")) {
                if (row.path("resourceNo").equals(personnel.path("resourceNo"))) {
                    resource = row;
                }
            }
            assertThat(resource).isNotNull();
            post(
                    "merchant",
                    "/biz/schedule-rules",
                    key(),
                    Map.of(
                            "resourceNo",
                            personnel.path("resourceNo").asString(),
                            "expectedRevision",
                            resource.path("revision").asLong(),
                            "timezone",
                            "UTC",
                            "weekdayMask",
                            127,
                            "startMinute",
                            0,
                            "endMinute",
                            1440,
                            "endDayOffset",
                            0,
                            "validFrom",
                            Instant.now().minusSeconds(86400).toString().substring(0, 10),
                            "validTo",
                            Instant.now().plusSeconds(86400 * 40).toString().substring(0, 10),
                            "status",
                            "ACTIVE"));
            CONFIGURED.add(store);
        }

        String reference(String label) throws Exception {
            var member = post(label, "/mp/member-code", null, Map.of());
            return post(
                            "merchant",
                            "/biz/member-code/resolve",
                            null,
                            Map.of(
                                    "storeNo",
                                    store,
                                    "memberCode",
                                    member.path("memberCode").asString()))
                    .path("customerRef")
                    .asString();
        }

        JsonNode get(String label, String path) throws Exception {
            return success(request(label, path, null, null, store));
        }

        JsonNode post(String label, String path, String key, Object body) throws Exception {
            return success(request(label, path, key, body, store));
        }

        void denied(String label, String path, String key, Object body, String requestedStore)
                throws Exception {
            var response = request(label, path, key, body, requestedStore);
            assertThat(
                            response.statusCode() != 200
                                    || json.readTree(response.body()).path("code").asInt() != 0)
                    .isTrue();
        }

        private HttpResponse<String> request(
                String label, String path, String key, Object body, String requestedStore)
                throws Exception {
            var request =
                    HttpRequest.newBuilder(URI.create(host.path("url").asString() + path))
                            .timeout(Duration.ofSeconds(45))
                            .header(
                                    "Authorization",
                                    "Bearer " + host.path("tokens").path(label).asString())
                            .header("X-Store-No", requestedStore);
            if (key != null) {
                request.header("Idempotency-Key", key);
            }
            if (body == null) {
                request.GET();
            } else {
                request.header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            }
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }

        private JsonNode success(HttpResponse<String> response) {
            assertThat(response.statusCode()).isEqualTo(200);
            var tree = json.readTree(response.body());
            assertThat(tree.path("code").asInt()).isZero();
            assertThat(tree.has("data")).isTrue();
            return tree.path("data");
        }

        void fault(String direction, String path) throws Exception {
            command("proxy", "DROP", direction + " " + path);
        }

        void assertDropped(String direction, String path) throws Exception {
            var events = json.readTree(Files.readString(directory.resolve("proxy-events.json")));
            boolean found = false;
            for (var event : events) {
                found |=
                        direction.equals(event.path("direction").asString())
                                && path.equals(event.path("path").asString())
                                && event.path("dropped").asBoolean()
                                && event.path("status").asInt() == 200;
            }
            assertThat(found).isTrue();
        }

        void command(String target, String action, String argument) throws Exception {
            String id = key();
            Path temporary =
                    Files.createTempFile(
                            directory,
                            "command-",
                            ".json",
                            PosixFilePermissions.asFileAttribute(
                                    PosixFilePermissions.fromString("rw-------")));
            Files.writeString(
                    temporary,
                    json.writeValueAsString(
                            Map.of("id", id, "action", action, "argument", argument)));
            Files.move(
                    temporary,
                    directory.resolve(target + "-command.json"),
                    StandardCopyOption.ATOMIC_MOVE);
            for (int attempt = 0; attempt < 400; attempt++) {
                Path response = directory.resolve(target + "-command-result.json");
                if (Files.exists(response)) {
                    var result = json.readTree(Files.readString(response));
                    if (id.equals(result.path("id").asString())) {
                        assertThat(result.path("completed").asBoolean()).isTrue();
                        return;
                    }
                }
                Thread.sleep(50);
            }
            throw new IllegalStateException("owned fixture command timed out");
        }

        void sql(boolean beauty, String statement, Object... arguments) throws Exception {
            try (var connection =
                            DriverManager.getConnection(
                                    required(
                                            beauty
                                                    ? "BEAUTY_BM04_BEAUTY_JDBC"
                                                    : "BEAUTY_BM04_HOST_JDBC"),
                                    "root",
                                    required("MYSQL_PWD"));
                    var query = connection.prepareStatement(statement)) {
                for (int i = 0; i < arguments.length; i++) {
                    query.setObject(i + 1, arguments[i]);
                }
                assertThat(query.executeUpdate()).isPositive();
            }
        }

        String scalar(boolean beauty, String statement, String argument) throws Exception {
            try (var connection =
                            DriverManager.getConnection(
                                    required(
                                            beauty
                                                    ? "BEAUTY_BM04_BEAUTY_JDBC"
                                                    : "BEAUTY_BM04_HOST_JDBC"),
                                    "root",
                                    required("MYSQL_PWD"));
                    var query = connection.prepareStatement(statement)) {
                query.setString(1, argument);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    return rows.getString(1);
                }
            }
        }

        long count(boolean beauty, String statement, String argument) throws Exception {
            try (var connection =
                            DriverManager.getConnection(
                                    required(
                                            beauty
                                                    ? "BEAUTY_BM04_BEAUTY_JDBC"
                                                    : "BEAUTY_BM04_HOST_JDBC"),
                                    "root",
                                    required("MYSQL_PWD"));
                    var query = connection.prepareStatement(statement)) {
                query.setString(1, argument);
                try (var rows = query.executeQuery()) {
                    assertThat(rows.next()).isTrue();
                    return rows.getLong(1);
                }
            }
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException("missing fixture environment " + name);
            }
            return value;
        }

        private static String key() {
            return UUID.randomUUID().toString();
        }
    }
}
