package ai.neargo.shop.trade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot;
import ai.neargo.shop.common.pricing.MerchantTaxSnapshot.Source;
import ai.neargo.shop.common.pricing.MoneyMinor;
import ai.neargo.shop.common.pricing.PriceTaxMode;
import ai.neargo.shop.common.pricing.PricingUnit;
import ai.neargo.shop.common.pricing.TaxClass;
import ai.neargo.shop.common.pricing.TaxEngine.Line;
import ai.neargo.shop.common.pricing.TaxSettingDefinition;
import ai.neargo.shop.spi.trade.PricingTaxCalculationPort.Input;
import ai.neargo.shop.spi.trade.PricingTaxSettingPort.Scope;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import ai.neargo.shop.trade.dto.PricingTaxSettingCommands;
import ai.neargo.shop.trade.port.PricingTaxCalculationPortImpl;
import ai.neargo.shop.trade.port.PricingTaxSettingPortImpl;
import ai.neargo.shop.trade.service.PricingTaxSettingService;
import ai.neargo.shop.trade.service.impl.PricingTaxCalculationServiceImpl;
import org.junit.jupiter.api.Test;

class PricingTaxCalculationTest {
    private final PricingTaxSettingService settings = mock(PricingTaxSettingService.class);
    private final StoreDirectoryPort stores = mock(StoreDirectoryPort.class);
    private final PricingTaxSettingPortImpl source = new PricingTaxSettingPortImpl(settings, stores);
    private final PricingTaxCalculationPortImpl calculation = new PricingTaxCalculationPortImpl(
            new PricingTaxCalculationServiceImpl(source));
    private final Scope scope = new Scope("MAIN", "M1", "S1");

    @Test
    void realAdapterChainFreezesManagedAndEffectiveVersionsAndRecomputesOnConfirmation() {
        setup(3, 7, Source.PLATFORM_DEFAULT, 500, PriceTaxMode.EXCLUSIVE);
        var preview = calculation.estimate(scope, input(10000, 1));
        assertThat(preview.result().snapshot().managedVersion()).isEqualTo(3);
        assertThat(preview.result().snapshot().sourceVersion()).isEqualTo(7);
        assertThat(preview.result().invoiceGross().minor()).isEqualTo(10500);
        var confirmed = calculation.confirm(scope, input(10000, 1), preview);
        assertThat(confirmed.result()).isNotSameAs(preview.result());
        assertThat(confirmed.result().lines()).isEqualTo(preview.result().lines());
    }

    @Test
    void sameRateWithChangedDefaultVersionOrResetMarkerStillRequiresRequote() {
        setup(0, 1, Source.PLATFORM_DEFAULT, 500, PriceTaxMode.EXCLUSIVE);
        var preview = calculation.estimate(scope, input(10000, 1));
        setup(0, 2, Source.PLATFORM_DEFAULT, 500, PriceTaxMode.EXCLUSIVE);
        conflict(() -> calculation.confirm(scope, input(10000, 1), preview));
        setup(2, 1, Source.PLATFORM_DEFAULT, 500, PriceTaxMode.EXCLUSIVE);
        conflict(() -> calculation.confirm(scope, input(10000, 1), preview));
    }

    @Test
    void sourceChangesEvenWithSameEffectivePriceRequireRequote() {
        setup(1, 1, Source.MERCHANT_CUSTOM, 500, PriceTaxMode.EXCLUSIVE);
        var preview = calculation.estimate(scope, input(10000, 1));
        setup(2, 1, Source.PLATFORM_DEFAULT, 500, PriceTaxMode.EXCLUSIVE);
        conflict(() -> calculation.confirm(scope, input(10000, 1), preview));
        assertThat(preview.result().snapshot().source()).isEqualTo(Source.MERCHANT_CUSTOM);
        assertThat(preview.result().invoiceGross().minor()).isEqualTo(10500);
    }

    @Test
    void changedGoodsFactsOrSourceRevisionCannotReuseThePreview() {
        setup(1, 1, Source.MERCHANT_CUSTOM, 500, PriceTaxMode.EXCLUSIVE);
        var preview = calculation.estimate(scope, input(10000, 1));
        conflict(() -> calculation.confirm(scope, input(10000, 2), preview));
        conflict(() -> calculation.confirm(scope, input(10001, 1), preview));
    }

    @Test
    void wrongTenantOrStoreOwnerIsRejectedBeforeReadingConfiguration() {
        assertThatThrownBy(() -> source.resolve(new Scope("OTHER", "M1", "S1")))
                .isInstanceOf(BizException.class);
        verifyNoInteractions(stores, settings);
        when(stores.cards(List.of("S1"))).thenReturn(Map.of("S1", card("M2")));
        assertThatThrownBy(() -> source.resolve(scope)).isInstanceOf(BizException.class);
        verifyNoInteractions(settings);
    }

    @Test
    void missingDefaultDoesNotBecomeZeroAndProviderFailureDoesNotBecomeAQuote() {
        setup(0, 1, Source.PLATFORM_DEFAULT, 0, PriceTaxMode.EXCLUSIVE);
        when(settings.getMerchant(any())).thenReturn(new PricingTaxSettingCommands.SettingVO(0, false, null));
        assertThatThrownBy(() -> calculation.estimate(scope, input(10000, 1)))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        when(settings.getMerchant(any())).thenThrow(new IllegalStateException("unavailable"));
        assertThatThrownBy(() -> calculation.estimate(scope, input(10000, 1)))
                .hasMessage("unavailable");
    }

    @Test
    void anotherStoreCannotConfirmAnOtherwiseIdenticalSnapshot() {
        setup(1, 1, Source.MERCHANT_CUSTOM, 500, PriceTaxMode.EXCLUSIVE);
        var preview = calculation.estimate(scope, input(10000, 1));
        var next = new StoreDirectoryPort.StoreCard("S2", "Store2", "M1", "", "ACTIVE", true,
                "", "", null, null, 0, 0);
        when(stores.cards(List.of("S2"))).thenReturn(Map.of("S2", next));
        conflict(() -> calculation.confirm(new Scope("MAIN", "M1", "S2"), input(10000, 1), preview));
    }

    private void setup(long managed, long version, Source selected, int rate, PriceTaxMode mode) {
        when(stores.cards(List.of("S1"))).thenReturn(Map.of("S1", card("M1")));
        String owner = selected == Source.PLATFORM_DEFAULT ? "PLATFORM" : "M1";
        var effective = new PricingTaxSettingCommands.Snapshot(
                PricingTaxSettingCommands.Source.valueOf(selected.name()), owner, version, rate, mode,
                new TaxSettingDefinition(true, rate, mode).snapshotHash(selected + ":" + owner, version));
        when(settings.getMerchant(any())).thenReturn(new PricingTaxSettingCommands.SettingVO(managed,
                selected == Source.MERCHANT_CUSTOM, effective));
    }

    private StoreDirectoryPort.StoreCard card(String entity) {
        return new StoreDirectoryPort.StoreCard("S1", "Store", entity, "", "ACTIVE", true,
                "", "", null, null, 0, 0);
    }

    static Input input(long amount, long version) {
        var money = new MoneyMinor("AED", amount);
        var unit = new PricingUnit("L1", null, "G1", "SKU1", "P1", "INV1", BigDecimal.ZERO,
                BigDecimal.ONE, money, money);
        return new Input("a".repeat(64), version,
                List.of(new Line(unit, TaxClass.STANDARD, PriceTaxMode.EXCLUSIVE, money)));
    }

    private void conflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.CONFLICT));
    }
}
