package ai.neargo.shop.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.auth.Realm;
import ai.neargo.shop.auth.TokenStores;
import ai.neargo.shop.common.OtpStore;
import ai.neargo.shop.support.TestLogin;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.http.MediaType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityBudget;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.ActivityBenefitIntentService;
import ai.neargo.shop.promotion.service.impl.ActivityVersionServiceImpl.PublishedSnapshot;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import tools.jackson.databind.ObjectMapper;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.paybridge.ActivityBenefitCoordinator;
import ai.neargo.shop.paybridge.EntitlementRecoveryService;
import ai.neargo.shop.trade.dto.EntitlementRecoveryWork.Kind;
import ai.neargo.shop.spi.entitlement.CoreEntitlementInternalApi;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.EntitlementCoreInternal;
import ai.neargo.shop.svc.ConfigServiceLocator;
import ai.neargo.shop.svc.InternalHttp;
import ai.neargo.shop.svc.ServiceName;
import java.time.Duration;
import org.springframework.boot.test.web.server.LocalServerPort;
import ai.neargo.shop.paybridge.ActivityBenefitDecisionConsumer;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.promotion.dto.ActivityBenefitDecided;
import ai.neargo.shop.promotion.dto.BenefitClaimCommand;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "shop.services.internal-token=benefit-http-test", "shop.promotion.activity.enabled=true",
        "shop.promotion.presets.enabled=true", "shop.pay.entitlement.catalog.enabled=true",
        "shop.pay.entitlement.issue.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:benefit-coordinator;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class ActivityBenefitCoordinatorFlowTest {
    @LocalServerPort int port;
    @Autowired ActivityBenefitCoordinator coordinator;
    @Autowired EntitlementRecoveryService recovery;
    @Autowired EntitlementCatalogService catalog;
    @Autowired EntitlementIssuePolicyService policies;
    @Autowired JdbcTemplate jdbc;
    @Autowired WebApplicationContext web;
    @Autowired OtpStore otp;
    @Autowired TokenStores tokens;
    @Autowired ActivityBenefitIntentService intents;
    @Autowired ActivityVersionService activities;
    @Autowired ActivityBenefitDecisionConsumer consumer;
    @Autowired ActivityMapper heads;
    @Autowired PmtActivityVersionMapper versions;
    @Autowired PmtActivityBudgetMapper budgets;
    @Autowired ObjectMapper json;
    @MockitoSpyBean EntitlementGrantPort pay;
    private String id() { return UUID.randomUUID().toString(); }
    private String[] owner() {
        String user = id(), entity = id(), store = id();
        jdbc.update("INSERT INTO usr_account(user_no,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,'NORMAL','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", user);
        jdbc.update("INSERT INTO mch_entity(entity_no,owner_user_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'beauty','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", entity, user);
        jdbc.update("INSERT INTO mch_store(store_no,entity_no,name,status,tenant_no,created_at,updated_at) "
                + "VALUES (?,?,'beauty','ACTIVE','MAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", store, entity);
        return new String[] {user, entity, store};
    }
    private String template(String[] owner, String mode) {
        var scope = new CatalogScope("MAIN", owner[1], owner[0], false);
        var definition = new EntitlementDefinition("claim", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of(mode));
        var head = catalog.create(scope, definition, id());
        var version = catalog.publish(scope, head.templateNo(), head.revision(), id());
        policies.save(scope, head.templateNo(), new SavePolicy(0, 5L, 500L, 1, true), id());
        return version.versionNo();
    }
    private void audit(BaseEntity row) {
        row.setTenantNo("MAIN"); row.setVersion(0L); row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
        row.setCreatedBy("FIXTURE"); row.setUpdatedBy("FIXTURE");
    }
    private String activity(String[] owner, List<String> references) {
        String no = "A" + id(); Instant now = Instant.now();
        var definition = new UnifiedActivityDefinition("two rights", "ENTITLEMENT_GRANT", "AP001", 1,
                new EntitlementDefinition.RedemptionScope("STORES", List.of(owner[2])), null,
                new UnifiedActivityDefinition.Schedule("ONE_OFF", now.minusSeconds(60), now.plusSeconds(3600),
                        "UTC", List.of(), null, null),
                new UnifiedActivityDefinition.Rule(1, new UnifiedActivityDefinition.Trigger("NONE", null, null),
                        references.stream().map(r -> new UnifiedActivityDefinition.Benefit("ENTITLEMENT", null,
                                r, null, 1)).toList(), List.of()), 5L, 0L, 1, "CNY");
        var head = new PmtActivity(); audit(head);
        head.setActivityNo(no); head.setEntityNo(owner[1]); head.setName("two rights"); head.setOwner("MERCHANT");
        head.setScene("ENTITLEMENT_GRANT"); head.setStatus("RUNNING"); head.setTriggerType("NONE");
        head.setBenefitType("COMBO"); head.setScheduleType("ONE_OFF"); head.setStartAt(now.toEpochMilli());
        head.setCurrentVersionNo(no + "V"); head.setDraftSnapshot(json.writeValueAsString(definition));
        heads.insert(head);
        var version = new PmtActivityVersion(); audit(version); version.setVersionNo(no + "V");
        version.setActivityNo(no); version.setVersionIndex(1); version.setSchemaVersion(1); version.setCurrency("CNY");
        version.setScheduleType("ONE_OFF"); version.setStartsAt(LocalDateTime.ofInstant(now.minusSeconds(60),
                ZoneOffset.UTC)); version.setEndsAt(LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC));
        version.setMarketZone("UTC");
        String snapshot = json.writeValueAsString(new PublishedSnapshot(definition, null));
        version.setRuleSnapshot(snapshot); version.setRuleHash(ActivityCommandDeduplicator.hash(snapshot));
        version.setPublishedBy("FIXTURE"); version.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        versions.insert(version);
        var budget = new PmtActivityBudget(); audit(budget); budget.setActivityNo(no); budget.setCurrency("CNY");
        budget.setQuotaLimit(5L); budget.setBudgetLimitMinor(0L); budget.setPerUserLimit(1);
        budget.setQuotaHeld(0L); budget.setQuotaUsed(0L); budget.setBudgetHeldMinor(0L); budget.setBudgetUsedMinor(0L);
        budgets.insert(budget); return no;
    }
    @Test void aRealPublishedReceiveActivityCanBeClaimedWithoutTestReadinessProviders() {
        var owner = owner(); String version = template(owner, "ACTIVITY");
        jdbc.update("UPDATE mch_entity SET industry='LIFE_SERVICE' WHERE entity_no=?", owner[1]);
        jdbc.update("UPDATE pmt_preset_template SET enabled=1 WHERE tenant_no='MAIN' AND template_code='AP004'");
        try {
            Instant now = Instant.now();
            var definition = new UnifiedActivityDefinition("进店领券", "ENTITLEMENT_GRANT", "AP004", 1,
                    new EntitlementDefinition.RedemptionScope("STORES", List.of(owner[2])), null,
                    new UnifiedActivityDefinition.Schedule("ONE_OFF", now.minusSeconds(60), now.plusSeconds(3600),
                            "UTC", List.of(), null, null),
                    new UnifiedActivityDefinition.Rule(1, new UnifiedActivityDefinition.Trigger("NONE", null, null),
                            List.of(new UnifiedActivityDefinition.Benefit("ENTITLEMENT", null, version, null, 1)),
                            List.of()), 10L, 0L, 1, "CNY");
            var actor = new ActivityActor("MAIN", owner[1], owner[0], null);
            var draft = activities.saveDraft(actor, new SaveDraft(null, null, definition), id());
            var published = activities.publish(actor, draft.activityNo(), draft.revision(), id());
            var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
            var result = coordinator.claim(context, new BenefitClaimCommand(version, draft.activityNo(),
                    published.versionNo(), owner[1], owner[2]), "published");
            assertThat(result.status()).isEqualTo("COMMITTED");
            assertThat(jdbc.queryForObject("SELECT quota_used FROM pmt_activity_budget WHERE activity_no=?",
                    Long.class, draft.activityNo())).isEqualTo(1);
        } finally {
            jdbc.update("UPDATE pmt_preset_template SET enabled=0 WHERE tenant_no='MAIN' AND template_code='AP004'");
        }
    }
    @Test void aTwoBenefitBundleConsumesOneParticipationAndEachGrantExactlyOnce() {
        var owner = owner(); var first = template(owner, "ACTIVITY"); var second = template(owner, "ACTIVITY");
        var no = activity(owner, List.of(first, second));
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        var command = new BenefitClaimCommand(first, no, no + "V", owner[1], owner[2]);
        var result = coordinator.claim(context, command, "bundle");
        assertThat(result.status()).isEqualTo("COMMITTED"); assertThat(result.grants()).hasSize(2);
        assertThat(result.grants()).allSatisfy(g -> assertThat(g.instanceNos()).hasSize(1));
        assertThat(jdbc.queryForObject("SELECT quota_used FROM pmt_activity_budget WHERE activity_no=?",
                Long.class, no))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT quota_held FROM pmt_activity_budget WHERE activity_no=?",
                Long.class, no))
                .isZero();
        assertThat(coordinator.claim(context, command, "bundle")).isEqualTo(result);
    }
    @Test void failureOfTheSecondPreparationAbortsTheWholeBundleAndReturnsAllReservations() {
        var owner = owner(); var first = template(owner, "ACTIVITY"); var second = template(owner, "ACTIVITY");
        var no = activity(owner, List.of(first, second));
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        var command = new BenefitClaimCommand(first, no, no + "V", owner[1], owner[2]);
        var plan = intents.begin(context, command, "failed-bundle");
        jdbc.update("UPDATE ent_issue_policy SET enabled=0 WHERE template_no="
                + "(SELECT template_no FROM ent_template_version WHERE version_no=?)", second);
        var result = coordinator.claim(context, command, "failed-bundle");
        assertThat(result.status()).isEqualTo("ABORTED");
        assertThat(jdbc.queryForObject("SELECT quota_held FROM pmt_activity_budget WHERE activity_no=?",
                Long.class, no))
                .isZero();
        for (var child : plan.grants()) {
            assertThat(pay.query(context, owner[1], child.grantNo()).status())
                    .isEqualTo(EntitlementGrantPort.State.ABORTED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE grant_no=?", Integer.class,
                    child.grantNo())).isZero();
        }
    }
    @Test void aLostPrepareResponseKeepsMarketingAndPayCapacityUntilOriginalOperationRecovers() {
        var owner = owner(); var version = template(owner, "ACTIVITY");
        var no = activity(owner, List.of(version));
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        var command = new BenefitClaimCommand(version, no, no + "V", owner[1], owner[2]);
        var once = new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(call -> {
            Object result = call.callRealMethod();
            if (once.getAndSet(false)) throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            return result;
        }).when(pay).prepare(any(), any());
        var result = coordinator.claim(context, command, "lost");
        assertThat(result.status()).isEqualTo("PROCESSING");
        assertThat(jdbc.queryForObject("SELECT quota_held FROM pmt_activity_budget WHERE activity_no=?",
                Long.class, no))
                .isEqualTo(1);
        var plan = intents.mine(context, result.intentNo());
        assertThat(plan.status()).isEqualTo("PENDING");
        assertThat(pay.query(context, owner[1], plan.grants().getFirst().grantNo()).status())
                .isEqualTo(EntitlementGrantPort.State.PREPARED);
        assertThat(recovery.recover("MAIN",Kind.ACTIVITY_BENEFIT,0,100).completed()).isGreaterThanOrEqualTo(1);
        assertThat(coordinator.query(context,result.intentNo()).status()).isEqualTo("COMMITTED");
    }
    @Test void centerClaimUsesFrozenCoreOriginAndRetriesTheSameGrant() {
        var owner = owner(); var version = template(owner, "CENTER");
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        var request = new BenefitClaimCommand(version, null, null, owner[1], owner[2]);
        var claimed = coordinator.claim(context, request, "same-key");
        assertThat(claimed.status()).isEqualTo("COMMITTED");
        assertThat(claimed.grants()).hasSize(1);
        assertThat(claimed.grants().getFirst().instanceNos()).hasSize(1);
        assertThat(coordinator.claim(context, request, "same-key")).isEqualTo(claimed);
        String grant = claimed.grants().getFirst().grantNo();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE grant_no=?", Integer.class, grant))
                .isEqualTo(1);
        assertThat(coordinator.claim(context, request, "other-key").status()).isEqualTo("ABORTED");
        assertThat(coordinator.recover(context, claimed.intentNo())).isEqualTo(claimed);
    }
    @Test void sourceDecisionAndEventRollbackTogetherThenRepeatedDeliveryCompletesOnlyTheOriginalGrant() {
        var owner = owner(); var version = template(owner, "CENTER");
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        var plan = intents.begin(context, new BenefitClaimCommand(version, null, null, owner[1], owner[2]), "event");
        var child = plan.grants().getFirst(); pay.prepare(context, child);
        String eventNo = "BE" + plan.parentSource().grantKey().substring(0, 60);
        jdbc.execute("ALTER TABLE sys_outbox ADD CONSTRAINT ck_benefit_outbox_failure CHECK(event_no <> '"
                + eventNo + "')");
        try {
            assertThatThrownBy(() -> intents.decide(context, plan.intentNo(), true))
                    .isInstanceOf(RuntimeException.class);
            assertThat(intents.mine(context, plan.intentNo()).status()).isEqualTo("PENDING");
            assertThat(pay.query(context, owner[1], child.grantNo()).status())
                    .isEqualTo(EntitlementGrantPort.State.PREPARED);
        } finally { jdbc.execute("ALTER TABLE sys_outbox DROP CONSTRAINT ck_benefit_outbox_failure"); }
        intents.decide(context, plan.intentNo(), true);
        var event = new SysOutbox(); event.setEventNo(eventNo);
        event.setPayload(jdbc.queryForObject("SELECT payload FROM sys_outbox WHERE event_no=?", String.class, eventNo));
        consumer.consume(event); consumer.consume(event);
        assertThat(intents.mine(context, plan.intentNo()).status()).isEqualTo("COMMITTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE grant_no=?", Integer.class,
                child.grantNo())).isEqualTo(1);
        assertThatThrownBy(() -> intents.decide(context, plan.intentNo(), false)).isInstanceOf(BizException.class);
    }
    @Test void actualCoreHttpExposesOnlyPersistedBundleCommandsAndTheirDurableDecision() {
        var owner = owner(); var version = template(owner, "ACTIVITY");
        var no = activity(owner, List.of(version));
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        var plan = intents.begin(context, new BenefitClaimCommand(version, no, no + "V", owner[1], owner[2]), "rpc");
        var child = plan.grants().getFirst();
        var locator = new ConfigServiceLocator();
        locator.getTargets().put(ServiceName.PLATFORM, "http://127.0.0.1:" + port);
        var api = new InternalHttp(locator, "benefit-http-test").client(ServiceName.PLATFORM,
                CoreEntitlementInternalApi.class, Duration.ofSeconds(5));
        assertThat(api.grantSource(new EntitlementCoreInternal.GrantSourceQuery(context, child)).binding())
                .isEqualTo(child);
        var query = new EntitlementCoreInternal.SourceQuery(context, child.sourceRef());
        assertThat(api.grantDecision(query).status()).isEqualTo(
                SourceDecisionPort.State.PENDING);
        pay.prepare(context, child); intents.decide(context, plan.intentNo(), true);
        var truth = api.grantDecision(query);
        assertThat(truth.decision()).isEqualTo(intents.mine(context, plan.intentNo()).decision(child.sourceRef()));
        assertThat(truth.snapshotHash()).isEqualTo(child.benefitSnapshotHash());
    }
    @Test void forgedChildQuantityIsRejectedBeforePayClaimsAnyCapacity() {
        var owner = owner(); var version = template(owner, "CENTER");
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        var plan = intents.begin(context, new BenefitClaimCommand(version, null, null, owner[1], owner[2]), "forged");
        var child = plan.grants().getFirst();
        var forged = new EntitlementGrantPort.GrantCommand(child.grantNo(), child.sourceRef(), child.permit(),
                child.templateVersionNo(), child.recipientUserNo(), child.entityNo(), 2,
                child.benefitSnapshotHash(), null);
        assertThatThrownBy(() -> pay.prepare(context, forged)).isInstanceOf(BizException.class);
        assertThat(pay.query(context, owner[1], child.grantNo()).status())
                    .isEqualTo(EntitlementGrantPort.State.NOT_FOUND);
    }
    @Test void sameKeyCannotBeReboundToAnotherTemplateOrAnotherOwner() {
        var owner = owner(); var version = template(owner, "CENTER");
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        var request = new BenefitClaimCommand(version, null, null, owner[1], owner[2]);
        var result = coordinator.claim(context, request, "bound-key");
        var other = template(owner, "CENTER");
        assertThatThrownBy(() -> coordinator.claim(context,
                new BenefitClaimCommand(other, null, null, owner[1], owner[2]), "bound-key"))
                .isInstanceOf(BizException.class);
        var stranger = new EntitlementContext("MAIN", "other-user", id(), "a".repeat(64));
        assertThatThrownBy(() -> coordinator.recover(stranger, result.intentNo())).isInstanceOf(BizException.class);
    }
    @Test void consumerHttpClaimDerivesIdentityAndReadOnlyQueryCannotIssueAgain() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web)
                .apply(SecurityMockMvcConfigurers.springSecurity()).build();
        String phone = "126" + String.format("%08d",
                Math.floorMod(UUID.randomUUID().getLeastSignificantBits(), 100000000));
        String token = TestLogin.consumer(mvc, json, otp, phone);
        String user = tokens.of(Realm.CONSUMER).get(token).orElseThrow().user().userNo();
        var owner = owner(); String version = template(owner, "CENTER");
        var command = new BenefitClaimCommand(version, null, null, owner[1], owner[2]);
        String body = json.writeValueAsString(command);
        var denied = mvc.perform(post("/mp/entitlement-claims").header("Idempotency-Key", "http")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn().getResponse();
        assertThat(denied.getStatus()).isEqualTo(401);
        var response = mvc.perform(post("/mp/entitlement-claims").header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", "http").contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
        var data = json.readTree(response.getContentAsString()).path("data");
        assertThat(data.path("status").asString()).isEqualTo("COMMITTED");
        String intent = data.path("intentNo").asString();
        String instance = data.path("instanceNos").get(0).asString();
        assertThat(jdbc.queryForObject("SELECT user_no FROM ent_instance WHERE instance_no=?", String.class, instance))
                .isEqualTo(user);
        for (int i = 0; i < 2; i++) {
            var query = mvc.perform(get("/mp/entitlement-claims/" + intent)
                    .header("Authorization", "Bearer " + token)).andReturn().getResponse();
            assertThat(json.readTree(query.getContentAsString()).path("data")).isEqualTo(data);
            assertThat(query.getContentAsString())
                    .doesNotContain("sourceRef", "permit", "decision", "principal", "bonus");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE instance_no=?",
                Integer.class, instance))
                .isEqualTo(1);
    }
    @Test void targetedOnlyTemplateCannotBeClaimedThroughTheConsumerCenter() {
        var owner = owner(); var version = template(owner, "TARGETED");
        var context = new EntitlementContext("MAIN", owner[0], id(), "a".repeat(64));
        assertThatThrownBy(() -> coordinator.claim(context,
                new BenefitClaimCommand(version, null, null, owner[1], owner[2]), "bad-mode"))
                .isInstanceOf(BizException.class);
    }
}
