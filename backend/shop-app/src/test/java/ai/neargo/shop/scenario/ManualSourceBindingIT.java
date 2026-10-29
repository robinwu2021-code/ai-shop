package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import ai.neargo.auth.store.SubjectKind;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStore.SessionData;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Public no-C opening and manual binding must retain the actual common identity and original
 * source.
 */
@EnabledIfSystemProperty(named = "beauty.manual.source.test.native", matches = "true")
class ManualSourceBindingIT extends ManualSourceHostFixture {
    @Test
    void createdManualPrepareRecoveryRequiresCurrentSourceFacts() {
        String work = open(Map.of("memberNo", member), "review-recovery");
        var root =
                jdbc.queryForMap(
                        "SELECT version,scope_revision FROM bty_work_order WHERE work_order_no=?",
                        work);
        var task =
                jdbc.queryForMap(
                        "SELECT task_no,version,binding_revision FROM bty_service_task WHERE work_order_no=?",
                        work);
        var prepared =
                successful(
                        http(
                                "POST",
                                "/biz/x/beauty/work-orders/" + work + "/service-submissions",
                                Map.of(
                                        "expectedRevision",
                                        root.get("version"),
                                        "expectedScopeRevision",
                                        root.get("scope_revision"),
                                        "tasks",
                                        List.of(
                                                Map.of(
                                                        "taskNo",
                                                        task.get("task_no"),
                                                        "expectedRevision",
                                                        task.get("version"),
                                                        "expectedBindingRevision",
                                                        task.get("binding_revision"))),
                                        "additions",
                                        List.of(),
                                        "source",
                                        Map.of(
                                                "mode",
                                                "MANUAL_PREPARE",
                                                "payMode",
                                                "OFFLINE",
                                                "activityChoices",
                                                Map.of(),
                                                "lines",
                                                List.of(),
                                                "counterCustomer",
                                                new ManualServiceAdmissionPort.CounterCustomerRef(
                                                        "USER", member, null, null))),
                                "review-prepare-" + work,
                                false));
        String number = prepared.path("submissionNo").asText();
        var quote =
                json.readValue(
                        prepared.path("counterQuote").toString(),
                        ManualServiceAdmissionPort.CounterCheckoutQuote.class);
        String frozen =
                jdbc.queryForObject(
                        "SELECT frozen_command FROM bty_service_submission WHERE submission_no=?",
                        String.class,
                        number);
        long revision =
                jdbc.queryForObject(
                        "SELECT version FROM bty_service_submission WHERE submission_no=?",
                        Long.class,
                        number);
        var request = Map.of("expectedRevision", revision, "reason", "inspect original source");
        String path = "/biz/x/beauty/service-submissions/" + number + "/recovery-requests";
        var precreation =
                asMerchant(
                        () ->
                                manual.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                quote.quote().operation(), null)));
        assertThat(precreation.orderNo()).isNull();
        try {
            doThrow(BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE))
                    .when(manual)
                    .source(any(), any());
            var beforeCreation =
                    successful(http("POST", path, request, "review-before-" + work, false));
            assertThat(successful(http("POST", path, request, "review-before-" + work, false)))
                    .isEqualTo(beforeCreation);
        } finally {
            doCallRealMethod().when(manual).source(any(), any());
        }
        assertThat(recoveryActions(number)).isOne();
        var accepted =
                asMerchant(
                        () ->
                                manual.acceptCounter(
                                        scope,
                                        new ManualServiceAdmissionPort.AcceptCounterReq(
                                                quote.quote().operation(),
                                                "review-accept-" + work,
                                                quote.quote().quoteNo(),
                                                quote.quote().quoteRevision(),
                                                quote.quote().quoteHash(),
                                                quote.party().identityRevision(),
                                                testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                                "MERCHANT_RECORDED_IN_PERSON")));
        var created =
                asMerchant(
                        () ->
                                manual.placeCounter(
                                        scope,
                                        new ManualServiceAdmissionPort.PlaceCounterReq(
                                                quote.quote().operation(),
                                                accepted.confirmationNo(),
                                                "review-place-" + work)));
        var genuine =
                asMerchant(
                        () ->
                                manual.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                quote.quote().operation(), created.subOrderNo())));
        assertThat(genuine.operation()).isEqualTo(quote.quote().operation());
        assertThat(genuine.orderNo()).isEqualTo(created.orderNo());
        assertThat(genuine.party()).isEqualTo(quote.party());
        assertThat(genuine.purchaseAuthority()).isEqualTo("MERCHANT_RECORDED_IN_PERSON");
        var currentQuote =
                asMerchant(() -> manual.readCounterQuote(scope, quote.quote().operation()));
        assertThat(currentQuote.quote().operation()).isEqualTo(quote.quote().operation());
        assertThat(currentQuote.quote().quoteHash()).isEqualTo(quote.quote().quoteHash());
        assertThat(currentQuote.quote().phase()).isEqualTo("CREATED");
        assertThat(currentQuote.party()).isEqualTo(quote.party());
        System.out.println("TASK67_R1_REAL_CREATED_QUOTE_SOURCE " + created.orderNo());
        try {
            doThrow(BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE))
                    .when(manual)
                    .source(any(), any());
            var unavailable = http("POST", path, request, "review-unavailable-" + work, false);
            assertThat(recoveryActions(number))
                    .as("unavailable CREATED source must not add a durable recovery acceptance")
                    .isOne();
            assertThat(unavailable.path("code").asInt()).isNotZero();
        } finally {
            doCallRealMethod().when(manual).source(any(), any());
        }
        var registered = guest("review-negative");
        var foreign =
                asMerchant(
                        () ->
                                guests.verify(
                                        new GuestRegistrationPort.Scope(
                                                entity,
                                                store,
                                                new DelegatedIdentity(
                                                        "MAIN", "MERCHANT", "MCH", actor)),
                                        registered.guestNo(),
                                        registered.claimToken()));
        var guestParty =
                new ManualServiceAdmissionPort.CustomerParty(
                        "GUEST", null, foreign.guestNo(), foreign.revision());
        for (var invalid :
                List.of(
                        negativeManualReply(genuine, guestParty, genuine.purchaseAuthority()),
                        negativeManualReply(genuine, genuine.party(), "CONSUMER_SESSION"))) {
            try {
                doAnswer(
                                invocation -> {
                                    assertThat(
                                                    TransactionSynchronizationManager
                                                            .isActualTransactionActive())
                                            .isFalse();
                                    return invalid;
                                })
                        .when(manual)
                        .source(any(), any());
                var rejected =
                        http(
                                "POST",
                                path,
                                request,
                                "review-contradictory-"
                                        + work
                                        + invalid.party().kind()
                                        + invalid.purchaseAuthority(),
                                false);
                assertThat(recoveryActions(number)).isOne();
                assertThat(rejected.path("code").asInt()).isNotZero();
            } finally {
                doCallRealMethod().when(manual).source(any(), any());
            }
        }
        var valid = successful(http("POST", path, request, "review-valid-" + work, false));
        assertThat(successful(http("POST", path, request, "review-valid-" + work, false)))
                .isEqualTo(valid);
        assertThat(recoveryActions(number)).isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT frozen_command FROM bty_service_submission WHERE submission_no=?",
                                String.class,
                                number))
                .isEqualTo(frozen);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM ord_checkout_operation WHERE entity_no=? AND operation_no=?",
                                Integer.class,
                                entity,
                                quote.quote().operation().operationNo()))
                .isOne();
        System.out.println("TASK67_R1_RECOVERY_FACTS_GATED " + number);
    }

    private int recoveryActions(String number) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM bty_action_log WHERE tenant_no='MAIN' AND entity_no=?"
                        + " AND store_no=? AND aggregate_no=? AND action='RECOVERY_REQUESTED'",
                Integer.class,
                entity,
                store,
                number);
    }

    private ManualServiceAdmissionPort.ManualSourceFacts negativeManualReply(
            ManualServiceAdmissionPort.ManualSourceFacts actual,
            ManualServiceAdmissionPort.CustomerParty party,
            String authority) {
        return new ManualServiceAdmissionPort.ManualSourceFacts(
                actual.operation(),
                actual.orderNo(),
                actual.subOrderNo(),
                party,
                actual.entityNo(),
                actual.storeNo(),
                actual.admissionKind(),
                actual.manualObservationRevision(),
                actual.manualObservationHash(),
                actual.orderRevision(),
                actual.admissionRevision(),
                actual.admissionState(),
                actual.lines(),
                actual.observedAt(),
                actual.dueMinor(),
                actual.currency(),
                actual.declaration(),
                authority);
    }

    @Test
    void startupRejectsLiveTriggerLiteralDrift() throws Exception {
        Object validator = type("config.BeautySchemaValidator").getConstructor().newInstance();
        String body =
                jdbc.queryForObject(
                        "SELECT ACTION_STATEMENT FROM information_schema.TRIGGERS"
                                + " WHERE TRIGGER_SCHEMA=DATABASE()"
                                + " AND TRIGGER_NAME='trg_bty_source_observation_domain_guard'",
                        String.class);
        call(validator, "initialize", dataSource, "validate");
        String prefix =
                "CREATE TRIGGER trg_bty_source_observation_domain_guard"
                        + " BEFORE UPDATE ON bty_source_observation FOR EACH ROW ";
        for (String changed :
                List.of(
                        body.replaceFirst("'MANUAL_OFFLINE'", "'manual_offline'"),
                        body.replace("Immutable beauty manual", "Immutable  beauty manual"))) {
            assertThat(changed).isNotEqualTo(body);
            jdbc.execute("DROP TRIGGER trg_bty_source_observation_domain_guard");
            try {
                jdbc.execute(prefix + changed);
                assertThatThrownBy(() -> call(validator, "initialize", dataSource, "validate"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("trigger differs");
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS trg_bty_source_observation_domain_guard");
                jdbc.execute(prefix + body);
            }
            call(validator, "initialize", dataSource, "validate");
        }
        System.out.println("TASK67_R1_TRIGGER_LITERAL_DRIFT_REJECTED");
    }

    @Test
    void fundedEvidenceRejectsManualAdoptionBeforeAndAfterAllocation() throws Exception {
        String work = open(Map.of("memberNo", member), "funded-evidence");
        jdbc.update("UPDATE mch_store SET offline_pay_enabled=1 WHERE store_no=?", store);
        jdbc.update(
                "UPDATE prd_goods SET pay_modes='[\"ONLINE\",\"OFFLINE\"]' WHERE goods_no=?",
                goods);
        String scopeHash =
                json.readTree(
                                jdbc.queryForObject(
                                        "SELECT frozen_scope FROM bty_work_order_context WHERE"
                                                + " entity_no=? AND store_no=? AND work_order_no=?",
                                        String.class,
                                        entity,
                                        store,
                                        work))
                        .path("scopeHash")
                        .asText();
        var operation = new CheckoutPort.OperationRef("BEAUTY", "funded-evidence-" + sku);
        var quote =
                asMerchant(
                        () ->
                                checkout.prepare(
                                        scope,
                                        new CheckoutPort.PrepareCheckoutReq(
                                                operation,
                                                "funded-prepare-" + sku,
                                                realConsumerReference(),
                                                1,
                                                scopeHash,
                                                List.of(
                                                        new CheckoutPort.CheckoutLineReq(
                                                                "funded-line", sku, 1)),
                                                "OFFLINE",
                                                null,
                                                Map.of(),
                                                null)));
        var consent =
                asBuyer(
                        () ->
                                checkout.confirm(
                                        new CheckoutPort.ConfirmCheckoutReq(
                                                operation,
                                                quote.quoteNo(),
                                                quote.quoteRevision(),
                                                quote.quoteHash(),
                                                "funded-confirm-" + sku)));
        var created =
                asBuyer(
                        () ->
                                checkout.place(
                                        new CheckoutPort.PlaceCheckoutReq(
                                                operation,
                                                consent.confirmationNo(),
                                                "funded-place-" + sku)));
        assertThat(created.phase()).isEqualTo("CREATED");
        successful(
                http(
                        "POST",
                        "/biz/checkout/" + operation.operationNo() + "/offline-receipts",
                        Map.of(
                                "method",
                                "CASH",
                                "amountMinor",
                                101L,
                                "currency",
                                "CNY",
                                "receivedAt",
                                Instant.now().truncatedTo(ChronoUnit.MILLIS).toString(),
                                "note",
                                "Actual funded original cash"),
                        "funded-cash-" + sku,
                        false));
        var facts =
                asMerchant(
                        () ->
                                checkout.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                operation, created.subOrderNo())));
        assertThat(facts.financialState()).isEqualTo("PAID");
        assertThat(facts.sourceRevision()).isPositive();
        var canonical =
                type("service.impl.ServiceSourceObservation$Canonical")
                        .getConstructors()[0]
                        .newInstance(
                                entity,
                                store,
                                buyer,
                                operation.operationNo(),
                                facts.orderNo(),
                                facts.subOrderNo());
        var forged =
                type("service.impl.ServiceSourceObservation$ManualCanonical")
                        .getConstructors()[0]
                        .newInstance(
                                entity,
                                store,
                                new ManualServiceAdmissionPort.CustomerParty(
                                        "USER", buyer, null, (Long) null),
                                operation.operationNo(),
                                facts.orderNo(),
                                facts.subOrderNo());
        var observed = asMerchant(() -> call(observationOwner(), "observe", canonical, actor));
        assertThat(call(observed, "ready")).isEqualTo(true);
        assertThat(allocationCount(facts.subOrderNo())).isZero();
        rejectFundedAdoptionUnchanged(forged, facts.subOrderNo());
        asMerchant(
                () ->
                        call(
                                observationOwner(),
                                "invalidateKnown",
                                entity,
                                store,
                                buyer,
                                operation.operationNo(),
                                actor));
        var unknown = observationRow(facts.subOrderNo());
        assertThat(unknown).containsEntry("state", "UNKNOWN");
        assertThat(unknown.get("source_revision")).isNotNull();
        assertThat(unknown.get("verified_revision")).isNotNull();
        assertThat(allocationCount(facts.subOrderNo())).isZero();
        rejectFundedAdoptionUnchanged(forged, facts.subOrderNo());
        var bound =
                successful(
                        http(
                                "POST",
                                "/biz/x/beauty/work-orders/" + work + "/service-submissions",
                                existingRequest(work, facts),
                                "funded-existing-" + sku,
                                false));
        assertThat(bound.path("status").asText()).isEqualTo("BOUND");
        assertThat(allocationCount(facts.subOrderNo())).isEqualTo(2);
        rejectFundedAdoptionUnchanged(forged, facts.subOrderNo());
        verify(manual, never()).source(any(), any());
        System.out.println("TASK67_FUNDED_DOMAIN_UNCHANGED " + facts.subOrderNo());
    }

    private int allocationCount(String source) {
        return jdbc.queryForObject(
                "SELECT (SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=? AND"
                        + " source_no=?)+(SELECT COUNT(*) FROM bty_source_allocation_guard WHERE"
                        + " entity_no=? AND source_no=?)",
                Integer.class,
                entity,
                source,
                entity,
                source);
    }

    private void rejectFundedAdoptionUnchanged(Object canonical, String source) {
        var before = observationRow(source);
        assertThatThrownBy(
                        () ->
                                asMerchant(
                                        () ->
                                                call(
                                                        observationOwner(),
                                                        "observeManual",
                                                        canonical,
                                                        actor)))
                .isInstanceOfSatisfying(
                        BizException.class,
                        failure -> {
                            assertThat(failure.errorCode()).isEqualTo(ErrorCode.CONFLICT);
                            assertThat(call(failure, "reasonCode"))
                                    .isEqualTo("SOURCE_DOMAIN_CONFLICT");
                        });
        assertThat(observationRow(source)).isEqualTo(before);
    }

    @Test
    void lateAdmittedReplyCannotCompleteNewerUnavailableTicket() throws Exception {
        String work = open(Map.of("memberNo", member), "same-pair-ticket");
        var original =
                admittedCounter(
                        work,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        "same-pair-ticket");
        var canonical = manualCanonical(original.source());
        asMerchant(() -> call(observationOwner(), "observeManual", canonical, actor));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var once = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            var actual = invocation.callRealMethod();
                            if (once.compareAndSet(false, true)) {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isFalse();
                                entered.countDown();
                                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                            }
                            return actual;
                        })
                .when(manual)
                .source(any(), any());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var old =
                    worker.submit(
                            () ->
                                    asMerchant(
                                            () ->
                                                    call(
                                                            observationOwner(),
                                                            "observeManual",
                                                            canonical,
                                                            actor)));
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            try {
                doThrow(BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE))
                        .when(manual)
                        .source(any(), any());
                var current =
                        asMerchant(
                                () -> call(observationOwner(), "observeManual", canonical, actor));
                assertThat(call(current, "manualReady")).isEqualTo(false);
                var newer = observationRow(original.source().subOrderNo());
                assertThat(newer)
                        .containsEntry("manual_state", "UNKNOWN")
                        .containsEntry(
                                "manual_revision", original.source().manualObservationRevision())
                        .containsEntry("manual_hash", original.source().manualObservationHash())
                        .containsEntry(
                                "manual_verified_hash", original.source().manualObservationHash());
                doCallRealMethod().when(manual).source(any(), any());
                release.countDown();
                assertThat(call(old.get(20, TimeUnit.SECONDS), "manualReady")).isEqualTo(false);
                assertThat(observationRow(original.source().subOrderNo())).isEqualTo(newer);
                System.out.println(
                        "TASK67_SAME_PAIR_STALE_TICKET_IGNORED " + original.source().subOrderNo());
            } finally {
                doCallRealMethod().when(manual).source(any(), any());
                release.countDown();
            }
        }
    }

    @Test
    void preM2UnknownManualPlaceholderAdoptsSameIdentityAfterV9() throws Exception {
        ManualSourceV8Driver.verifyUpgrade(json);
    }

    @Test
    void noConsumerReceptionUsesCanonicalMemberSelector() throws Exception {
        actualOrigins();
        var resolved =
                asMerchant(
                        () ->
                                customers.resolveCounterReference(
                                        new EntitlementContext(
                                                "MAIN",
                                                actor,
                                                "member-open-" + sku,
                                                IdempotencyScope.requestHash(member)),
                                        new CustomerIdentityPort.CounterReferenceQuery(
                                                entity, store, member)));
        assertThat(resolved.userNo()).isEqualTo(buyer);
        assertThat(reference).as("no consumer proof was issued by fixture setup").isNull();
        var oldFour =
                dto(
                        "OpenWorkOrderReq",
                        null,
                        "LEGACY-S",
                        List.of(dto("AppointmentItemReq", "LEGACY-K", 1)),
                        null,
                        "LEGACY-G",
                        null,
                        List.of(),
                        null);
        String oldBytes = json.writeValueAsString(oldFour);
        assertThat(oldBytes)
                .isEqualTo(
                        "{\"customerRef\":null,\"storeNo\":\"LEGACY-S\",\"items\":[{\"skuNo\":"
                            + "\"LEGACY-K\",\"qty\":1}],\"appointmentNo\":null,\"guestNo\":\"LEGACY-G\","
                            + "\"expectedRevision\":null,\"assignments\":[],\"startAt\":null}");
        var oldShort =
                dto(
                        "OpenWorkOrderReq",
                        "LEGACY-REF",
                        "LEGACY-S",
                        List.of(dto("AppointmentItemReq", "LEGACY-K", 1)),
                        null);
        assertThat(json.writeValueAsString(oldShort))
                .isEqualTo(
                        json.writeValueAsString(
                                dto(
                                        "OpenWorkOrderReq",
                                        "LEGACY-REF",
                                        "LEGACY-S",
                                        List.of(dto("AppointmentItemReq", "LEGACY-K", 1)),
                                        null,
                                        null,
                                        null,
                                        List.of(),
                                        null)));
        System.out.println("TASK67_LEGACY_OPEN_BYTES " + oldBytes);
        System.out.println("TASK67_LEGACY_OPEN_HASH " + IdempotencyScope.requestHash(oldBytes));
        var guest =
                asMerchant(
                        () ->
                                guests.register(
                                        new GuestRegistrationPort.Scope(
                                                entity,
                                                store,
                                                new DelegatedIdentity(
                                                        "MAIN", "MERCHANT", "MCH", actor)),
                                        new GuestRegistrationPort.RegisterCommand(
                                                null, null, true, "guest-open-" + sku)));
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        var pool = tokens.of(Realm.MERCHANT);
        String token =
                pool.issue(new SessionData(LoginUser.merchant(actor, "manual source cashier")));
        try {
            var guestResponse =
                    mvc.perform(
                                    post("/biz/x/beauty/work-orders")
                                            .header("Authorization", "Bearer " + token)
                                            .header("X-Store-No", store)
                                            .header("Idempotency-Key", "open-guest-" + sku)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(
                                                    json.writeValueAsString(
                                                            dto(
                                                                    "OpenWorkOrderReq",
                                                                    null,
                                                                    store,
                                                                    List.of(
                                                                            dto(
                                                                                    "AppointmentItemReq",
                                                                                    sku,
                                                                                    1)),
                                                                    null,
                                                                    guest.guestNo(),
                                                                    null,
                                                                    List.of(),
                                                                    null))))
                            .andReturn()
                            .getResponse();
            assertThat(guestResponse.getStatus())
                    .as("existing GUEST endpoint works before member assertion")
                    .isBetween(200, 299);
            var guestBody = json.readTree(guestResponse.getContentAsString());
            assertThat(guestBody.path("code").asInt(-1))
                    .as(guestResponse.getContentAsString())
                    .isZero();
            assertThat(guestBody.path("data").path("workOrderNo").asText()).isNotBlank();
            String body =
                    json.writeValueAsString(
                            Map.of(
                                    "memberNo",
                                    member,
                                    "storeNo",
                                    store,
                                    "items",
                                    List.of(Map.of("skuNo", sku, "qty", 1))));
            var response =
                    mvc.perform(
                                    post("/biz/x/beauty/work-orders")
                                            .header("Authorization", "Bearer " + token)
                                            .header("X-Store-No", store)
                                            .header("Idempotency-Key", "open-member-" + sku)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(body))
                            .andReturn()
                            .getResponse();
            assertThat(response.getStatus())
                    .as("canonical member reception must accept no-C memberNo JSON")
                    .isBetween(200, 299);
            var returned = json.readTree(response.getContentAsString());
            assertThat(returned.path("code").asInt(-1))
                    .as(
                            "canonical member reception must open successfully: "
                                    + response.getContentAsString())
                    .isZero();
            String number = returned.path("data").path("workOrderNo").asText();
            assertThat(number).isNotBlank();
            assertThat(
                            jdbc.queryForMap(
                                    "SELECT identity_kind,identity_ref FROM bty_work_order_context"
                                        + " WHERE entity_no=? AND store_no=? AND work_order_no=?",
                                    entity,
                                    store,
                                    number))
                    .containsEntry("identity_kind", "USER")
                    .containsEntry("identity_ref", buyer);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT customer_ref FROM bty_work_order WHERE work_order_no=?",
                                    String.class,
                                    number))
                    .isEqualTo("USER:" + buyer);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM bty_service_task WHERE work_order_no=?",
                                    Integer.class,
                                    number))
                    .isOne();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_operation WHERE entity_no=?",
                                    Integer.class,
                                    entity))
                    .as("reception creates no purchase operation")
                    .isZero();
        } finally {
            pool.revoke(token);
        }
    }

    @Test
    void manualUserAndGuestBindExistingOrdinaryLines() throws Exception {
        actualOrigins();
        var guest =
                asMerchant(
                        () ->
                                guests.register(
                                        new GuestRegistrationPort.Scope(
                                                entity,
                                                store,
                                                new DelegatedIdentity(
                                                        "MAIN", "MERCHANT", "MCH", actor)),
                                        new GuestRegistrationPort.RegisterCommand(
                                                null, null, true, "guest-bind-" + sku)));
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
        var pool = tokens.of(Realm.MERCHANT);
        String token =
                pool.issue(new SessionData(LoginUser.merchant(actor, "manual source cashier")));
        try {
            String userNumber = openForBinding(mvc, token, Map.of("memberNo", member), "user");
            String guestNumber =
                    openForBinding(mvc, token, Map.of("guestNo", guest.guestNo()), "guest");
            // Both actual source owners must be ADMITTED before the desired beauty branch is
            // exercised.
            var userOriginal =
                    admittedCounter(
                            userNumber,
                            new ManualServiceAdmissionPort.CounterCustomerRef(
                                    "USER", member, null, null),
                            "user");
            var guestOriginal =
                    admittedCounter(
                            guestNumber,
                            new ManualServiceAdmissionPort.CounterCustomerRef(
                                    "GUEST", null, guest.guestNo(), guest.claimToken()),
                            "guest");
            assertThat(userOriginal.source().party().userNo()).isEqualTo(buyer);
            assertThat(guestOriginal.source().party().userNo()).isNull();
            assertThat(guestOriginal.source().party().guestNo()).isEqualTo(guest.guestNo());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=?",
                                    Integer.class,
                                    entity))
                    .isZero();
            for (var original : List.of(userOriginal, guestOriginal)) {
                var source = original.source();
                var line = source.lines().getFirst();
                var root =
                        jdbc.queryForMap(
                                "SELECT version,scope_revision FROM bty_work_order"
                                        + " WHERE entity_no=? AND store_no=? AND work_order_no=?",
                                entity,
                                store,
                                original.workOrder());
                var task =
                        jdbc.queryForMap(
                                "SELECT t.task_no,t.version,t.binding_revision FROM"
                                    + " bty_service_task t JOIN bty_work_order w ON"
                                    + " w.tenant_no=t.tenant_no AND w.entity_no=t.entity_no AND"
                                    + " w.work_order_no=t.work_order_no WHERE t.tenant_no='MAIN'"
                                    + " AND t.entity_no=? AND w.store_no=? AND t.work_order_no=?"
                                    + " AND t.deleted=0 AND w.deleted=0",
                                entity,
                                store,
                                original.workOrder());
                String taskNo = (String) task.get("task_no");
                var request =
                        Map.of(
                                "expectedRevision",
                                root.get("version"),
                                "expectedScopeRevision",
                                root.get("scope_revision"),
                                "tasks",
                                List.of(
                                        Map.of(
                                                "taskNo",
                                                taskNo,
                                                "expectedRevision",
                                                task.get("version"),
                                                "expectedBindingRevision",
                                                task.get("binding_revision"))),
                                "additions",
                                List.of(),
                                "source",
                                Map.of(
                                        "mode",
                                        "MANUAL_EXISTING",
                                        "activityChoices",
                                        Map.of(),
                                        "checkoutOperationNo",
                                        source.operation().operationNo(),
                                        "subOrderNo",
                                        source.subOrderNo(),
                                        "sourceRevision",
                                        source.manualObservationRevision(),
                                        "sourceHash",
                                        source.manualObservationHash(),
                                        "lines",
                                        List.of(
                                                Map.of(
                                                        "selectionRef",
                                                        taskNo,
                                                        "sourceLineNo",
                                                        line.sourceLineNo()))));
                var response =
                        mvc.perform(
                                        post("/biz/x/beauty/work-orders/"
                                                        + original.workOrder()
                                                        + "/service-submissions")
                                                .header("Authorization", "Bearer " + token)
                                                .header("X-Store-No", store)
                                                .header(
                                                        "Idempotency-Key",
                                                        "bind-" + source.operation().operationNo())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json.writeValueAsString(request)))
                                .andReturn()
                                .getResponse();
                assertThat(response.getStatus())
                        .as("MANUAL_EXISTING uses the existing public submission endpoint")
                        .isBetween(200, 299);
                var returned = json.readTree(response.getContentAsString());
                assertThat(returned.path("code").asInt(-1))
                        .as(
                                "ADMITTED "
                                        + source.party().kind()
                                        + " manual source must bind: "
                                        + response.getContentAsString())
                        .isZero();
                var submission = returned.path("data");
                assertThat(submission.path("status").asText()).isEqualTo("BOUND");
                assertThat(submission.path("source").path("canStart").asBoolean(true)).isFalse();
                assertThat(submission.path("source").path("admissionKind").asText())
                        .isEqualTo("MANUAL_OFFLINE");
                assertThat(submission.path("source").path("manualState").asText())
                        .isEqualTo("READY");
                assertThat(submission.path("subOrderNo").asText()).isEqualTo(source.subOrderNo());
                var binding =
                        jdbc.queryForMap(
                                "SELECT"
                                    + " source_kind,source_no,source_line_ref,qty,source_revision,guard_no"
                                    + " FROM bty_trade_binding WHERE entity_no=? AND task_no=?",
                                entity,
                                taskNo);
                assertThat(binding)
                        .containsEntry("source_kind", "STORE_ORDER")
                        .containsEntry("source_no", source.subOrderNo())
                        .containsEntry("source_line_ref", line.sourceLineNo())
                        .containsEntry("source_revision", null);
                assertThat((BigDecimal) binding.get("qty")).isEqualByComparingTo(BigDecimal.ONE);
                var guard =
                        jdbc.queryForMap(
                                "SELECT source_no,source_line_ref,source_qty,allocated_qty FROM"
                                        + " bty_source_allocation_guard WHERE entity_no=? AND"
                                        + " guard_no=?",
                                entity,
                                binding.get("guard_no"));
                assertThat(guard)
                        .containsEntry("source_no", source.subOrderNo())
                        .containsEntry("source_line_ref", line.sourceLineNo());
                assertThat((BigDecimal) guard.get("source_qty"))
                        .isEqualByComparingTo(BigDecimal.ONE);
                assertThat((BigDecimal) guard.get("allocated_qty"))
                        .isEqualByComparingTo(BigDecimal.ONE);
                var manualRow =
                        jdbc.queryForMap(
                                "SELECT"
                                        + " admission_kind,customer_kind,buyer_no,guest_no,identity_revision,"
                                        + "source_revision,source_hash,verified_revision,verified_hash,"
                                        + "verified_lines,manual_revision,manual_hash"
                                        + " FROM bty_source_observation WHERE tenant_no='MAIN' AND"
                                        + " entity_no=? AND store_no=? AND source_no=?",
                                entity,
                                store,
                                source.subOrderNo());
                assertThat(manualRow)
                        .containsEntry("admission_kind", "MANUAL_OFFLINE")
                        .containsEntry("customer_kind", source.party().kind())
                        .containsEntry("buyer_no", source.party().userNo())
                        .containsEntry("guest_no", source.party().guestNo())
                        .containsEntry("identity_revision", source.party().identityRevision())
                        .containsEntry("source_revision", null)
                        .containsEntry("source_hash", null)
                        .containsEntry("verified_revision", null)
                        .containsEntry("verified_hash", null)
                        .containsEntry("verified_lines", null)
                        .containsEntry("manual_revision", source.manualObservationRevision())
                        .containsEntry("manual_hash", source.manualObservationHash());
                System.out.println(
                        "TASK67_MANUAL_BIND "
                                + source.party().kind()
                                + " "
                                + source.subOrderNo()
                                + " "
                                + line.sourceLineNo());
            }
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=?",
                                    Integer.class,
                                    entity))
                    .isEqualTo(2);
        } finally {
            pool.revoke(token);
        }
    }

    @Test
    void twoWorkOrdersCannotAllocateLastUnit() throws Exception {
        String first = open(Map.of("memberNo", member), "last-a");
        String second = open(Map.of("memberNo", member), "last-b");
        var original =
                admittedCounter(
                        first,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        "last");
        var a = existingRequest(first, original.source());
        var b = existingRequest(second, original.source());
        var start = new CountDownLatch(1);
        var bothReplies = new CountDownLatch(2);
        var sourceCalls = ThreadLocal.withInitial(() -> 0);
        var ownerThreads = ConcurrentHashMap.<Long>newKeySet();
        doAnswer(
                        invocation -> {
                            var facts = invocation.callRealMethod();
                            int index = sourceCalls.get() + 1;
                            sourceCalls.set(index);
                            if (index == 2) {
                                assertThat(
                                                TransactionSynchronizationManager
                                                        .isActualTransactionActive())
                                        .isFalse();
                                ownerThreads.add(Thread.currentThread().threadId());
                                bothReplies.countDown();
                                assertThat(bothReplies.await(20, TimeUnit.SECONDS)).isTrue();
                            }
                            return facts;
                        })
                .when(manual)
                .source(any(), any());
        try (var workers = Executors.newFixedThreadPool(2)) {
            var left =
                    workers.submit(
                            () -> {
                                start.await();
                                return http(
                                        "POST",
                                        "/biz/x/beauty/work-orders/"
                                                + first
                                                + "/service-submissions",
                                        a,
                                        "last-a-" + sku,
                                        false);
                            });
            var right =
                    workers.submit(
                            () -> {
                                start.await();
                                return http(
                                        "POST",
                                        "/biz/x/beauty/work-orders/"
                                                + second
                                                + "/service-submissions",
                                        b,
                                        "last-b-" + sku,
                                        false);
                            });
            start.countDown();
            var outcomes = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
            assertThat(
                            outcomes.stream()
                                    .filter(
                                            x ->
                                                    "BOUND"
                                                            .equals(
                                                                    x.path("data")
                                                                            .path("status")
                                                                            .asText()))
                                    .count())
                    .isOne();
            assertThat(ownerThreads).hasSize(2);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_trade_binding WHERE entity_no=? AND"
                                        + " source_no=?",
                                Integer.class,
                                entity,
                                original.source().subOrderNo()))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT allocated_qty FROM bty_source_allocation_guard WHERE"
                                        + " entity_no=? AND source_no=?",
                                BigDecimal.class,
                                entity,
                                original.source().subOrderNo()))
                .isEqualByComparingTo(BigDecimal.ONE);
    }

    @Test
    void m1ConsumerManualExistingUsesSourceFactsWithoutCounterQuote() {
        String workOrder = open(Map.of("memberNo", member), "m1");
        var original = admittedM1(workOrder, "compat");
        doThrow(new IllegalStateException("M1 has no counter quote"))
                .when(manual)
                .readCounterQuote(any(), any());
        var bound = bind(original, "m1");
        assertThat(bound.path("status").asText()).isEqualTo("BOUND");
        String number = bound.path("submissionNo").asText();
        asMerchant(
                () ->
                        call(
                                bean("ServiceSubmissionService"),
                                "getSubmission",
                                entity,
                                store,
                                actor,
                                number));
        asMerchant(
                () ->
                        call(
                                bean("ServiceSubmissionService"),
                                "recoverPending",
                                entity,
                                store,
                                actor,
                                number));
        verify(manual, never()).readCounterQuote(any(), any());
        assertThat(observationRow(original.source().subOrderNo()))
                .containsEntry("identity_revision", 1L);
    }

    @Test
    void counterUserAndGuestExistingSourcesKeepHistoricalParty() {
        var registration = guest("history");
        String guestWork = open(Map.of("guestNo", registration.guestNo()), "history");
        var original =
                admittedCounter(
                        guestWork,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "GUEST", null, registration.guestNo(), registration.claimToken()),
                        "history");
        claim(registration);
        var afterClaim =
                asMerchant(
                        () ->
                                manual.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                original.source().operation(),
                                                original.source().subOrderNo())));
        assertThat(afterClaim.party()).isEqualTo(original.source().party());
        var unrelated = guest("wrong-party");
        String wrong = open(Map.of("guestNo", unrelated.guestNo()), "wrong-party");
        assertThat(
                        http(
                                        "POST",
                                        "/biz/x/beauty/work-orders/"
                                                + wrong
                                                + "/service-submissions",
                                        existingRequest(wrong, afterClaim),
                                        "wrong-party-" + sku,
                                        false)
                                .path("code")
                                .asInt())
                .isNotZero();
        assertThat(
                        bind(new ManualOriginal(guestWork, afterClaim), "history")
                                .path("status")
                                .asText())
                .isEqualTo("BOUND");
        assertThat(
                        jdbc.queryForMap(
                                "SELECT identity_kind,identity_ref FROM bty_work_order_context"
                                        + " WHERE work_order_no=?",
                                guestWork))
                .containsEntry("identity_kind", "GUEST")
                .containsEntry("identity_ref", registration.guestNo());
        assertThat(observationRow(afterClaim.subOrderNo()))
                .containsEntry("customer_kind", "GUEST")
                .containsEntry("guest_no", registration.guestNo())
                .containsEntry("identity_revision", original.source().party().identityRevision());
    }

    @Test
    void manualExistingRecoveryNeedsNoRawGuestCredential() {
        var registration = guest("resume");
        String workOrder = open(Map.of("guestNo", registration.guestNo()), "resume");
        var original =
                admittedCounter(
                        workOrder,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "GUEST", null, registration.guestNo(), registration.claimToken()),
                        "resume");
        var bound = bind(original, "resume");
        String number = bound.path("submissionNo").asText();
        String command =
                jdbc.queryForObject(
                        "SELECT frozen_command FROM bty_service_submission WHERE submission_no=?",
                        String.class,
                        number);
        assertThat(command)
                .doesNotContain(registration.claimToken())
                .doesNotContain("counterCustomer");
        doThrow(BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE))
                .when(manual)
                .source(any(), any());
        var unknown =
                asMerchant(
                        () ->
                                call(
                                        bean("ServiceSubmissionService"),
                                        "recoverPending",
                                        entity,
                                        store,
                                        actor,
                                        number));
        assertThat(
                        json.readTree(json.writeValueAsString(unknown))
                                .path("blockingReasons")
                                .isEmpty())
                .isFalse();
        doCallRealMethod().when(manual).source(any(), any());
        var recovered =
                asMerchant(
                        () ->
                                call(
                                        bean("ServiceSubmissionService"),
                                        "recoverPending",
                                        entity,
                                        store,
                                        actor,
                                        number));
        assertThat(
                        json.readTree(json.writeValueAsString(recovered))
                                .path("source")
                                .path("manualState")
                                .asText())
                .isEqualTo("READY");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_trade_binding WHERE submission_no=?",
                                Integer.class,
                                number))
                .isOne();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT frozen_command FROM bty_service_submission WHERE"
                                        + " submission_no=?",
                                String.class,
                                number))
                .isEqualTo(command);
    }

    @Test
    void manualAndFundedHashesNeverAlias() throws Exception {
        String workOrder = open(Map.of("memberNo", member), "domain");
        var original =
                admittedCounter(
                        workOrder,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        "domain");
        asMerchant(
                () ->
                        call(
                                observationOwner(),
                                "observeManual",
                                manualCanonical(original.source()),
                                actor));
        var before = observationRow(original.source().subOrderNo());
        var financial =
                type("service.impl.ServiceSourceObservation$Canonical")
                        .getConstructors()[0]
                        .newInstance(
                                entity,
                                store,
                                buyer,
                                original.source().operation().operationNo(),
                                original.source().orderNo(),
                                original.source().subOrderNo());
        asMerchant(() -> call(observationOwner(), "observe", financial, actor));
        assertThat(observationRow(original.source().subOrderNo())).isEqualTo(before);
        var oldFacts =
                asMerchant(
                        () ->
                                checkout.source(
                                        scope,
                                        new CheckoutPort.SourceLookup(
                                                original.source().operation(),
                                                original.source().subOrderNo())));
        assertThat(oldFacts.lines()).isEmpty();
        assertThat(before)
                .containsEntry("source_revision", null)
                .containsEntry("verified_hash", null)
                .containsEntry("manual_hash", original.source().manualObservationHash());
    }

    @Test
    void lateObservationCannotReviveSuspendedSource() throws Exception {
        String workOrder = open(Map.of("memberNo", member), "epoch");
        var original =
                admittedCounter(
                        workOrder,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        "epoch");
        var canonical = manualCanonical(original.source());
        asMerchant(() -> call(observationOwner(), "observeManual", canonical, actor));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        doAnswer(
                        invocation -> {
                            var actual = invocation.callRealMethod();
                            if (first.compareAndSet(true, false)) {
                                entered.countDown();
                                assertThat(release.await(20, TimeUnit.SECONDS)).isTrue();
                            }
                            return actual;
                        })
                .when(manual)
                .source(any(), any());
        try (var worker = Executors.newSingleThreadExecutor()) {
            var stale =
                    worker.submit(
                            () ->
                                    asMerchant(
                                            () ->
                                                    call(
                                                            observationOwner(),
                                                            "observeManual",
                                                            canonical,
                                                            actor)));
            assertThat(entered.await(20, TimeUnit.SECONDS)).isTrue();
            try {
                assertThat(observationRow(original.source().subOrderNo()))
                        .containsEntry("manual_state", "UNKNOWN");
                jdbc.update(
                        "UPDATE ord_manual_service_admission SET"
                            + " phase='SUSPENDED',admission_revision=admission_revision+1,version=version+1"
                            + " WHERE order_no=?",
                        original.source().orderNo());
                var current =
                        asMerchant(
                                () -> call(observationOwner(), "observeManual", canonical, actor));
                assertThat(call(current, "manualReady")).isEqualTo(false);
                var suspended = observationRow(original.source().subOrderNo());
                assertThat(suspended).containsEntry("manual_state", "UNKNOWN");
                release.countDown();
                stale.get(20, TimeUnit.SECONDS);
                assertThat(observationRow(original.source().subOrderNo())).isEqualTo(suspended);
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void itemAndMappingRevisionAdvanceManualPairOnly() {
        String workOrder = open(Map.of("memberNo", member), "revision");
        var original =
                admittedCounter(
                        workOrder,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        "revision");
        asMerchant(
                () ->
                        call(
                                observationOwner(),
                                "observeManual",
                                manualCanonical(original.source()),
                                actor));
        for (String table : List.of("ord_item", "ord_checkout_line")) {
            var before = observationRow(original.source().subOrderNo());
            jdbc.update(
                    "UPDATE " + table + " SET version=version+1 WHERE sub_order_no=?",
                    original.source().subOrderNo());
            var current =
                    asMerchant(
                            () ->
                                    call(
                                            observationOwner(),
                                            "observeManual",
                                            manualCanonical(original.source()),
                                            actor));
            assertThat(call(current, "manualReady")).isEqualTo(true);
            var after = observationRow(original.source().subOrderNo());
            assertThat((Long) after.get("manual_revision"))
                    .isGreaterThan((Long) before.get("manual_revision"));
            assertThat(after.get("manual_hash")).isNotEqualTo(before.get("manual_hash"));
            for (String field :
                    List.of(
                            "source_revision",
                            "source_hash",
                            "verified_revision",
                            "verified_hash",
                            "verified_lines"))
                assertThat(after.get(field)).isEqualTo(before.get(field));
        }
    }

    @Test
    void memberReceptionRejectsBadSelectorsAndReplaysFrozenIdentity() {
        String work = open(Map.of("memberNo", member), "replay");
        jdbc.update("UPDATE usr_account SET status='DISABLED' WHERE user_no=?", buyer);
        try {
            assertThat(
                            http(
                                            "POST",
                                            "/biz/x/beauty/work-orders",
                                            Map.of(
                                                    "memberNo",
                                                    member,
                                                    "storeNo",
                                                    store,
                                                    "items",
                                                    List.of(Map.of("skuNo", sku, "qty", 1))),
                                            "inactive-new-" + sku,
                                            false)
                                    .path("code")
                                    .asInt())
                    .isNotZero();
            assertThat(open(Map.of("memberNo", member), "replay")).isEqualTo(work);
        } finally {
            jdbc.update("UPDATE usr_account SET status='NORMAL' WHERE user_no=?", buyer);
        }
        for (var selector :
                List.of(
                        Map.of("memberNo", "unknown-member"),
                        Map.of("memberNo", member, "guestNo", "bad"),
                        Map.of("memberNo", " " + member),
                        Map.of("memberNo", member, "userNo", buyer))) {
            var body = new HashMap<String, Object>(selector);
            body.put("storeNo", store);
            body.put("items", List.of(Map.of("skuNo", sku, "qty", 1)));
            assertThat(
                            http(
                                            "POST",
                                            "/biz/x/beauty/work-orders",
                                            body,
                                            "bad-" + selector.hashCode() + sku,
                                            false)
                                    .path("code")
                                    .asInt())
                    .isNotZero();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_work_order WHERE entity_no=?",
                                Integer.class,
                                entity))
                .isOne();
    }

    @Test
    void memberReceptionRechecksAuthorizationAfterCatalogIO() {
        var once = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            var actual = invocation.callRealMethod();
                            if (once.compareAndSet(false, true))
                                jdbc.update(
                                        "UPDATE mch_account SET status='DISABLED' WHERE"
                                                + " mch_account_no=?",
                                        actor);
                            return actual;
                        })
                .when(serviceCatalog)
                .service(anyString(), anyString());
        try {
            assertThatThrownBy(
                            () ->
                                    asMerchant(
                                            () ->
                                                    call(
                                                            bean("WorkOrderService"),
                                                            "openWorkOrder",
                                                            entity,
                                                            store,
                                                            actor,
                                                            dto(
                                                                    "OpenWorkOrderReq",
                                                                    null,
                                                                    store,
                                                                    List.of(
                                                                            dto(
                                                                                    "AppointmentItemReq",
                                                                                    sku,
                                                                                    1)),
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    List.of(),
                                                                    null,
                                                                    member),
                                                            "catalog-revoke-" + sku)))
                    .isInstanceOf(BizException.class);
        } finally {
            jdbc.update("UPDATE mch_account SET status='ACTIVE' WHERE mch_account_no=?", actor);
        }
        assertThat(once).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_work_order WHERE entity_no=?",
                                Integer.class,
                                entity))
                .isZero();
    }

    @Test
    void manualPrepareContinuesOriginalWithoutRawGuestCredential() {
        var registration = guest("prepare");
        for (var selector :
                List.of(
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "GUEST",
                                null,
                                registration.guestNo(),
                                registration.claimToken()))) {
            String work =
                    open(
                            "USER".equals(selector.kind())
                                    ? Map.of("memberNo", member)
                                    : Map.of("guestNo", registration.guestNo()),
                            "prepare-" + selector.kind());
            var root =
                    jdbc.queryForMap(
                            "SELECT version,scope_revision FROM bty_work_order WHERE"
                                    + " work_order_no=?",
                            work);
            var task =
                    jdbc.queryForMap(
                            "SELECT task_no,version,binding_revision FROM bty_service_task WHERE"
                                    + " work_order_no=?",
                            work);
            var prepared =
                    successful(
                            http(
                                    "POST",
                                    "/biz/x/beauty/work-orders/" + work + "/service-submissions",
                                    Map.of(
                                            "expectedRevision",
                                            root.get("version"),
                                            "expectedScopeRevision",
                                            root.get("scope_revision"),
                                            "tasks",
                                            List.of(
                                                    Map.of(
                                                            "taskNo",
                                                            task.get("task_no"),
                                                            "expectedRevision",
                                                            task.get("version"),
                                                            "expectedBindingRevision",
                                                            task.get("binding_revision"))),
                                            "additions",
                                            List.of(),
                                            "source",
                                            Map.of(
                                                    "mode",
                                                    "MANUAL_PREPARE",
                                                    "payMode",
                                                    "OFFLINE",
                                                    "activityChoices",
                                                    Map.of(),
                                                    "lines",
                                                    List.of(),
                                                    "counterCustomer",
                                                    selector)),
                                    "manual-prepare-" + work,
                                    false));
            assertThat(prepared.path("quote").isNull()).isTrue();
            var quote =
                    json.readValue(
                            prepared.path("counterQuote").toString(),
                            ManualServiceAdmissionPort.CounterCheckoutQuote.class);
            assertThat(quote.party().kind()).isEqualTo(selector.kind());
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM ord_checkout_operation WHERE entity_no=?"
                                            + " AND operation_no=? AND order_no IS NOT NULL",
                                    Integer.class,
                                    entity,
                                    quote.quote().operation().operationNo()))
                    .isZero();
            var accepted =
                    asMerchant(
                            () ->
                                    manual.acceptCounter(
                                            scope,
                                            new ManualServiceAdmissionPort.AcceptCounterReq(
                                                    quote.quote().operation(),
                                                    "accept-" + work,
                                                    quote.quote().quoteNo(),
                                                    quote.quote().quoteRevision(),
                                                    quote.quote().quoteHash(),
                                                    quote.party().identityRevision(),
                                                    testClock
                                                            .instant()
                                                            .truncatedTo(ChronoUnit.MILLIS),
                                                    "MERCHANT_RECORDED_IN_PERSON")));
            var created =
                    asMerchant(
                            () ->
                                    manual.placeCounter(
                                            scope,
                                            new ManualServiceAdmissionPort.PlaceCounterReq(
                                                    quote.quote().operation(),
                                                    accepted.confirmationNo(),
                                                    "place-" + work)));
            long revision =
                    jdbc.queryForObject(
                            "SELECT version FROM ord_order WHERE order_no=?",
                            Long.class,
                            created.orderNo());
            asMerchant(
                    () ->
                            manual.confirm(
                                    scope,
                                    new ManualServiceAdmissionPort.ConfirmManualReceipt(
                                            quote.quote().operation(),
                                            "cash-" + work,
                                            revision,
                                            ManualServiceAdmissionPort.ManualMethod.CASH,
                                            101,
                                            "CNY",
                                            testClock.instant().truncatedTo(ChronoUnit.MILLIS),
                                            null)));
            String number = prepared.path("submissionNo").asText();
            var frozen =
                    jdbc.queryForObject(
                            "SELECT frozen_command FROM bty_service_submission WHERE"
                                    + " submission_no=?",
                            String.class,
                            number);
            assertThat(frozen).doesNotContain(registration.claimToken());
            var frozenJson = json.readTree(frozen);
            assertThat(frozenJson.path("schemaVersion").asInt()).isEqualTo(3);
            assertThat(frozenJson.path("counterQuoteAuthority").asText())
                    .isEqualTo("MERCHANT_RECORDED_IN_PERSON");
            var recovered =
                    asMerchant(
                            () ->
                                    call(
                                            bean("ServiceSubmissionService"),
                                            "recoverPending",
                                            entity,
                                            store,
                                            actor,
                                            number));
            var view = json.readTree(json.writeValueAsString(recovered));
            assertThat(view.path("status").asText()).isEqualTo("BOUND");
            assertThat(view.path("subOrderNo").asText()).isEqualTo(created.subOrderNo());
            assertThat(view.path("source").path("canStart").asBoolean(true)).isFalse();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM bty_trade_binding WHERE submission_no=?",
                                    Integer.class,
                                    number))
                    .isOne();
        }
    }

    @Test
    void memberReceptionRejectsAllowedNamespaceShiftAfterCatalogIO() {
        jdbc.update(
                "INSERT INTO usr_account(user_no,nickname,status,created_at,updated_at) VALUES"
                        + " (?,'operator user','NORMAL',NOW(),NOW())",
                actor);
        jdbc.update("UPDATE mch_account SET user_no=? WHERE mch_account_no=?", actor, actor);
        var original = LoginUser.merchant(actor, "cashier");
        var userOperator =
                new LoginUser(
                        Realm.MERCHANT,
                        SubjectKind.USR,
                        actor,
                        "same actor user",
                        original.roles(),
                        original.perms(),
                        original.tenantNo(),
                        original.dataScope());
        String switchedToken = tokens.of(Realm.MERCHANT).issue(new SessionData(userOperator));
        var shifted = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            var actual = invocation.callRealMethod();
                            if (shifted.compareAndSet(false, true)) {
                                var authentication =
                                        UsernamePasswordAuthenticationToken.authenticated(
                                                userOperator, null, List.of());
                                authentication.setDetails(switchedToken);
                                SecurityContextHolder.getContext()
                                        .setAuthentication(authentication);
                                assertThat(
                                                operators.allowed(
                                                        new MerchantOperatorAccessPort.Query(
                                                                "MAIN",
                                                                entity,
                                                                store,
                                                                actor,
                                                                "MERCHANT",
                                                                "USR",
                                                                BizPerms.CUSTOMER)))
                                        .isTrue();
                            }
                            return actual;
                        })
                .when(serviceCatalog)
                .service(anyString(), anyString());
        try {
            assertThatThrownBy(
                            () ->
                                    asMerchant(
                                            () ->
                                                    call(
                                                            bean("WorkOrderService"),
                                                            "openWorkOrder",
                                                            entity,
                                                            store,
                                                            actor,
                                                            dto(
                                                                    "OpenWorkOrderReq",
                                                                    null,
                                                                    store,
                                                                    List.of(
                                                                            dto(
                                                                                    "AppointmentItemReq",
                                                                                    sku,
                                                                                    1)),
                                                                    null,
                                                                    null,
                                                                    null,
                                                                    List.of(),
                                                                    null,
                                                                    member),
                                                            "scope-shift-" + sku)))
                    .isInstanceOf(BizException.class);
        } finally {
            tokens.of(Realm.MERCHANT).revoke(switchedToken);
        }
        assertThat(shifted).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_work_order WHERE entity_no=?",
                                Integer.class,
                                entity))
                .isZero();
    }

    @Test
    void claimedGuestContextM1UserKeepsVerifiedRelation() {
        var registration = guest("m1-claim");
        String work = open(Map.of("guestNo", registration.guestNo()), "m1-claim");
        claim(registration);
        var original = admittedM1(work, "claimed-guest");
        var bound = bind(original, "m1-claimed");
        String number = bound.path("submissionNo").asText();
        var command =
                json.readTree(
                        jdbc.queryForObject(
                                "SELECT frozen_command FROM bty_service_submission WHERE"
                                        + " submission_no=?",
                                String.class,
                                number));
        assertThat(command.path("claimRevision").asLong()).isEqualTo(2);
        assertThat(command.path("claimReceiptHash").asText()).matches("[a-f0-9]{64}");
        assertThat(command.path("party").path("identityRevision").asLong()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT identity_kind FROM bty_work_order_context WHERE"
                                        + " work_order_no=?",
                                String.class,
                                work))
                .isEqualTo("GUEST");
        String originalHash = command.path("claimReceiptHash").asText();
        assertThat(originalHash).isNotEqualTo("0".repeat(64));
        jdbc.update(
                "UPDATE bty_guest_claim_receipt SET owner_receipt_hash=? WHERE entity_no=? AND"
                        + " store_no=? AND guest_no=?",
                "0".repeat(64),
                entity,
                store,
                registration.guestNo());
        try {
            assertThatThrownBy(
                            () ->
                                    asMerchant(
                                            () ->
                                                    call(
                                                            bean("ServiceSubmissionService"),
                                                            "getSubmission",
                                                            entity,
                                                            store,
                                                            actor,
                                                            number)))
                    .isInstanceOf(BizException.class);
        } finally {
            jdbc.update(
                    "UPDATE bty_guest_claim_receipt SET owner_receipt_hash=? WHERE entity_no=? AND"
                            + " store_no=? AND guest_no=?",
                    originalHash,
                    entity,
                    store,
                    registration.guestNo());
        }
        asMerchant(
                () ->
                        call(
                                bean("ServiceSubmissionService"),
                                "getSubmission",
                                entity,
                                store,
                                actor,
                                number));
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM bty_trade_binding WHERE submission_no=?",
                                Integer.class,
                                number))
                .isOne();
    }

    @Test
    void manualTypedDomainRejectsFinancialWritesAndStructuralDrift() throws Exception {
        String work = open(Map.of("memberNo", member), "ddl");
        var original =
                admittedCounter(
                        work,
                        new ManualServiceAdmissionPort.CounterCustomerRef(
                                "USER", member, null, null),
                        "ddl");
        bind(original, "ddl");
        var before = observationRow(original.source().subOrderNo());
        for (String mutation :
                List.of(
                        "observation_epoch=observation_epoch+1",
                        "buyer_no='FOREIGN'",
                        "state='UNKNOWN',failure_code='LATE_FINANCIAL'",
                        "admission_kind='LEGACY_FUNDED'")) {
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE bty_source_observation SET "
                                                    + mutation
                                                    + " WHERE id=?",
                                            before.get("id")))
                    .isInstanceOf(DataAccessException.class);
            assertThat(observationRow(original.source().subOrderNo())).isEqualTo(before);
        }
        for (String mutation :
                List.of(
                        "manual_source_hash=NULL",
                        "source_revision=1",
                        "identity_revision=0",
                        "customer_kind=NULL"))
            assertThatThrownBy(
                            () ->
                                    jdbc.update(
                                            "UPDATE bty_trade_binding SET "
                                                    + mutation
                                                    + " WHERE entity_no=? AND source_no=?",
                                            entity,
                                            original.source().subOrderNo()))
                    .isInstanceOf(DataAccessException.class);
        Object validator = type("config.BeautySchemaValidator").getConstructor().newInstance();
        call(validator, "initialize", dataSource, "validate");
        String sql;
        try (var input =
                type("config.BeautySchemaValidator")
                        .getClassLoader()
                        .getResourceAsStream(
                                "db/industry/beauty/V9__beauty_manual_source_observation.sql")) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        String trigger =
                sql.substring(sql.indexOf("CREATE TRIGGER"), sql.lastIndexOf("$$\nDELIMITER"));
        jdbc.execute("DROP TRIGGER trg_bty_source_observation_domain_guard");
        try {
            assertThatThrownBy(() -> call(validator, "initialize", dataSource, "validate"))
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            jdbc.execute(trigger);
        }
        call(validator, "initialize", dataSource, "validate");
    }

    private String openForBinding(
            MockMvc mvc, String token, Map<String, Object> identity, String suffix)
            throws Exception {
        var body = new HashMap<String, Object>(identity);
        body.put("storeNo", store);
        body.put("items", List.of(Map.of("skuNo", sku, "qty", 1)));
        var response =
                mvc.perform(
                                post("/biz/x/beauty/work-orders")
                                        .header("Authorization", "Bearer " + token)
                                        .header("X-Store-No", store)
                                        .header(
                                                "Idempotency-Key",
                                                "source-open-" + suffix + '-' + sku)
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(json.writeValueAsString(body)))
                        .andReturn()
                        .getResponse();
        assertThat(response.getStatus()).isBetween(200, 299);
        var returned = json.readTree(response.getContentAsString());
        assertThat(returned.path("code").asInt(-1)).as(response.getContentAsString()).isZero();
        String number = returned.path("data").path("workOrderNo").asText();
        assertThat(number).isNotBlank();
        return number;
    }
}

