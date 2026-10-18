package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.service.EntitlementCatalogService;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.ReferenceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.Validity;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** A closed switch prevents new rights definitions without breaking durable replies or management shutdown. */
@SpringBootTest(properties = {
        "shop.pay.entitlement.catalog.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:ent-catalog-off;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@ActiveProfiles("test")
class EntitlementCatalogGateFlowTest {
    @Autowired private EntitlementCatalogService catalog;
    @Autowired private EntitlementCatalogPort references;
    @Autowired private PayEntitlementProperties properties;
    @Autowired private JdbcTemplate jdbc;
    private CatalogScope scope;

    @BeforeEach
    void fixture() {
        scope = new CatalogScope("GATE-" + UUID.randomUUID().toString().substring(0, 8),
                "ENTITY", "GATE_TEST", false);
    }

    @AfterEach
    void cleanup() {
        properties.getEntitlement().getCatalog().setEnabled(false);
        for (String table : List.of("ent_template_version", "ent_template", "pay_ent_command_dedup")) {
            jdbc.update("DELETE FROM " + table + " WHERE tenant_no=?", scope.tenantNo());
        }
    }

    @Test
    void disabledCatalogRejectsCreateAndRollsBackTheCommandClaim() {
        disabled(() -> catalog.create(scope, definition(), "new-create"));
        assertThat(rows("ent_template")).isZero();
        assertThat(rows("pay_ent_command_dedup")).isZero();
    }

    @Test
    void closingCatalogPreservesHistoryAndExactRepliesButBlocksNewWork() {
        properties.getEntitlement().getCatalog().setEnabled(true);
        var definition = definition();
        var draft = catalog.create(scope, definition, "original-create");
        var published = catalog.publish(scope, draft.templateNo(), draft.revision(), "original-publish");
        var head = catalog.get(scope, draft.templateNo());
        properties.getEntitlement().getCatalog().setEnabled(false);

        assertThat(catalog.create(scope, definition, "original-create")).isEqualTo(draft);
        assertThat(catalog.publish(scope, draft.templateNo(), draft.revision(), "original-publish"))
                .isEqualTo(published);
        assertThat(catalog.get(scope, draft.templateNo()).status()).isEqualTo("ACTIVE");
        assertThat(references.getPublishedVersion(scope.tenantNo(), scope.entityNo(), published.versionNo())
                .snapshotHash()).isEqualTo(published.snapshotHash());

        disabled(() -> catalog.create(scope, definition, "second-create"));
        disabled(() -> catalog.saveDraft(scope, head.templateNo(), head.revision(), definition, "new-draft"));
        disabled(() -> catalog.publish(scope, head.templateNo(), head.revision(), "new-publish"));
        disabled(() -> references.validateReference(new ReferenceQuery(scope.tenantNo(), scope.entityNo(),
                published.versionNo(), "CNY", "ACTIVITY_GRANT")));

        var paused = catalog.changeStatus(scope, head.templateNo(), head.revision(), "PAUSED", "pause");
        disabled(() -> catalog.changeStatus(scope, head.templateNo(), paused.revision(), "ACTIVE", "resume"));
        assertThat(catalog.changeStatus(scope, head.templateNo(), paused.revision(), "ENDED", "end").status())
                .isEqualTo("ENDED");
        assertThat(rows("ent_template")).isEqualTo(1);
        assertThat(rows("ent_template_version")).isEqualTo(1);
        assertThat(rows("pay_ent_command_dedup")).isEqualTo(4);
    }

    private long rows(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_no=?",
                Long.class, scope.tenantNo());
    }

    private void disabled(Runnable command) {
        assertThatThrownBy(command::run).isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
    }

    private EntitlementDefinition definition() {
        return new EntitlementDefinition("Care coupon", "ORDER_COUPON", "MERCHANT", "CNY", "CASH",
                500L, null, null, 0L, null, "COUNT", 1, null,
                new Validity("RELATIVE", null, null, 30, "GRANTED", "ROLLING_DAYS", "Asia/Shanghai"),
                new RedemptionScope("ALL", List.of()), List.of("ACTIVITY"));
    }
}
