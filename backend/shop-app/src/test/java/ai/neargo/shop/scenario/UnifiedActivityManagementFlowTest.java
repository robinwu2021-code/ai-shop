package ai.neargo.shop.scenario;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.promotion.dto.ActivityActor;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition.Benefit;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition.Rule;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition.Schedule;
import ai.neargo.shop.promotion.dto.UnifiedActivityDefinition.Trigger;
import ai.neargo.shop.promotion.dto.UnifiedActivityViews.SaveDraft;
import ai.neargo.shop.promotion.service.ActivityCapabilityEvidence;
import ai.neargo.shop.promotion.service.ActivityGovernanceService;
import ai.neargo.shop.promotion.service.ActivityVersionService;
import ai.neargo.shop.promotion.service.EntitlementScopeValidationService;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition.RedemptionScope;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Persistent management flow; only external owner admission is a fixture. No command or mapper mocks. */
@SpringBootTest(properties = {"shop.promotion.activity.enabled=true", "shop.promotion.presets.enabled=true",
        "spring.datasource.url=jdbc:h2:mem:activity-management;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@ActiveProfiles("test")
@Import(UnifiedActivityManagementFlowTest.Readiness.class)
@Transactional
class UnifiedActivityManagementFlowTest {
    @Autowired ActivityVersionService service;
    @Autowired ActivityGovernanceService operations;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean EntitlementScopeValidationService scope;
    private final ActivityActor actor = new ActivityActor("MAIN", "M-MGMT-" + UUID.randomUUID(), "U-MGMT", null);
    @BeforeEach void enableFixturePreset() {
        jdbc.update("UPDATE pmt_preset_template SET enabled=1 WHERE tenant_no='MAIN' AND template_code='AP009'");
    }
    private String key() { return "K" + UUID.randomUUID().toString().replace("-", ""); }
    private UnifiedActivityDefinition definition(boolean permanent) {
        return new UnifiedActivityDefinition("满额优惠", "ORDER", "AP009", 1,
                new RedemptionScope("STORES", List.of("ST-MGMT")), null,
                new Schedule(permanent ? "ALWAYS_ON" : "ONE_OFF", Instant.now().minusSeconds(30),
                        permanent ? null : Instant.now().plusSeconds(3600), "UTC", List.of(), null, null),
                new Rule(1, new Trigger("AMOUNT", 1000L, null),
                        List.of(new Benefit("CUT", 100L, null, null, null)), List.of()), 10L, 1000L, 1, "CNY");
    }
    @Test void draftNullClearPublishPauseResumeAndEndPersistAndReplay() {
        var draft = service.saveDraft(actor, new SaveDraft(null, null, definition(false)), key());
        var edited = service.saveDraft(actor, new SaveDraft(draft.activityNo(), draft.revision(), definition(true)), key());
        assertThat(edited.revision()).isGreaterThan(draft.revision());
        assertThat(jdbc.queryForObject("SELECT end_at FROM pmt_activity WHERE activity_no=?", Long.class,
                draft.activityNo())).isNull();
        String publishKey = key();
        var version = service.publish(actor, draft.activityNo(), edited.revision(), publishKey);
        var running = service.get(actor, draft.activityNo());
        assertThat(running.status()).isEqualTo("RUNNING");
        assertThat(running.currentVersionNo()).isEqualTo(version.versionNo());
        var paused = service.changeStatus(actor, draft.activityNo(), running.revision(), "PAUSED", "暂停检查", key());
        assertThat(service.get(actor, draft.activityNo()).status()).isEqualTo("PAUSED");
        assertThatThrownBy(() -> service.saveDraft(actor,
                new SaveDraft(draft.activityNo(), paused.revision(), definition(true)), key())).isInstanceOf(BizException.class);
        var resumed = service.changeStatus(actor, draft.activityNo(), paused.revision(), "RUNNING", "检查完成", key());
        var ended = service.changeStatus(actor, draft.activityNo(), resumed.revision(), "ENDED", "活动结束", key());
        assertThat(service.get(actor, draft.activityNo()).status()).isEqualTo("ENDED");
        assertThat(service.publish(actor, draft.activityNo(), edited.revision(), publishKey)).isEqualTo(version);
        assertThatThrownBy(() -> service.changeStatus(actor, draft.activityNo(), ended.revision(), "RUNNING", "不能恢复", key()))
                .isInstanceOf(BizException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_activity_version WHERE activity_no=?", Integer.class,
                draft.activityNo())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pmt_activity_budget WHERE activity_no=?", Integer.class,
                draft.activityNo())).isEqualTo(1);
    }
    @Test void statusPublicationAndOperationsStopSharePersistentVersionAndRevision() {
        var draft = service.saveDraft(actor, new SaveDraft(null, null, definition(false)), key());
        var running = service.changeStatus(actor, draft.activityNo(), draft.revision(), "RUNNING", "发布活动", key());
        assertThat(running.currentVersionNo()).isNotBlank();
        var stopped = operations.stop("MAIN", "OP-MGMT", draft.activityNo(), running.revision(), "运营停止检查", key());
        assertThat(stopped.activity().status()).isEqualTo("ENDED");
        assertThat(service.get(actor, draft.activityNo()).status()).isEqualTo("ENDED");
        var foreign = new ActivityActor("MAIN", "M-FOREIGN", "U-MGMT", null);
        assertThatThrownBy(() -> service.get(foreign, draft.activityNo())).isInstanceOf(BizException.class);
        var restricted = new ActivityActor("MAIN", actor.entityNo(), "U-MGMT", Set.of("ST-OTHER"));
        assertThatThrownBy(() -> service.get(restricted, draft.activityNo())).isInstanceOf(BizException.class);
    }
    @TestConfiguration static class Readiness {
        @Bean ActivityCapabilityEvidence managementFixtureEvidence() {
            return actor -> EnumSet.allOf(ActivityCapability.class);
        }
    }
}