/**
 * Pure transport controls share the owned runtime scenario file, without booting a HOST context.
 */
class ManualSourceV8ProtocolTest {
    @Test
    void crossProcessIntegralValuesRetainExactProtocolSemantics() {
        var json = JsonMapper.builder().build();
        assertThat((JsonNode) json.valueToTree(1L)).isNotEqualTo(json.readTree("1"));
        assertThat(ManualSourceV8Driver.protocolValue(json, 1L)).isEqualTo(json.readTree("1"));
        for (long value : new long[] {9_007_199_254_740_993L, Long.MAX_VALUE, Long.MIN_VALUE}) {
            var canonical = ManualSourceV8Driver.protocolValue(json, value);
            assertThat(canonical.isIntegralNumber()).isTrue();
            assertThat(canonical.longValue()).isEqualTo(value);
            assertThat(canonical).isEqualTo(json.readTree(Long.toString(value)));
        }
        assertThat(ManualSourceV8Driver.protocolValue(json, "1")).isNotEqualTo(json.readTree("1"));
        assertThat(ManualSourceV8Driver.protocolValue(json, null).isNull()).isTrue();
        assertThat(ManualSourceV8Driver.protocolValue(json, "{\"frozen\":1}").asText())
                .isEqualTo("{\"frozen\":1}");
    }
}
