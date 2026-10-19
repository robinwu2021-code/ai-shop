package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementGrantService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.spi.entitlement.DecisionRef;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.EntitlementGrantSourcePort;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort;
import ai.neargo.shop.spi.entitlement.SourceRef;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Source owners are fixtures; grant/capacity/instances/ledger/outbox and transaction boundaries are real. */
@SpringBootTest(properties = {"shop.pay.entitlement.catalog.enabled=true", "shop.pay.entitlement.issue.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-grant-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementGrantFlowTest {
    @Autowired EntitlementCatalogService catalog;
    @Autowired EntitlementIssuePolicyService policies;
    @Autowired EntitlementGrantService grants;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EntitlementGrantSourcePort source;
    @MockitoBean SourceDecisionPort decisions;
    private final CatalogScope scope = new CatalogScope("MAIN", "GR-" + UUID.randomUUID(), "GRANT-TEST", false);
    private String key() { return UUID.randomUUID().toString(); }
    private EntitlementContext context() { return new EntitlementContext("MAIN", scope.actorNo(), key(), "a".repeat(64)); }
    private EntitlementGrantPort.GrantCommand command() { return command(1, null, 500L); }
    private EntitlementGrantPort.GrantCommand command(int uses, Long cap, long exposureLimit) {
        var d = new EntitlementDefinition("coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                cap, null, 0L, null, "COUNT", uses, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("TARGETED"));
        var h = catalog.create(scope, d, key()); var v = catalog.publish(scope, h.templateNo(), h.revision(), key());
        policies.save(scope, h.templateNo(), new SavePolicy(0, 5L, exposureLimit, 2, true), key());
        return new EntitlementGrantPort.GrantCommand(key(), new SourceRef("CORE", "TARGETED", key(), null,
                null, null, 1), null, v.versionNo(), "U1", scope.entityNo(), 2, "b".repeat(64), null);
    }
    private void origin(EntitlementGrantPort.GrantCommand c) {
        when(source.supports(any())).thenReturn(true);
        when(source.resolve(any(), any())).thenReturn(new EntitlementGrantSourcePort.SourceFact("MAIN", c, null));
    }
    private DecisionRef decision(EntitlementGrantPort.GrantCommand c, boolean commit) {
        var d = new DecisionRef(key(), Instant.now(), 1,
                commit ? DecisionRef.DecisionType.GRANT_COMMIT : DecisionRef.DecisionType.GRANT_ABORT);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(c.sourceRef(),
                commit ? SourceDecisionPort.State.COMMITTED : SourceDecisionPort.State.CANCELLED, d,
                c.recipientUserNo(), c.entityNo(), null, "CNY", 0L, c.benefitSnapshotHash()));
        return d;
    }
    @Test void prepareCommitReplayAndSourceUniquenessIssueExactlyTwoInstancesWithLedgerAndOutbox() {
        var c = command(); origin(c);
        assertThat(grants.prepare(context(), c).status()).isEqualTo(EntitlementGrantPort.State.PREPARED);
        assertThat(grants.prepare(context(), c).status()).isEqualTo(EntitlementGrantPort.State.PREPARED);
        var d = decision(c, true);
        var committed = grants.commit(context(), new EntitlementGrantPort.GrantDecision(c.grantNo(), c.sourceRef(), d));
        assertThat(committed.instanceNos()).hasSize(2);
        assertThat(grants.commit(context(), new EntitlementGrantPort.GrantDecision(c.grantNo(), c.sourceRef(), d)))
                .isEqualTo(committed);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_ledger WHERE instance_no IN (?,?)", Integer.class,
                committed.instanceNos().get(0), committed.instanceNos().get(1))).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE aggregate_no=?", Integer.class,
                c.grantNo())).isEqualTo(1);
        var different = new EntitlementGrantPort.GrantCommand(key(), c.sourceRef(), null, c.templateVersionNo(),
                c.recipientUserNo(), c.entityNo(), 2, c.benefitSnapshotHash(), null);
        origin(different);
        assertThatThrownBy(() -> grants.prepare(context(), different)).isInstanceOf(BizException.class);
        assertThat(grants.query(context(), c.entityNo(), c.grantNo())).isEqualTo(committed);
        var abort = new DecisionRef(key(), Instant.now(), 1, DecisionRef.DecisionType.GRANT_ABORT);
        assertThatThrownBy(() -> grants.abort(context(), new EntitlementGrantPort.AbortGrant(c.grantNo(),
                c.sourceRef(), abort))).isInstanceOf(BizException.class);
    }
    @Test void unknownCannotReleaseAndTrustedAbortReturnsCapacityWithoutIssuing() {
        var c = command(); origin(c); grants.prepare(context(), c);
        when(decisions.query(any(), any())).thenReturn(new SourceDecisionPort.SourceDecision(c.sourceRef(),
                SourceDecisionPort.State.UNKNOWN, null, null, null, null, null, null, null));
        var attempted = new DecisionRef(key(), Instant.now(), 1, DecisionRef.DecisionType.GRANT_ABORT);
        assertThat(grants.abort(context(), new EntitlementGrantPort.AbortGrant(c.grantNo(), c.sourceRef(), attempted))
                .status()).isEqualTo(EntitlementGrantPort.State.PROCESSING);
        var d = decision(c, false);
        assertThat(grants.abort(context(), new EntitlementGrantPort.AbortGrant(c.grantNo(), c.sourceRef(), d)).status())
                .isEqualTo(EntitlementGrantPort.State.ABORTED);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE grant_no=?", Integer.class,
                c.grantNo())).isZero();
        assertThat(jdbc.queryForObject("SELECT held_count FROM ent_issue_policy WHERE template_no="
                + "(SELECT template_no FROM ent_template_version WHERE version_no=?)", Long.class,
                c.templateVersionNo())).isZero();
    }
    @Test void forgedOriginAndTenantFailBeforeClaimingCapacity() {
        var c = command(); when(source.supports(any())).thenReturn(true);
        when(source.resolve(any(), any())).thenReturn(new EntitlementGrantSourcePort.SourceFact("OTHER", c, null));
        assertThatThrownBy(() -> grants.prepare(context(), c)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE grant_no=?", Integer.class,
                c.grantNo())).isZero();
    }
    @Test void concurrentSameGrantConsumesAndIssuesOnlyOnce() throws Exception {
        var c = command(); origin(c);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var preparations = new ArrayList<Callable<EntitlementGrantPort.GrantView>>();
            for (int i = 0; i < 20; i++) preparations.add(() -> grants.prepare(context(), c));
            for (var result : pool.invokeAll(preparations)) {
                assertThat(result.get().status()).isEqualTo(EntitlementGrantPort.State.PREPARED);
            }
            var d = decision(c, true);
            var commits = new ArrayList<Callable<EntitlementGrantPort.GrantView>>();
            for (int i = 0; i < 20; i++) commits.add(() -> grants.commit(context(),
                    new EntitlementGrantPort.GrantDecision(c.grantNo(), c.sourceRef(), d)));
            for (var result : pool.invokeAll(commits)) assertThat(result.get().instanceNos()).hasSize(2);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE grant_no=?", Integer.class,
                c.grantNo())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pay_ent_outbox WHERE aggregate_no=?", Integer.class,
                c.grantNo())).isEqualTo(1);
    }
    @Test void eventInsertFailureRollsBackInstancesLedgerAndSettlementThenSameGrantCanRetry() {
        var c = command(); origin(c); grants.prepare(context(), c); var d = decision(c, true);
        jdbc.execute("ALTER TABLE pay_ent_outbox ADD CONSTRAINT ck_grant_fixture_failure CHECK (aggregate_no <> '"
                + c.grantNo() + "')");
        try {
            assertThatThrownBy(() -> grants.commit(context(), new EntitlementGrantPort.GrantDecision(
                    c.grantNo(), c.sourceRef(), d))).isInstanceOf(RuntimeException.class);
            assertThat(grants.query(context(), c.entityNo(), c.grantNo()).status())
                    .isEqualTo(EntitlementGrantPort.State.PREPARED);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE grant_no=?", Integer.class,
                    c.grantNo())).isZero();
            assertThat(jdbc.queryForObject("SELECT held_count FROM ent_issue_policy WHERE template_no="
                    + "(SELECT template_no FROM ent_template_version WHERE version_no=?)", Long.class,
                    c.templateVersionNo())).isEqualTo(2);
        } finally { jdbc.execute("ALTER TABLE pay_ent_outbox DROP CONSTRAINT ck_grant_fixture_failure"); }
        assertThat(grants.commit(context(), new EntitlementGrantPort.GrantDecision(c.grantNo(), c.sourceRef(), d))
                .instanceNos()).hasSize(2);
    }
    @Test void archivedTemplateRejectsNewPreparationEvenWhenItsOldStatusIsActive() {
        var c = command(); origin(c);
        jdbc.update("UPDATE ent_template SET archived_at=CURRENT_TIMESTAMP WHERE current_version_no=?",
                c.templateVersionNo());
        assertThatThrownBy(() -> grants.prepare(context(), c)).isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE grant_no=?", Integer.class,
                c.grantNo())).isZero();
    }

    @Test void exposureCoversAllUsesAndCashFaceIsNotReducedByAPercentageCap() {
        var c = command(3, 1L, 1000L); origin(c); grants.prepare(context(), c);
        assertThat(jdbc.queryForObject("SELECT held_exposure_minor FROM ent_issue_policy WHERE template_no="
                + "(SELECT template_no FROM ent_template_version WHERE version_no=?)", Long.class,
                c.templateVersionNo())).isEqualTo(600);
    }

}
