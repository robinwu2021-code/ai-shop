package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BaseEntity;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.entity.PmtActivity;
import ai.neargo.shop.promotion.entity.PmtActivityBudget;
import ai.neargo.shop.promotion.entity.PmtActivityVersion;
import ai.neargo.shop.promotion.mapper.PromotionMappers.ActivityMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityBudgetMapper;
import ai.neargo.shop.promotion.mapper.PmtActivityVersionMapper;
import ai.neargo.shop.promotion.service.ActivityCommandDeduplicator;
import ai.neargo.shop.promotion.service.ActivityParticipationService;
import ai.neargo.shop.promotion.service.impl.ActivityVersionServiceImpl.PublishedSnapshot;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationOutcomePort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real core mapper and transaction tests. The outcome owner exists only inside this fixture. */
@SpringBootTest(properties = {"shop.promotion.activity.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:activity-participation;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Import(ActivityParticipationTransactionTest.SourceConfiguration.class)
class ActivityParticipationTransactionTest {
    @Autowired ActivityParticipationService service;
    @Autowired ActivityMapper heads;
    @Autowired PmtActivityVersionMapper versions;
    @Autowired PmtActivityBudgetMapper budgets;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired FixtureSource source;
    private final String tenant = "PT" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private EntitlementContext context() {
        return new EntitlementContext(tenant, "SYSTEM", "request", "a".repeat(64));
    }
    private String seed(int personal, long quota, long budget) {
        String no = "ACT" + UUID.randomUUID().toString().replace("-", "");
        Instant now = Instant.now();
        var definition = new UnifiedActivityDefinition("fixture", "RECHARGE", "FIXTURE", 1,
                new RedemptionScope("STORES", List.of("store")), null,
                new UnifiedActivityDefinition.Schedule("ONE_OFF", now.minusSeconds(60), now.plusSeconds(3600),
                        "UTC", List.of(), null, null),
                new UnifiedActivityDefinition.Rule(1, null, List.of(), List.of(
                        new UnifiedActivityDefinition.Tier("tier", 1000, 100,
                                new RedemptionScope("STORES", List.of("store"))))), quota, budget, personal, "CNY");
        var head = new PmtActivity(); audit(head);
        head.setActivityNo(no); head.setEntityNo("entity"); head.setName("fixture"); head.setOwner("MERCHANT");
        head.setScene("RECHARGE"); head.setStatus("RUNNING"); head.setTriggerType("NONE");
        head.setBenefitType("COMBO"); head.setScheduleType("ONE_OFF"); head.setStartAt(now.toEpochMilli());
        head.setCurrentVersionNo(no + "V"); head.setDraftSnapshot(json.writeValueAsString(definition));
        heads.insert(head);
        var version = new PmtActivityVersion(); audit(version);
        version.setVersionNo(no + "V"); version.setActivityNo(no); version.setVersionIndex(1);
        version.setSchemaVersion(1); version.setCurrency("CNY"); version.setScheduleType("ONE_OFF");
        version.setStartsAt(LocalDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC));
        version.setEndsAt(LocalDateTime.ofInstant(now.plusSeconds(3600), ZoneOffset.UTC)); version.setMarketZone("UTC");
        String snapshot = json.writeValueAsString(new PublishedSnapshot(definition, null));
        version.setRuleSnapshot(snapshot); version.setRuleHash(ActivityCommandDeduplicator.hash(snapshot));
        version.setPublishedBy("SYSTEM"); version.setPublishedAt(LocalDateTime.now(ZoneOffset.UTC));
        versions.insert(version);
        var capacity = new PmtActivityBudget(); audit(capacity);
        capacity.setActivityNo(no); capacity.setCurrency("CNY"); capacity.setQuotaLimit(quota);
        capacity.setBudgetLimitMinor(budget); capacity.setPerUserLimit(personal);
        capacity.setQuotaHeld(0L); capacity.setQuotaUsed(0L);
        capacity.setBudgetHeldMinor(0L); capacity.setBudgetUsedMinor(0L); budgets.insert(capacity);
        return no;
    }
    private void audit(BaseEntity row) {
        row.setTenantNo(tenant); row.setVersion(0L); row.setDeleted(0);
        row.setCreatedAt(LocalDateTime.now(ZoneOffset.UTC)); row.setUpdatedAt(row.getCreatedAt());
        row.setCreatedBy("SYSTEM"); row.setUpdatedBy("SYSTEM");
    }
    private ReserveActivity command(String activity, String user) {
        var ref = new SourceRef("FIXTURE", "RECHARGE", UUID.randomUUID().toString(), null, null, null, 0);
        var cmd = new ReserveActivity(ref, activity, activity + "V", "tier", user, "entity", "store", "CNY", 1);
        source.intents.put(tenant + ref.grantKey(), cmd);
        return cmd;
    }
    private long count(String activity, String column) {
        return jdbc.queryForObject("SELECT " + column + " FROM pmt_activity_budget WHERE tenant_no=? AND activity_no=?",
                Long.class, tenant, activity);
    }
    @Test void confirmationAndDurableAbortSettleBothCountersExactlyOnce() {
        String activity = seed(2, 10, 1000);
        var first = command(activity, "user"); var permit = service.reserve(context(), first);
        assertThat(count(activity, "budget_held_minor")).isEqualTo(100);
        source.finish(tenant, first, ActivityParticipationOutcomePort.State.COMMITTED, "grant");
        var confirm = new ActivityParticipationPort.ConfirmParticipation(permit.reservationNo(), first.sourceRef(), "grant");
        service.confirm(context(), confirm); service.confirm(context(), confirm);
        assertThat(count(activity, "quota_used")).isEqualTo(1);
        assertThat(count(activity, "budget_used_minor")).isEqualTo(100);
        var second = command(activity, "user"); var secondPermit = service.reserve(context(), second);
        source.finish(tenant, second, ActivityParticipationOutcomePort.State.ABORTED, "abort");
        var release = new ActivityParticipationPort.ReleaseParticipation(secondPermit.reservationNo(), second.sourceRef(), "abort");
        service.release(context(), release); service.release(context(), release);
        assertThat(count(activity, "quota_held")).isZero();
        assertThat(count(activity, "quota_used")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT used_count FROM pmt_activity_participant "
                + "WHERE tenant_no=? AND activity_no=? AND user_no='user'", Integer.class, tenant, activity)).isEqualTo(1);
    }
    @Test void concurrentSourceReplayConsumesOnlyOneReservation() throws Exception {
        String activity = seed(10, 10, 1000); var cmd = command(activity, "user");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> service.reserve(context(), cmd));
            var b = workers.submit(() -> service.reserve(context(), cmd));
            assertThat(a.get().reservationNo()).isEqualTo(b.get().reservationNo());
        }
        assertThat(count(activity, "quota_held")).isEqualTo(1);
        assertThat(count(activity, "budget_held_minor")).isEqualTo(100);
    }
    @Test void personalAndGlobalFailuresRollbackAllCapacityDeltas() {
        String personal = seed(1, 10, 1000);
        service.reserve(context(), command(personal, "user"));
        assertThatThrownBy(() -> service.reserve(context(), command(personal, "user"))).isInstanceOf(BizException.class);
        assertThat(count(personal, "quota_held")).isEqualTo(1);
        String budget = seed(10, 10, 100);
        service.reserve(context(), command(budget, "user"));
        assertThatThrownBy(() -> service.reserve(context(), command(budget, "other"))).isInstanceOf(BizException.class);
        assertThat(count(budget, "quota_held")).isEqualTo(1);
        String quota = seed(10, 1, 1000);
        service.reserve(context(), command(quota, "user"));
        assertThatThrownBy(() -> service.reserve(context(), command(quota, "other"))).isInstanceOf(BizException.class);
        assertThat(count(quota, "budget_held_minor")).isEqualTo(100);
    }
    @Test void unknownRecoveryAndForgedTerminalBindingsNeverReleaseOriginalHold() {
        String activity = seed(10, 10, 1000); var cmd = command(activity, "user");
        var permit = service.reserve(context(), cmd);
        jdbc.update("UPDATE pmt_activity_reservation SET deadline=? WHERE tenant_no=? AND reservation_no=?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), tenant, permit.reservationNo());
        service.recoverPending(100);
        assertThat(count(activity, "quota_held")).isEqualTo(1);
        var other = command(activity, "other");
        source.results.put(tenant + cmd.sourceRef().grantKey(), new ActivityParticipationOutcomePort.Outcome(
                ActivityParticipationOutcomePort.State.COMMITTED, "forged", tenant, other));
        assertThatThrownBy(() -> service.confirm(context(), new ActivityParticipationPort.ConfirmParticipation(
                permit.reservationNo(), cmd.sourceRef(), "forged"))).isInstanceOf(BizException.class);
        assertThat(service.query(new EntitlementContext("otherTenant", "SYSTEM", "request", "a".repeat(64)),
                cmd.sourceRef()).status()).isEqualTo(ActivityParticipationPort.State.NOT_FOUND);
        assertThatThrownBy(() -> service.release(context(), new ActivityParticipationPort.ReleaseParticipation(
                permit.reservationNo(), other.sourceRef(), "abort"))).isInstanceOf(BizException.class);
        assertThat(count(activity, "quota_held")).isEqualTo(1);
    }
    @Test void unknownOldestReservationDoesNotStarveLaterTerminalResult() {
        String activity = seed(10, 10, 1000);
        var unknown = command(activity, "first");
        var unknownPermit = service.reserve(context(), unknown);
        var committed = command(activity, "second");
        service.reserve(context(), committed);
        source.finish(tenant, committed, ActivityParticipationOutcomePort.State.COMMITTED, "laterGrant");
        jdbc.update("UPDATE pmt_activity_reservation SET deadline=? WHERE tenant_no=? AND activity_no=?",
                LocalDateTime.now(ZoneOffset.UTC).minusDays(1), tenant, activity);
        service.recoverPending(1);
        assertThat(service.query(context(), unknown.sourceRef()).status())
                .isEqualTo(ActivityParticipationPort.State.RESERVED);
        assertThat(jdbc.queryForObject("SELECT deadline FROM pmt_activity_reservation "
                + "WHERE tenant_no=? AND reservation_no=?", LocalDateTime.class, tenant,
                unknownPermit.reservationNo())).isAfter(LocalDateTime.now(ZoneOffset.UTC).plusMinutes(4));
        service.recoverPending(1);
        assertThat(service.query(context(), committed.sourceRef()).status())
                .isEqualTo(ActivityParticipationPort.State.CONFIRMED);
        assertThat(count(activity, "quota_held")).isEqualTo(1);
        assertThat(count(activity, "quota_used")).isEqualTo(1);
    }
    @TestConfiguration static class SourceConfiguration {
        @Bean FixtureSource fixtureSource() { return new FixtureSource(); }
    }
    static class FixtureSource implements ActivityParticipationOutcomePort {
        final Map<String, ReserveActivity> intents = new ConcurrentHashMap<>();
        final Map<String, Outcome> results = new ConcurrentHashMap<>();
        public boolean supports(SourceRef ref) { return "FIXTURE".equals(ref.sourceSystem()); }
        public void validateSource(EntitlementContext ctx, ReserveActivity cmd) {
            if (!cmd.equals(intents.get(ctx.tenantNo() + cmd.sourceRef().grantKey())))
                throw new IllegalArgumentException("Untrusted source bindings");
        }
        public Outcome outcome(EntitlementContext ctx, SourceRef ref) {
            return results.getOrDefault(ctx.tenantNo() + ref.grantKey(), new Outcome(State.UNKNOWN, null, null, null));
        }
        void finish(String tenant, ReserveActivity cmd, State state, String fact) {
            results.put(tenant + cmd.sourceRef().grantKey(), new Outcome(state, fact, tenant, cmd));
        }
    }
}
