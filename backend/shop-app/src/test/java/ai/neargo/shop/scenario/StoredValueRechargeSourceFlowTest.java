package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.paybridge.StoredValueRechargeCoordinator;
import ai.neargo.shop.paybridge.StoredValueRechargeEventConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import java.util.Map;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import ai.neargo.shop.pay.storedvalue.service.StoredValueRechargeService;
import ai.neargo.shop.pay.storedvalue.service.StoredValueCreditService;
import ai.neargo.shop.spi.storedvalue.StoredValuePort;
import ai.neargo.shop.spi.storedvalue.StoredValueRechargeFactsPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.EntitlementEvents.StoredValueRechargeReceived;
import ai.neargo.shop.spi.entitlement.PayEntitlementEventCodec;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import ai.neargo.svc.client.ServiceCallException;
import java.time.Duration;
import org.springframework.boot.test.web.server.LocalServerPort;
import java.util.concurrent.Executors;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.member.dto.MemberCodeScope;
import ai.neargo.shop.member.service.MemberCodeResolveService;
import ai.neargo.shop.promotion.dto.RechargeIntentCreate;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityBudget;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.RechargeIntentService;
import ai.neargo.shop.promotion.service.impl.ActivityVersionServiceImpl.PublishedSnapshot;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.promotion.ActivityParticipationOutcomePort;
import ai.neargo.shop.spi.storedvalue.RechargeSourcePort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/** Real identity, core rules/reservation and owner reads. Preset publication/full manual receipt are verified later. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shop.services.internal-token=recharge-source-rpc","shop.member-code.enabled=true", "shop.pay.stored-value.enabled=true", "shop.promotion.activity.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:sv-source;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class StoredValueRechargeSourceFlowTest {
    @Autowired WebApplicationContext web;
    @Autowired TokenStores tokens;
    @Autowired OtpStore otp;
    @Autowired StoredValueRechargeCoordinator coordinator;
    @Autowired StoredValueRechargeEventConsumer recovery;
    @Autowired StoredValueRechargeService recharges;
    @Autowired StoredValueCreditService credits;
    @Autowired StoredValueRechargeFactsPort payFacts;
    @Autowired ActivityParticipationPort participation;
    @LocalServerPort int port;
    @Autowired RechargeIntentService sources;
    @Autowired RechargeSourcePort facts;
    @Autowired MemberCodeResolveService members;
    @Autowired ActivityMapper heads;
    @Autowired PmtActivityVersionMapper versions;
    @Autowired PmtActivityBudgetMapper budgets;
    @Autowired List<ActivityParticipationOutcomePort> outcomes;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    private String user, actor, entity, store, activity;
    private EntitlementContext ctx;
    private RechargeIntentCreate request;
    private String id() { return UUID.randomUUID().toString(); }
    @BeforeEach void seedOwnedPublishedRulesAndRealMemberReference() {
        user = id(); actor = id(); entity = id(); store = id(); activity = "A" + id();
        ctx = new EntitlementContext("MAIN", actor, id(), "a".repeat(64));
        for (var no : List.of(user, actor)) jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,"
                + "updated_at) VALUES (?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", no);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,market,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'beauty','ACTIVE','CN','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, actor);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'beauty store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        Instant now = Instant.ofEpochMilli(System.currentTimeMillis());
        var definition = new UnifiedActivityDefinition("top up", "RECHARGE", "FIXTURE", 1,
                new RedemptionScope("STORES", List.of(store)), null,
                new UnifiedActivityDefinition.Schedule("ONE_OFF", now.minusSeconds(60), now.plusSeconds(3600), "UTC",
                        List.of(), null, null),
                new UnifiedActivityDefinition.Rule(1, null, List.of(), List.of(new UnifiedActivityDefinition.Tier(
                        "T1", 1000, 100, new RedemptionScope("STORES", List.of(store))))), 10L, 10000L, 2, "CNY");
        var head = new PmtActivity(); audit(head); head.setActivityNo(activity); head.setEntityNo(entity);
        head.setName("top up"); head.setOwner("MERCHANT"); head.setScene("RECHARGE"); head.setStatus("RUNNING");
        head.setTriggerType("NONE"); head.setBenefitType("COMBO"); head.setScheduleType("ONE_OFF");
        head.setStartAt(now.toEpochMilli()); head.setCurrentVersionNo(activity + "V");
        head.setDraftSnapshot(json.writeValueAsString(definition)); heads.insert(head);
        var version = new PmtActivityVersion(); audit(version); version.setVersionNo(activity + "V");
        version.setActivityNo(activity); version.setVersionIndex(1); version.setSchemaVersion(1);
        version.setCurrency("CNY"); version.setScheduleType("ONE_OFF"); version.setMarketZone("UTC");
        version.setStartsAt(LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC));
        version.setEndsAt(LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC));
        String snapshot = json.writeValueAsString(new PublishedSnapshot(definition, null));
        version.setRuleSnapshot(snapshot); version.setRuleHash(ActivityCommandDeduplicator.hash(snapshot));
        version.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC)); version.setPublishedBy(actor); versions.insert(version);
        var budget = new PmtActivityBudget(); audit(budget); budget.setActivityNo(activity); budget.setCurrency("CNY");
        budget.setQuotaLimit(10L); budget.setQuotaHeld(0L); budget.setQuotaUsed(0L); budget.setPerUserLimit(2);
        budget.setBudgetLimitMinor(10000L); budget.setBudgetHeldMinor(0L); budget.setBudgetUsedMinor(0L);
        budgets.insert(budget);
        var member = members.issue("MAIN", user);
        var identified = members.resolve(new MemberCodeScope("MAIN", entity, store, actor), member.memberCode());
        request = new RechargeIntentCreate(identified.customerRef(), activity, activity + "V", "T1", store);
    }
    @Test void authoritativeSourceReservesActualTierOnceAndReplaysWithoutPersistingRawMemberReference() {
        var plan = sources.begin(ctx, entity, request, "begin");
        assertThat(plan.permit().payAmountMinor()).isEqualTo(1000);
        assertThat(plan.permit().bonusAmountMinor()).isEqualTo(100);
        assertThat(plan.participation().userNo()).isEqualTo(user);
        assertThat(facts.resolve(ctx, plan.source())).isEqualTo(plan.fact());
        assertThat(facts.byRechargeNo(ctx, plan.rechargeNo())).isEqualTo(plan.fact());
        assertThat(jdbc.queryForObject("SELECT quota_held FROM pmt_activity_budget WHERE activity_no=?",
                Long.class, activity)).isOne();
        assertThat(jdbc.queryForObject("SELECT budget_held_minor FROM pmt_activity_budget WHERE activity_no=?",
                Long.class, activity)).isEqualTo(100);
        assertThat(jdbc.queryForObject("SELECT plan_snapshot FROM pmt_recharge_intent WHERE intent_no=?", String.class,
                plan.intentNo())).doesNotContain(request.customerRef());
        jdbc.update("UPDATE mbr_identity_ticket SET issued_at=?,expires_at=? WHERE actor_no=?",
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10), LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1), actor);
        assertThat(sources.begin(ctx, entity, request, "begin")).isEqualTo(plan);
        assertThatThrownBy(() -> sources.begin(ctx, entity,
                new RechargeIntentCreate(request.customerRef(), activity, activity + "V", "OTHER", store), "begin"))
                .isInstanceOf(BizException.class);
        var owner = outcomes.stream().filter(p -> p.supports(plan.source())).findFirst().orElseThrow();
        assertThat(owner.outcome(ctx, plan.source()).state()).isEqualTo(ActivityParticipationOutcomePort.State.UNKNOWN);
    }
    @Test void failedSourceOutboxRollsBackSourceReservationAndBudgetAndAllowsTheSameOriginalRequest() {
        String target = "RI" + ActivityCommandDeduplicator.hash(json.writeValueAsString(
                List.of(ctx.tenantNo(), entity, actor, "retry"))).substring(0, 60);
        jdbc.execute("ALTER TABLE sys_outbox ADD CONSTRAINT reject_recharge_source "
                + "CHECK (event_type<>'STORED_VALUE_RECHARGE_PREPARED' OR aggregate_id<>'" + target + "')");
        try { assertThatThrownBy(() -> sources.begin(ctx, entity, request, "retry")).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE sys_outbox DROP CONSTRAINT reject_recharge_source"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_recharge_intent WHERE entity_no=?", Integer.class,
                entity)).isZero();
        assertThat(jdbc.queryForObject("SELECT quota_held FROM pmt_activity_budget WHERE activity_no=?", Long.class,
                activity)).isZero();
        assertThat(sources.begin(ctx, entity, request, "retry").permit()).isNotNull();
    }
    @Test void realCoreSourceRoundTripsThroughAuthenticatedReverseHttp() {
        var plan = sources.begin(ctx, entity, request, "rpc");
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PLATFORM, "http://127.0.0.1:" + port);
        var api = new InternalHttp(locator, "recharge-source-rpc").client(ServiceName.PLATFORM,
                CoreEntitlementInternalApi.class, Duration.ofSeconds(5));
        assertThat(api.rechargeSource(new EntitlementCoreInternal.SourceQuery(ctx, plan.source())))
                .isEqualTo(plan.fact());
        assertThat(api.rechargeByNo(new EntitlementCoreInternal.RechargeQuery(ctx, plan.rechargeNo())))
                .isEqualTo(plan.fact());
        var denied = new InternalHttp(locator, "wrong").client(ServiceName.PLATFORM,
                CoreEntitlementInternalApi.class, Duration.ofSeconds(5));
        assertThatThrownBy(() -> denied.rechargeSource(new EntitlementCoreInternal.SourceQuery(ctx, plan.source())))
                .isInstanceOf(ServiceCallException.class);
    }
    @Test void realTierAndMerchantReceiptCreditOnceAndOnlyThenConfirmMarketing() {
        var plan = sources.begin(ctx, entity, request, "manual");
        var create = new StoredValuePort.CreateRecharge(plan.source(), plan.permit());
        var row = recharges.create(plan.context(), entity, create);
        assertThat(row.status()).isEqualTo(StoredValuePort.RechargeState.WAIT_COLLECTION);
        assertThatThrownBy(() -> recharges.submit(commandContext(), entity, new StoredValuePort.SubmitRecharge(
                row.rechargeNo(), row.revision(), 999, StoredValuePort.CollectionMethod.CASH, id(), "c".repeat(64))))
                .isInstanceOf(BizException.class);
        var submitted = recharges.submit(commandContext(), entity, new StoredValuePort.SubmitRecharge(
                row.rechargeNo(), row.revision(), 1000, StoredValuePort.CollectionMethod.TRANSFER, id(), "c".repeat(64)));
        assertThatThrownBy(() -> recharges.receive(commandContext(), entity, new StoredValuePort.ReceiveRecharge(
                row.rechargeNo(), submitted.revision(), "d".repeat(64), null))).isInstanceOf(BizException.class);
        var receiveContext = commandContext();
        var receive = new StoredValuePort.ReceiveRecharge(row.rechargeNo(), submitted.revision(), "c".repeat(64),
                "Merchant checked transfer receipt");
        var received = recharges.receive(receiveContext, entity, receive);
        assertThat(received.status()).isEqualTo(StoredValuePort.RechargeState.RECEIVED);
        assertThat(recharges.receive(receiveContext, entity, receive)).isEqualTo(received);
        assertThatThrownBy(() -> recharges.cancel(commandContext(), entity,
                new StoredValuePort.CancelRecharge(row.rechargeNo(), received.revision(), "late cancel")))
                .isInstanceOf(BizException.class);
        var f = payFacts.query(ctx, entity, row.rechargeNo());
        var command = new StoredValuePort.CreditRecharge(row.rechargeNo(), new SourceRef("PAY", "RECHARGE",
                row.rechargeNo(), null, null, null, 0), f.receivedDecision());
        var credited = credits.credit(ctx, command);
        assertThat(credited.status()).isEqualTo(StoredValuePort.RechargeState.CREDITED);
        assertThat(credits.credit(ctx, command)).isEqualTo(credited);
        var confirmed = participation.confirm(plan.context(), new ActivityParticipationPort.ConfirmParticipation(
                plan.permit().reservationNo(), plan.source(), row.rechargeNo()));
        assertThat(confirmed.status()).isEqualTo(ActivityParticipationPort.State.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT quota_used FROM pmt_activity_budget WHERE activity_no=?",
                Long.class, activity)).isOne();
    }
    @Test void cancellationBeforeCreateFencesLateCreateAndOnlyActualCancellationReleasesBudget() {
        var plan = sources.begin(ctx, entity, request, "cancel-before-create");
        var cancelled = recharges.cancel(commandContext(), entity,
                new StoredValuePort.CancelRecharge(plan.rechargeNo(), 0, "customer changed mind"));
        assertThat(cancelled.status()).isEqualTo(StoredValuePort.RechargeState.CANCELLED);
        assertThat(recharges.create(plan.context(), entity,
                new StoredValuePort.CreateRecharge(plan.source(), plan.permit())).status())
                .isEqualTo(StoredValuePort.RechargeState.CANCELLED);
        assertThat(participation.release(plan.context(), new ActivityParticipationPort.ReleaseParticipation(
                plan.permit().reservationNo(), plan.source(), plan.rechargeNo())).status())
                .isEqualTo(ActivityParticipationPort.State.RELEASED);
        assertThat(jdbc.queryForObject("SELECT quota_held FROM pmt_activity_budget WHERE activity_no=?",
                Long.class, activity)).isZero();
    }
    @Test void receiptCannotFundTwoRechargesAndReceiveRacingCancelHasOneDurableOutcome() throws Exception {
        var first = sources.begin(ctx, entity, request, "first");
        var second = sources.begin(ctx, entity, request, "second");
        String receipt = id();
        var a = recharges.create(first.context(), entity, new StoredValuePort.CreateRecharge(first.source(), first.permit()));
        var b = recharges.create(second.context(), entity, new StoredValuePort.CreateRecharge(second.source(), second.permit()));
        a = recharges.submit(commandContext(), entity, new StoredValuePort.SubmitRecharge(a.rechargeNo(), 0, 1000,
                StoredValuePort.CollectionMethod.CASH, receipt, "c".repeat(64)));
        b = recharges.submit(commandContext(), entity, new StoredValuePort.SubmitRecharge(b.rechargeNo(), 0, 1000,
                StoredValuePort.CollectionMethod.CASH, receipt, "c".repeat(64)));
        recharges.receive(commandContext(), entity, new StoredValuePort.ReceiveRecharge(a.rechargeNo(), a.revision(),
                "c".repeat(64), null));
        final var secondSubmitted = b;
        assertThatThrownBy(() -> recharges.receive(commandContext(), entity, new StoredValuePort.ReceiveRecharge(
                secondSubmitted.rechargeNo(), secondSubmitted.revision(), "c".repeat(64), null)))
                .isInstanceOf(RuntimeException.class);
        // Correct an unreceived bad receipt by cancelling its original source and creating a fresh intent.
        recharges.cancel(commandContext(), entity, new StoredValuePort.CancelRecharge(b.rechargeNo(), b.revision(),
                "incorrect receipt reference"));
        participation.release(second.context(), new ActivityParticipationPort.ReleaseParticipation(
                second.permit().reservationNo(), second.source(), second.rechargeNo()));
        var corrected = sources.begin(ctx, entity, request, "corrected");
        var created = recharges.create(corrected.context(), entity,
                new StoredValuePort.CreateRecharge(corrected.source(), corrected.permit()));
        var raceSubmitted = recharges.submit(commandContext(), entity, new StoredValuePort.SubmitRecharge(
                created.rechargeNo(), created.revision(), 1000, StoredValuePort.CollectionMethod.CASH, id(),
                "c".repeat(64)));
        try (var pool = Executors.newFixedThreadPool(2)) {
            var receive = pool.submit(() -> attempt(() -> recharges.receive(commandContext(), entity,
                    new StoredValuePort.ReceiveRecharge(raceSubmitted.rechargeNo(), raceSubmitted.revision(),
                            "c".repeat(64), null))));
            var cancel = pool.submit(() -> attempt(() -> recharges.cancel(commandContext(), entity,
                    new StoredValuePort.CancelRecharge(raceSubmitted.rechargeNo(), raceSubmitted.revision(),
                            "before received"))));
            assertThat(List.of(receive.get(), cancel.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(payFacts.query(ctx, entity, raceSubmitted.rechargeNo()).recharge().status())
                .isIn(StoredValuePort.RechargeState.RECEIVED, StoredValuePort.RechargeState.CANCELLED);
    }
    @Test void durableEventsRecoverTheOriginalReceivedCreditAndMarketingOnlyAfterPaySucceeds() {
        var p = sources.begin(ctx, entity, request, "event-recovery");
        var prepared = event("STORED_VALUE_RECHARGE_PREPARED", jdbc.queryForObject(
                "SELECT payload FROM sys_outbox WHERE aggregate_id=? AND event_type='STORED_VALUE_RECHARGE_PREPARED'",
                String.class, p.intentNo()));
        recovery.consume(prepared); recovery.consume(prepared);
        var created = payFacts.query(ctx, entity, p.rechargeNo()).recharge();
        assertThat(created.status()).isEqualTo(StoredValuePort.RechargeState.WAIT_COLLECTION);
        var submitted = recharges.submit(commandContext(), entity, new StoredValuePort.SubmitRecharge(p.rechargeNo(),
                created.revision(), 1000, StoredValuePort.CollectionMethod.CASH, id(), "c".repeat(64)));
        recharges.receive(commandContext(), entity, new StoredValuePort.ReceiveRecharge(p.rechargeNo(),
                submitted.revision(), "c".repeat(64), null));
        var received = event("STORED_VALUE_RECHARGE_RECEIVED", jdbc.queryForObject("SELECT payload FROM pay_ent_outbox "
                + "WHERE aggregate_no=? AND event_type='STORED_VALUE_RECHARGE_RECEIVED'", String.class, p.rechargeNo()));
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PLATFORM, "http://127.0.0.1:" + port);
        var api = new InternalHttp(locator, "recharge-source-rpc").client(ServiceName.PLATFORM,
                CoreEntitlementInternalApi.class, Duration.ofSeconds(5));
        var actualEvent = json.readValue(received.getPayload(), StoredValueRechargeReceived.class);
        assertThat(api.receiveEvent(PayEntitlementEventCodec.encode(actualEvent, json)).accepted()).isTrue();
        jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT reject_recovery_credit CHECK "
                + "(aggregate_no<>'" + p.rechargeNo() + "' OR event_type<>'STORED_VALUE_CREDITED')");
        try { assertThatThrownBy(() -> recovery.consume(received)).isInstanceOf(RuntimeException.class); }
        finally { jdbc.execute("ALTER TABLE pay_ent_outbox DROP CONSTRAINT reject_recovery_credit"); }
        assertThat(participation.query(p.context(), p.source()).status())
                .isEqualTo(ActivityParticipationPort.State.RESERVED);
        assertThat(payFacts.query(ctx, entity, p.rechargeNo()).recharge().status())
                .isEqualTo(StoredValuePort.RechargeState.RECEIVED);
        recovery.consume(received); recovery.consume(received); recovery.consume(prepared);
        assertThat(coordinator.query(ctx, entity, p.rechargeNo()).status()).isEqualTo("CREDITED");
        assertThat(participation.query(p.context(), p.source()).status())
                .isEqualTo(ActivityParticipationPort.State.CONFIRMED);
        assertThat(jdbc.queryForObject("SELECT status FROM pmt_recharge_intent WHERE intent_no=?", String.class,
                p.intentNo())).isEqualTo("CREDITED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sv_voucher WHERE biz_no=?", Integer.class,
                p.rechargeNo())).isOne();
    }
    @Test void latePreparedEventCannotReopenACancelledIntentOrReReserveMarketing() {
        var p = sources.begin(ctx, entity, request, "delayed-prepared");
        assertThat(coordinator.query(ctx, entity, p.rechargeNo()).status()).isEqualTo("PROCESSING");
        assertThat(coordinator.cancel(commandContext(), entity,
                new StoredValuePort.CancelRecharge(p.rechargeNo(), 0, "do not collect")).status())
                .isEqualTo("CANCELLED");
        var prepared = event("STORED_VALUE_RECHARGE_PREPARED", jdbc.queryForObject(
                "SELECT payload FROM sys_outbox WHERE aggregate_id=? AND event_type='STORED_VALUE_RECHARGE_PREPARED'",
                String.class, p.intentNo()));
        recovery.consume(prepared);
        assertThat(coordinator.query(ctx, entity, p.rechargeNo()).status()).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM pmt_recharge_intent WHERE intent_no=?", String.class,
                p.intentNo())).isEqualTo("CANCELLED");
        assertThat(participation.query(p.context(), p.source()).status())
                .isEqualTo(ActivityParticipationPort.State.RELEASED);
    }
    @Test void merchantHttpHandlesReceiptProgressAndEnforcesExplicitStorePermissions() throws Exception {
        var mvc = MockMvcBuilders.webAppContextSetup(web).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String phone = "126" + String.format("%08d", Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
        String consumer = TestLogin.consumer(mvc, json, otp, phone);
        String operator = tokens.of(Realm.CONSUMER).get(consumer).orElseThrow().user().userNo();
        jdbc.update("UPDATE mch_entity SET owner_user_no=? WHERE entity_no=?", operator, entity);
        String account = id();
        jdbc.update("INSERT INTO mch_account(mch_account_no,entity_no,user_no,is_owner,is_primary,status,tenant_no,"
                + "created_at,updated_at) VALUES (?,?,?,1,1,'ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                account, entity, operator);
        String merchant = TestLogin.merchantOwner(mvc, json, otp, phone);
        var member = members.issue("MAIN", user);
        var reference = members.resolve(new MemberCodeScope("MAIN", entity, store, operator), member.memberCode());
        String key = id();
        var body = new RechargeIntentCreate(reference.customerRef(), activity, activity + "V", "T1", store);
        var first = publicPost(mvc, "/biz/stored-value/recharges", merchant, body, key);
        assertThat(first.path("code").asInt()).isZero();
        var created = first.path("data"); String no = created.path("rechargeNo").asString();
        assertThat(created.path("status").asString()).isEqualTo("WAIT_COLLECTION");
        String path = "/biz/stored-value/recharges/" + no;
        assertThat(publicPost(mvc, path + "/submit", merchant, Map.of("expectedRevision", 0,
                "actualAmountMinor", 999, "collectionMethod", "CASH", "receiptRef", id(), "receiptDigest",
                "c".repeat(64)), id()).path("code").asInt()).isNotZero();
        var submitted = publicPost(mvc, path + "/submit", merchant, Map.of("expectedRevision", 0,
                "actualAmountMinor", 1000, "collectionMethod", "CASH", "receiptRef", id(), "receiptDigest",
                "c".repeat(64)), id());
        assertThat(submitted.path("code").asInt()).isZero();
        var received = publicPost(mvc, path + "/receive", merchant,
                Map.of("expectedRevision", submitted.path("data").path("revision").asLong(), "receiptDigest",
                        "c".repeat(64)), id());
        assertThat(received.path("code").asInt()).isZero();
        assertThat(received.path("data").path("status").asString()).isEqualTo("RECEIVED");
        recovery.consume(event("STORED_VALUE_RECHARGE_RECEIVED", jdbc.queryForObject("SELECT payload "
                + "FROM pay_ent_outbox WHERE aggregate_no=? AND event_type='STORED_VALUE_RECHARGE_RECEIVED'",
                String.class, no)));
        var completed = publicGet(mvc, path, merchant);
        assertThat(completed.path("code").asInt()).isZero();
        assertThat(completed.path("data").path("status").asString()).isEqualTo("CREDITED");
        assertThat(json.writeValueAsString(completed)).doesNotContain("principalMinor", "bonusMinor", "receiptRef",
                "receiptDigest", "receivedBy", reference.customerRef());
        assertThat(publicPost(mvc, "/biz/stored-value/recharges", merchant, body, key)
                .path("data").path("rechargeNo").asString()).isEqualTo(no);
        assertThat(publicGet(mvc, path, consumer).path("code").asInt()).isNotZero();
        String otherStore = id();
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'other store','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", otherStore, entity);
        jdbc.update("UPDATE mch_account SET is_owner=0 WHERE mch_account_no=?", account);
        jdbc.update("INSERT INTO mch_role(entity_no,role_code,name,perms,tenant_no,created_at,updated_at) "
                + "VALUES (?,'SV_READ_ONLY','reader','[\"pay:sv:recharge:read\"]','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)",
                entity);
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'SV_READ_ONLY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", account, otherStore);
        assertThat(publicGet(mvc, path, merchant).path("code").asInt()).isNotZero();
        jdbc.update("INSERT INTO mch_store_role(mch_account_no,store_no,role,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'SV_READ_ONLY','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", account, store);
        assertThat(publicGet(mvc, path, merchant).path("code").asInt()).isZero();
        assertThat(publicPost(mvc, path + "/receive", merchant, Map.of("expectedRevision", 0, "receiptDigest",
                "c".repeat(64)), id()).path("code").asInt()).isNotZero();
    }
    private JsonNode publicPost(MockMvc mvc, String path, String token, Object body, String key) throws Exception {
        var response = json.readTree(mvc.perform(post(path).header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))).andReturn().getResponse().getContentAsString());
        assertThat(response.has("code")).isTrue(); return response;
    }
    private JsonNode publicGet(MockMvc mvc, String path, String token) throws Exception {
        var response = json.readTree(mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString());
        assertThat(response.has("code")).isTrue(); return response;
    }
    private SysOutbox event(String type, String payload) {
        var event = new SysOutbox(); event.setEventType(type); event.setPayload(payload); return event;
    }
    private boolean attempt(Runnable work) { try { work.run(); return true; } catch (RuntimeException failure) { return false; } }
    private EntitlementContext commandContext() { return new EntitlementContext("MAIN", actor, id(), "b".repeat(64)); }
    private void audit(BaseEntity row) {
        row.setTenantNo("MAIN"); row.setVersion(0L); row.setDeleted(0); row.setCreatedBy(actor); row.setUpdatedBy(actor);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
    }
}
