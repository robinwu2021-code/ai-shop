package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews.CreateBatch;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssueBatchService;
import ai.neargo.shop.pay.entitlement.service.EntitlementIssuePolicyService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort;
import java.util.List;
import java.util.UUID;
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

/** Only core audience resolution is a fixture: each durable result, grant, instance and counter is real. */
@SpringBootTest(properties = {"shop.pay.entitlement.catalog.enabled=true", "shop.pay.entitlement.issue.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:ent-batch-flow;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
class EntitlementIssueBatchFlowTest {
    @Autowired EntitlementCatalogService catalog;
    @Autowired EntitlementIssuePolicyService policies;
    @Autowired EntitlementIssueBatchService batches;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EntitlementIssueAudiencePort audiences;
    private final CatalogScope scope = new CatalogScope("MAIN", "BATCH-" + UUID.randomUUID(), "BATCH-TEST", false);
    private String key() { return UUID.randomUUID().toString(); }
    private String version() {
        var d = new EntitlementDefinition("coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH", 100L,
                null, null, 0L, null, "COUNT", 1, null,
                new EntitlementDefinition.Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "UTC"),
                new EntitlementDefinition.RedemptionScope("ALL", List.of()), List.of("TARGETED"));
        var h = catalog.create(scope, d, key()); var v = catalog.publish(scope, h.templateNo(), h.revision(), key());
        policies.save(scope, h.templateNo(), new SavePolicy(0, 2L, 200L, 1, true), key()); return v.versionNo();
    }
    @Test void boundedResumeKeepsStableRecipientsAndCapacityFailuresLeaveNoPartialGrant() {
        String version = version(); String ref = key();
        when(audiences.resolve(any(), any(), any())).thenReturn(new EntitlementIssueAudiencePort.AudienceSnapshot(
                "MAIN", scope.entityNo(), ref, List.of(
                new EntitlementIssueAudiencePort.Recipient("U1", true, null),
                new EntitlementIssueAudiencePort.Recipient("U2", false, "NOT_ELIGIBLE"),
                new EntitlementIssueAudiencePort.Recipient("U3", true, null),
                new EntitlementIssueAudiencePort.Recipient("U4", true, null))));
        var c = new CreateBatch(version, ref); String k = key(); var created = batches.create(scope, c, k);
        assertThat(batches.resume(scope, created.batchNo(), 1).issuedCount()).isEqualTo(1);
        assertThat(batches.create(scope, c, k)).isEqualTo(created);
        var finished = batches.resume(scope, created.batchNo(), 100);
        assertThat(finished.status()).isEqualTo("PARTIAL"); assertThat(finished.pendingCount()).isZero();
        assertThat(finished.issuedCount()).isEqualTo(2); assertThat(finished.skippedCount()).isEqualTo(2);
        assertThat(finished.results()).hasSize(4);
        assertThat(finished.results().get(1).reasonCode()).isEqualTo("NOT_ELIGIBLE");
        assertThat(batches.resume(scope, created.batchNo(), 100)).isEqualTo(finished);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_grant WHERE entity_no=?", Integer.class,
                scope.entityNo())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_instance WHERE entity_no=?", Integer.class,
                scope.entityNo())).isEqualTo(2);
        assertThatThrownBy(() -> batches.get(new CatalogScope("MAIN", "OTHER", "X", false), created.batchNo()))
                .isInstanceOf(BizException.class);
    }
    @Test void rejectsCrossOwnerAudienceBeforeCreatingABatch() {
        String version = version(); String ref = key();
        when(audiences.resolve(any(), any(), any())).thenReturn(new EntitlementIssueAudiencePort.AudienceSnapshot(
                "MAIN", "OTHER", ref, List.of(new EntitlementIssueAudiencePort.Recipient("U1", true, null))));
        assertThatThrownBy(() -> batches.create(scope, new CreateBatch(version, ref), key()))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ent_issue_batch WHERE entity_no=?", Integer.class,
                scope.entityNo())).isZero();
    }
}
