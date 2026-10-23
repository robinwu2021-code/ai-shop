package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.config.FoodWebAutoConfiguration;
import ai.neargo.shop.food.dto.CartAccess;
import ai.neargo.shop.food.port.FoodPricingSourceFactsPortImpl;
import ai.neargo.shop.food.service.DiningPricingFactsService;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Credentials;
import ai.neargo.shop.spi.trade.PricingSourceFactsPort.Scope;
import ai.neargo.shop.spi.trade.PricingSourceRef;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class FoodPricingSourceFactsPortTest {
    private final DiningPricingFactsService dining = mock(DiningPricingFactsService.class);
    private final FoodPricingSourceFactsPortImpl port = new FoodPricingSourceFactsPortImpl(dining);
    private final Scope scope = new Scope("MAIN", "E", "A");
    private final PricingSourceRef source = new PricingSourceRef("FOOD", "CART", "C", 4, null, null);

    @Test void mapsAuthorizedStaffPricesWithOriginalInvoiceIdentityAndDistinctProjectionDigest() {
        var facts = new RemoteDiningPricingFactsTest().receipt(null);
        when(dining.staff("E", "A", "C", 4)).thenReturn(facts);
        var result = port.staff(scope, source);
        assertThat(result.scope()).isEqualTo(scope); assertThat(result.source()).isEqualTo(source);
        assertThat(result.prices().baseTotal().minor()).isEqualTo(200);
        assertThat(result.prices().sourceSnapshotHash()).isEqualTo(facts.sourceHash());
        assertThat(result.snapshotHash()).isNotEqualTo(facts.sourceHash());
        assertThat(result.invoiceBases()).hasSize(1);
        assertThat(result.invoiceBases().getFirst().invoiceLineRef()).isEqualTo("L");
        verify(dining).staff("E", "A", "C", 4);
    }
    @Test void customerCredentialsAreForwardedAndOmittedSessionUsesAuthorizedOriginalSession() {
        var access = new Credentials(null, "secret", "BUYER");
        var cartAccess = new CartAccess(null, "secret", "BUYER");
        when(dining.customer("C", cartAccess, 4)).thenReturn(new RemoteDiningPricingFactsTest().receipt("SESSION"));
        var result = port.customer(source, access);
        assertThat(result.subjectNo()).isEqualTo("SESSION");
        assertThat(result.toString()).doesNotContain("secret", "accessToken");
        verify(dining).customer("C", cartAccess, 4);
    }
    @Test void originalAuthorizationFailureIsPreservedWithoutProjectionFallback() {
        when(dining.staff("E", "A", "C", 4)).thenThrow(BizException.of(ErrorCode.FORBIDDEN));
        assertThatThrownBy(() -> port.staff(scope, source)).isInstanceOfSatisfying(BizException.class,
                e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.FORBIDDEN));
    }
    @Test void unsupportedHistoricalOrPartialSourcesNeverReadCartFacts() {
        for (var bad : List.of(new PricingSourceRef("FOOD", "CHECK", "C", 4, null, null),
                new PricingSourceRef("FOOD", "CART", "C", 4, "AA", 1L),
                new PricingSourceRef("OTHER", "CART", "C", 4, null, null))) {
            assertThatThrownBy(() -> port.staff(scope, bad)).isInstanceOf(BizException.class);
        }
        verifyNoInteractions(dining);
    }
    @Test void malformedForeignOrSessionSwitchedFactsAreBlocked() {
        when(dining.staff("E", "OTHER", "C", 4)).thenReturn(new RemoteDiningPricingFactsTest().receipt(null));
        assertThatThrownBy(() -> port.staff(new Scope("MAIN", "E", "OTHER"), source))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.PRICING_OWNER_UNSUPPORTED));
        var access = new Credentials("FORGED", "secret", "BUYER");
        when(dining.customer("C", new CartAccess("FORGED", "secret", "BUYER"), 4))
                .thenReturn(new RemoteDiningPricingFactsTest().receipt("SESSION"));
        assertThatThrownBy(() -> port.customer(source, access)).isInstanceOf(BizException.class);
    }
    @Test void bothDeploymentModesRegisterTheSameAdapterAndDisabledModeRegistersNone() {
        var runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FoodWebAutoConfiguration.class))
                .withPropertyValues("shop.food.enabled=true", "spring.profiles.active=api")
                .withBean(DiningPricingFactsService.class, () -> dining);
        for (String mode : List.of("embedded", "standalone")) {
            runner.withPropertyValues("shop.food.deployment=" + mode).run(c -> assertThat(c).hasNotFailed()
                    .hasSingleBean(PricingSourceFactsPort.class).hasSingleBean(FoodPricingSourceFactsPortImpl.class));
        }
        runner.withPropertyValues("shop.food.enabled=false").run(c -> assertThat(c).hasNotFailed()
                .doesNotHaveBean(PricingSourceFactsPort.class));
        runner.withPropertyValues("shop.food.migration.mode=off").run(c -> assertThat(c).hasNotFailed()
                .doesNotHaveBean(PricingSourceFactsPort.class));
    }
}
