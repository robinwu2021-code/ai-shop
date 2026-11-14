package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.common.data.scope.DataScopeContext;
import ai.neargo.common.data.scope.DataScopeSpec;
import ai.neargo.shop.auth.ScopeDim;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Evidence;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateRevisionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingConfigMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingRevisionMapper;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import ai.neargo.shop.trade.service.impl.PricingRuntimeConfigServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class PricingRuntimeConfigServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
    private final Scope scope = new Scope("MAIN", "E", "A");
    private final StorePricingConfigMapper heads = mock(StorePricingConfigMapper.class);
    private final StorePricingRevisionMapper revisions = mock(StorePricingRevisionMapper.class);
    private final PricingTemplateRevisionMapper templates = mock(PricingTemplateRevisionMapper.class);
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private PricingRuntimeConfigServiceImpl service() {
        return new PricingRuntimeConfigServiceImpl(heads, revisions, templates,
                beans.getBeanProvider(PricingConfigEvidencePort.class), Clock.fixed(NOW, ZoneOffset.UTC));
    }
    private void rows(PricingRuntimeFixture.Rows rows) {
        when(heads.selectList(any())).thenReturn(List.of(rows.head()));
        when(revisions.selectOne(any())).thenReturn(rows.revision()); when(templates.selectOne(any())).thenReturn(rows.template());
    }
    private Evidence evidence(PricingConfigEvidencePort.Scope requested) {
        return new Evidence(requested, PricingRuntimeFixture.CAPS, "CAP", NOW.plusSeconds(180), Set.of(),
                List.of(new PricingSourceRef("OWNER", "CAPABILITY", "CAP", 1, null, null)));
    }
    @Test void readsImmutablePublishedDefinitionAndIgnoresWorkingDraftChanges() throws Exception {
        var rows = PricingRuntimeFixture.rows(); rows(rows);
        beans.registerSingleton("owner", (PricingConfigEvidencePort) this::evidence);
        var first = service().resolve(scope, "FOOD");
        rows.head().setDraftOverride("{\"unpublished\":true}"); rows.head().setDraftVersion(999L);
        assertThat(service().resolve(scope, "FOOD").snapshotHash()).isEqualTo(first.snapshotHash());
        assertThat(first.config().configHash()).isEqualTo(rows.revision().getEffectiveHash());
    }
    @Test void missingAndAmbiguousEvidenceNeverBecomeZeroOrMaximumCapabilities() throws Exception {
        rows(PricingRuntimeFixture.rows());
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
        beans.registerSingleton("one", (PricingConfigEvidencePort) this::evidence);
        beans.registerSingleton("two", (PricingConfigEvidencePort) this::evidence);
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
    }
    @Test void expiredForeignOrConflictingOwnerVersionsAreRejected() throws Exception {
        rows(PricingRuntimeFixture.rows());
        beans.registerSingleton("owner", (PricingConfigEvidencePort) requested -> new Evidence(requested,
                PricingRuntimeFixture.CAPS, "CAP", NOW, Set.of(), evidence(requested).sources()));
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
        beans.destroySingleton("owner");
        beans.registerSingleton("owner", (PricingConfigEvidencePort) requested -> new Evidence(
                new PricingConfigEvidencePort.Scope("MAIN", "OTHER", "A", "CONF", false),
                PricingRuntimeFixture.CAPS, "CAP", NOW.plusSeconds(180), Set.of(), evidence(requested).sources()));
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
        beans.destroySingleton("owner");
        beans.registerSingleton("owner", (PricingConfigEvidencePort) requested -> new Evidence(requested,
                PricingRuntimeFixture.CAPS, "CAP", NOW.plusSeconds(180), Set.of(),
                List.of(new PricingSourceRef("OWNER", "CAPABILITY", "CAP", 1, null, null),
                        new PricingSourceRef("OWNER", "CAPABILITY", "CAP", 2, null, null))));
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
    }
    @Test void ownerCapabilityDowngradeAndFrozenSnapshotTamperingBlockConsumption() throws Exception {
        var rows = PricingRuntimeFixture.rows(); rows(rows);
        beans.registerSingleton("owner", (PricingConfigEvidencePort) requested -> new Evidence(requested,
                new PricingCapabilities(0, 0, false, false, false), "CAP", NOW.plusSeconds(180), Set.of(),
                evidence(requested).sources()));
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
        beans.destroySingleton("owner"); beans.registerSingleton("owner", (PricingConfigEvidencePort) this::evidence);
        rows.revision().setEffectiveHash("0".repeat(64));
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
    }
    @Test void activePointerChangeDuringOwnerReadIsAConflict() throws Exception {
        var rows = PricingRuntimeFixture.rows(); rows(rows);
        var changed = PricingRuntimeFixture.rows().head(); changed.setActiveRevisionNo("OTHER");
        when(heads.selectList(any())).thenReturn(List.of(rows.head()), List.of(changed));
        beans.registerSingleton("owner", (PricingConfigEvidencePort) this::evidence);
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void foreignIndustryOldSchemaOrRuleRegistryCannotBeReinterpretedAsCurrent() throws Exception {
        var rows = PricingRuntimeFixture.rows(); rows(rows);
        beans.registerSingleton("owner", (PricingConfigEvidencePort) this::evidence);
        assertThatThrownBy(() -> service().resolve(scope, "RETAIL")).isInstanceOf(BizException.class);
        rows.revision().setSchemaVersion(2);
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
        rows.revision().setSchemaVersion(3); rows.revision().setRuleCatalogVersion("OLD");
        assertThatThrownBy(() -> service().resolve(scope, "FOOD")).isInstanceOf(BizException.class);
    }
    @Test void evidenceSetOrderingDoesNotCreateFalseConfigurationChanges() throws Exception {
        rows(PricingRuntimeFixture.rows());
        var calls = new AtomicInteger();
        beans.registerSingleton("owner", (PricingConfigEvidencePort) requested -> {
            var a = new PricingSourceRef("OWNER", "CAPABILITY", "A", 1, null, null);
            var b = new PricingSourceRef("OWNER", "CAPABILITY", "B", 1, null, null);
            return new Evidence(requested, PricingRuntimeFixture.CAPS, "CAP", NOW.plusSeconds(180),
                    Set.of("PROFILE_A", "PROFILE_B"), calls.incrementAndGet() == 1 ? List.of(a, b) : List.of(b, a));
        });
        assertThat(service().resolve(scope, "FOOD").snapshotHash())
                .isEqualTo(service().resolve(scope, "FOOD").snapshotHash());
    }
    @Test void jsonStorageFormattingDoesNotChangePublishedSemantics() throws Exception {
        var rows = PricingRuntimeFixture.rows();
        var json = JsonMapper.builder().build();
        rows.template().setDefinitionSnapshot(json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(json.readTree(rows.template().getDefinitionSnapshot())));
        rows.revision().setEffectiveSnapshot(json.writerWithDefaultPrettyPrinter()
                .writeValueAsString(json.readTree(rows.revision().getEffectiveSnapshot())));
        assertThat(PricingRuntimeConfigPolicy.verify(rows.revision(), rows.template(), PricingRuntimeFixture.CAPS)
                .configHash()).isEqualTo(rows.revision().getEffectiveHash());
    }
    @Test void ownerCallbackPreservesCallerDataScope() throws Exception {
        rows(PricingRuntimeFixture.rows());
        var caller = DataScopeSpec.of(ScopeDim.SELF, Set.of("ACTOR"));
        beans.registerSingleton("owner", (PricingConfigEvidencePort) requested -> {
            assertThat(DataScopeContext.isSkipped()).isFalse();
            assertThat(DataScopeContext.current()).isSameAs(caller);
            return evidence(requested);
        });
        DataScopeContext.set(caller);
        try {
            service().resolve(scope, "FOOD");
            assertThat(DataScopeContext.isSkipped()).isFalse();
            assertThat(DataScopeContext.current()).isSameAs(caller);
        } finally { DataScopeContext.clear(); }
    }
    @Test void purePolicyDetectsChangedTemplateOrEffectiveContent() throws Exception {
        var rows = PricingRuntimeFixture.rows();
        rows.template().setDefinitionHash("f".repeat(64));
        assertThatThrownBy(() -> PricingRuntimeConfigPolicy.verify(rows.revision(), rows.template(),
                PricingRuntimeFixture.CAPS)).isInstanceOf(RuntimeException.class);
        var bad = PricingRuntimeFixture.rows(); bad.revision().setEffectiveSnapshot("{}");
        assertThatThrownBy(() -> PricingRuntimeConfigPolicy.verify(bad.revision(), bad.template(),
                PricingRuntimeFixture.CAPS)).isInstanceOf(RuntimeException.class);
    }
}
