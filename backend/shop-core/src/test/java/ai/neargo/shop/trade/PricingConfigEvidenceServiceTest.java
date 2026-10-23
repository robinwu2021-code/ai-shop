package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingCapabilities;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.pay.MarketPort;
import ai.neargo.shop.spi.trade.PricingConfigEvidencePort.Scope;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Family;
import ai.neargo.shop.spi.trade.PricingFundingCapabilities.Status;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import ai.neargo.shop.spi.user.MerchantScopeFactsPort;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.mapper.TradeMappers.PricingTemplateRevisionMapper;
import ai.neargo.shop.trade.mapper.TradeMappers.StorePricingConfigMapper;
import ai.neargo.shop.trade.service.PricingFundingCapabilityService;
import ai.neargo.shop.trade.service.impl.PricingConfigEvidenceServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PricingConfigEvidenceServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T08:01:10Z");
    private final Scope scope = new Scope("MAIN", "E", "A", "CONF", false);
    private final StorePricingConfigMapper configs = mock(StorePricingConfigMapper.class);
    private final PricingTemplateRevisionMapper templates = mock(PricingTemplateRevisionMapper.class);
    private final StoreDirectoryPort stores = mock(StoreDirectoryPort.class);
    private final MerchantScopeFactsPort owners = mock(MerchantScopeFactsPort.class);
    private final MerchantQueryPort merchants = mock(MerchantQueryPort.class);
    private final MarketPort markets = mock(MarketPort.class);
    private final PricingFundingCapabilityService funding = mock(PricingFundingCapabilityService.class);
    private final PricingTaxSettingPort taxes = mock(PricingTaxSettingPort.class);
    private PricingConfigEvidenceServiceImpl service(Instant now) {
        return new PricingConfigEvidenceServiceImpl(configs, templates, stores, owners, merchants, markets,
                funding, taxes, Clock.fixed(now, ZoneOffset.UTC));
    }
    private MerchantTaxSnapshot tax(long managed) {
        return new MerchantTaxSnapshot("MAIN", "E", "A", managed, MerchantTaxSnapshot.Source.PLATFORM_DEFAULT,
                "PLATFORM", 1, 500, PriceTaxMode.EXCLUSIVE,
                new TaxSettingDefinition(true, 500, PriceTaxMode.EXCLUSIVE).snapshotHash("PLATFORM_DEFAULT:PLATFORM", 1));
    }
    private void ready() throws Exception {
        when(owners.entityActive("MAIN", "E")).thenReturn(true);
        when(owners.storesOwned("MAIN", "E", Set.of("A"))).thenReturn(true);
        when(stores.cards(List.of("A"))).thenReturn(Map.of("A", new StoreDirectoryPort.StoreCard("A", "Store", "E",
                null, "ACTIVE", true, null, null, null, null, 0, 0)));
        when(merchants.marketOf("E")).thenReturn("CN");
        when(markets.find("CN")).thenReturn(Optional.of(new MarketPort.MarketBrief("CN", "China", "CNY", 2,
                "Asia/Shanghai", 1, true)));
        var rows = PricingRuntimeFixture.rows();
        rows.head().setDraftSchemaVersion(3); rows.head().setDraftTemplateRevisionNo("TR");
        rows.head().setDraftOverride("{}"); rows.head().setDraftVersion(1L); rows.head().setVersion(1L);
        when(configs.selectOne(any())).thenReturn(rows.head());
        when(templates.selectOne(any())).thenReturn(rows.template());
        when(funding.inspect(any(), any())).thenAnswer(invocation -> PricingFundingCapabilities.freeze(
                invocation.getArgument(0), invocation.getArgument(1), Status.SOURCE_UNSUPPORTED));
        when(taxes.resolve(any())).thenReturn(tax(1));
    }
    @Test void exactOwnerNegativeReceiptsProveZeroWithStableExpiryAndRealTaxVersions() throws Exception {
        ready(); var first = service(NOW).load(scope); var second = service(NOW.plusSeconds(30)).load(scope);
        assertThat(first).isEqualTo(second);
        assertThat(first.capabilities()).isEqualTo(new PricingCapabilities(0, 0, false, false, false));
        assertThat(first.expiresAt()).isEqualTo(Instant.parse("2026-10-23T08:05:00Z"));
        assertThat(first.reference()).hasSize(32);
        assertThat(first.sources()).anySatisfy(source -> {
            assertThat(source.sourceType()).isEqualTo("PRICING_TAX");
            assertThat(source.sourceNo()).isEqualTo("PLATFORM");
            assertThat(source.sourceVersion()).isEqualTo(1);
            assertThat(source.scopeVersion()).isEqualTo(1);
        });
    }
    @Test void absentOrUnavailableFundingOwnerDoesNotTurnIntoZeroCapability() throws Exception {
        ready();
        org.mockito.Mockito.doThrow(BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED))
                .when(funding).inspect(eq(Family.ORDER_COUPON), any());
        assertThatThrownBy(() -> service(NOW).load(scope)).isInstanceOf(BizException.class);
        org.mockito.Mockito.doAnswer(invocation -> PricingFundingCapabilities.freeze(
                invocation.getArgument(0), invocation.getArgument(1), Status.OWNER_UNAVAILABLE))
                .when(funding).inspect(eq(Family.ORDER_COUPON), any());
        assertThatThrownBy(() -> service(NOW).load(scope)).isInstanceOf(BizException.class);
    }
    @Test void disabledCurrencyOrTenantFailureIsNotTheExplicitSourceUnsupportedProof() throws Exception {
        ready();
        for (var status : List.of(Status.DISABLED, Status.CURRENCY_UNSUPPORTED, Status.TENANT_UNSUPPORTED)) {
            org.mockito.Mockito.doAnswer(invocation -> PricingFundingCapabilities.freeze(
                    invocation.getArgument(0), invocation.getArgument(1), status))
                    .when(funding).inspect(any(), any());
            assertThatThrownBy(() -> service(NOW).load(scope)).isInstanceOf(BizException.class);
        }
    }
    @Test void globalTemplatePublicationHasNoFabricatedStoreEvidence() {
        assertThatThrownBy(() -> service(NOW).load(new Scope("MAIN", null, null, "T", true)))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(owners, stores, funding, taxes, configs, templates);
    }
    @Test void wrongStoreOwnerAndMissingMarketFailBeforeCapabilityInference() throws Exception {
        ready(); when(owners.storesOwned("MAIN", "E", Set.of("A"))).thenReturn(false);
        assertThatThrownBy(() -> service(NOW).load(scope)).isInstanceOf(BizException.class);
        verifyNoInteractions(funding, taxes);
        when(owners.storesOwned("MAIN", "E", Set.of("A"))).thenReturn(true);
        when(markets.find("CN")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service(NOW).load(scope)).isInstanceOf(BizException.class);
        verifyNoInteractions(funding, taxes);
    }
    @Test void sameRateWithNewManagedTaxVersionChangesEvidenceAndConcurrentChangeConflicts() throws Exception {
        ready(); var original = service(NOW).load(scope);
        when(taxes.resolve(any())).thenReturn(tax(2));
        assertThat(service(NOW).load(scope).reference()).isNotEqualTo(original.reference());
        when(taxes.resolve(any())).thenReturn(tax(1), tax(2));
        assertThatThrownBy(() -> service(NOW).load(scope)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
    @Test void fiveMinuteBoundaryCreatesNewBoundedSnapshotInsteadOfExtendingAnExistingOne() throws Exception {
        ready(); var original = service(NOW).load(scope);
        var later = service(Instant.parse("2026-10-23T08:05:00Z")).load(scope);
        assertThat(later.reference()).isNotEqualTo(original.reference());
        assertThat(original.expiresAt()).isEqualTo(Instant.parse("2026-10-23T08:05:00Z"));
        assertThat(later.expiresAt()).isEqualTo(Instant.parse("2026-10-23T08:10:00Z"));
    }
}
