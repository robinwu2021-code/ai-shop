package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.product.GoodsPricingPolicyFacts;
import ai.neargo.shop.spi.product.GoodsPricingPolicyPort;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort;
import ai.neargo.shop.spi.trade.PricingSourceFacts;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Component;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Kind;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Snapshot;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.trade.dto.PricingPreparation.RuntimeConfig;
import ai.neargo.shop.trade.policy.PricingRuntimeConfigPolicy;
import ai.neargo.shop.trade.service.PricingRuntimeConfigService;
import ai.neargo.shop.trade.service.PricingSourceService;
import ai.neargo.shop.trade.service.impl.PricingPreparationServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PricingPreparationServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:00:00Z");
    private final Scope scope = new Scope("MAIN", "E", "A");
    private final PricingSourceRef source = new PricingSourceRef("FOOD", "CART", "C", 4, null, null);
    private final PricingSourceService sources = mock(PricingSourceService.class);
    private final PricingRuntimeConfigService rules = mock(PricingRuntimeConfigService.class);
    private final PricingTaxSettingPort taxes = mock(PricingTaxSettingPort.class);
    private final GoodsPricingPolicyPort catalog = mock(GoodsPricingPolicyPort.class);
    private final PricingPreparationServiceImpl service = new PricingPreparationServiceImpl(sources, rules, taxes, catalog,
            Clock.fixed(NOW, ZoneOffset.UTC));
    private Snapshot source(String subject, long amount) {
        return PricingSourceFacts.freeze(scope, subject, source, "POS", "a".repeat(64), List.of(new Component(Kind.MAIN,
                new PricingUnit("L", null, "G", "S", "V", "L", BigDecimal.ZERO, BigDecimal.ONE,
                        new MoneyMinor("CNY", amount), new MoneyMinor("CNY", amount)))));
    }
    private GoodsPricingPolicyPort.Snapshot catalog(long version) {
        return GoodsPricingPolicyFacts.freeze(new GoodsPricingPolicyPort.Scope("MAIN", "E", "A"),
                List.of(new GoodsPricingPolicyPort.Policy("P", "G", "S", version, true, TaxClass.STANDARD)));
    }
    private MerchantTaxSnapshot tax(long managedVersion) {
        var definition = new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE);
        return new MerchantTaxSnapshot("MAIN", "E", "A", managedVersion, MerchantTaxSnapshot.Source.PLATFORM_DEFAULT,
                "PLATFORM", 1, 500, PriceTaxMode.EXCLUSIVE, definition.snapshotHash("PLATFORM_DEFAULT:PLATFORM", 1));
    }
    private RuntimeConfig config(String digest) throws Exception {
        var rows = PricingRuntimeFixture.rows();
        var evidence = new PricingConfigEvidencePort.Evidence(new PricingConfigEvidencePort.Scope("MAIN", "E", "A",
                "CONF", false), PricingRuntimeFixture.CAPS, "CAP", NOW.plusSeconds(180), Set.of(), List.of(source));
        var compiled = PricingRuntimeConfigPolicy.verify(rows.revision(), rows.template(),
                evidence.capabilities());
        return new RuntimeConfig(scope, "FOOD", "CONF", "R", 1, "TR", "PRICING_RULE_SPEC_V5",
                rows.revision().getRuleCatalogVersion(), compiled, evidence, digest);
    }
    private void ready() throws Exception {
        when(sources.staff(scope, source)).thenReturn(source(null, 100));
        when(rules.resolve(scope, "FOOD")).thenReturn(config("b".repeat(64)));
        when(taxes.resolve(any())).thenReturn(tax(0));
        when(catalog.resolve(any(), any())).thenReturn(catalog(1));
    }
    @Test void bindsStableSourcesRulesOwnerEvidenceAndManagedTaxWithoutReadClockInDigest() throws Exception {
        ready(); var prepared = service.staff(scope, source);
        assertThat(prepared.tax().rateBp()).isEqualTo(500);
        assertThat(prepared.expiresAt()).isEqualTo(NOW.plusSeconds(180));
        assertThat(service.staff(scope, source).preparationHash()).isEqualTo(prepared.preparationHash());
        verify(sources, times(4)).staff(scope, source);
        verify(rules, times(4)).resolve(scope, "FOOD");
    }
    @Test void sourceOrRuleChangeDuringPreparationConflicts() throws Exception {
        ready(); when(sources.staff(scope, source)).thenReturn(source(null, 100), source(null, 101));
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        when(sources.staff(scope, source)).thenReturn(source(null, 100));
        when(rules.resolve(scope, "FOOD")).thenReturn(config("b".repeat(64)), config("c".repeat(64)));
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void taxManagedVersionChangeEvenWithSameRateRequiresFreshConfirmation() throws Exception {
        ready(); var original = service.staff(scope, source);
        when(taxes.resolve(any())).thenReturn(tax(1));
        assertThatThrownBy(() -> service.confirmStaff(scope, source, original.preparationHash()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void taxChangeDuringPreparationCannotReturnMixedVersions() throws Exception {
        ready(); when(taxes.resolve(any())).thenReturn(tax(0), tax(1));
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void oldHashDoesNotRestoreRevokedSourceAccess() throws Exception {
        ready(); var original = service.staff(scope, source);
        when(sources.staff(scope, source)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.confirmStaff(scope, source, original.preparationHash()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    @Test void customerUsesOriginalCredentialsAndMissingOwnerBlocksBeforeTaxRead() throws Exception {
        var credentials = new Credentials("SESSION", "secret", "BUYER");
        when(sources.customer(source, credentials)).thenReturn(source("SESSION", 100));
        when(rules.resolve(scope, "FOOD")).thenThrow(BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        assertThatThrownBy(() -> service.customer(source, credentials)).isInstanceOf(BizException.class);
        verify(sources).customer(source, credentials); verifyNoInteractions(taxes);
    }
    @Test void forbiddenOrMalformedSourceCannotTriggerRuleAndTaxReads() {
        when(sources.staff(scope, source)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOf(BizException.class);
        verifyNoInteractions(rules, taxes);
    }
    @Test void missingOrForeignCatalogPoliciesNeverDefaultToPromotionOrZeroTax() throws Exception {
        ready(); when(catalog.resolve(any(), any())).thenReturn(null);
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOf(BizException.class);
        when(catalog.resolve(any(), any())).thenReturn(GoodsPricingPolicyFacts.freeze(
                new GoodsPricingPolicyPort.Scope("MAIN", "OTHER", "A"),
                List.of(new GoodsPricingPolicyPort.Policy("P", "G", "S", 1, true, TaxClass.STANDARD))));
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOf(BizException.class);
    }
    @Test void catalogVersionChangeConflictsEvenWithUnchangedEligibilityAndTaxClass() throws Exception {
        ready(); var prepared = service.staff(scope, source);
        when(catalog.resolve(any(), any())).thenReturn(catalog(2));
        assertThatThrownBy(() -> service.confirmStaff(scope, source, prepared.preparationHash()))
                .isInstanceOfSatisfying(BizException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
        when(catalog.resolve(any(), any())).thenReturn(catalog(1), catalog(2));
        assertThatThrownBy(() -> service.staff(scope, source)).isInstanceOf(BizException.class);
    }
    @Test void malformedExpectedHashDoesNotReadAnySource() {
        assertThatThrownBy(() -> service.confirmStaff(scope, source, "invalid")).isInstanceOf(BizException.class);
        verifyNoInteractions(sources, rules, taxes);
    }
}
