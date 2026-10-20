package ai.neargo.shop.paybridge;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.pay.entitlement.config.PayEntitlementProperties;
import ai.neargo.shop.pay.entitlement.policy.PayEntitlementFeatureGate;
import ai.neargo.shop.promotion.config.PromotionFeatureProperties;
import ai.neargo.shop.promotion.policy.PromotionFeatureGate;
import ai.neargo.shop.spi.promotion.ActivityCapability;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Flags authorize new work only; verified capabilities and owner scope remain separate requirements. */
class EntitlementFeatureGateTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Features.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({PayEntitlementProperties.class, PromotionFeatureProperties.class})
    @Import({PayEntitlementFeatureGate.class, PromotionFeatureGate.class})
    static class Features {
    }

    @Test
    void defaultsRejectNewWorkEvenWithEveryCapabilityPresent() {
        runner.run(context -> {
            var pay = context.getBean(PayEntitlementFeatureGate.class);
            rejected(pay::requireCatalog);
            rejected(pay::requireIssuance);
            rejected(pay::requireUse);
            rejected(pay::requireStoredValue);
            rejected(pay::requirePaidCard);
            var promotion = context.getBean(PromotionFeatureGate.class);
            rejected(promotion::requirePresets);
            rejected(promotion::requireActivity);
            var assessment = promotion.assessPreset(List.of("MEMBER_IDENTITY"),
                    EnumSet.allOf(ActivityCapability.class));
            assertThat(assessment.available()).isFalse();
        });
    }

    @Test
    void allSevenExplicitFlagsPermitTheirOwnNewWork() {
        runner.withPropertyValues("shop.pay.entitlement.catalog.enabled=true",
                "shop.pay.entitlement.issue.enabled=true", "shop.pay.entitlement.use.enabled=true",
                "shop.pay.stored-value.enabled=true", "shop.pay.paid-card.enabled=true",
                "shop.promotion.presets.enabled=true", "shop.promotion.activity.enabled=true").run(context -> {
                    var pay = context.getBean(PayEntitlementFeatureGate.class);
                    pay.requireCatalog();
                    pay.requireIssuance();
                    pay.requireUse();
                    pay.requireStoredValue();
                    pay.requirePaidCard();
                    var promotion = context.getBean(PromotionFeatureGate.class);
                    promotion.requirePresets();
                    promotion.requireActivity();
                    assertThat(promotion.assessPreset(List.of("MEMBER_IDENTITY"),
                            Set.of(ActivityCapability.MEMBER_IDENTITY)).available()).isTrue();
                });
    }

    @Test
    void paidCardFlagCannotBypassItsCatalogIssuanceAndUsageDependencies() {
        runner.withPropertyValues("shop.pay.paid-card.enabled=true").run(context -> {
            var pay = context.getBean(PayEntitlementFeatureGate.class);
            rejected(pay::requirePaidCard);
            rejected(pay::requireCatalog);
            rejected(pay::requireStoredValue);
        });
        runner.withPropertyValues("shop.pay.paid-card.enabled=true", "shop.pay.entitlement.catalog.enabled=true",
                "shop.pay.entitlement.issue.enabled=true").run(context -> {
                    var pay = context.getBean(PayEntitlementFeatureGate.class);
                    pay.requireCatalog();
                    pay.requireIssuance();
                    rejected(pay::requireUse);
                    rejected(pay::requirePaidCard);
                });
    }

    @ParameterizedTest
    @CsvSource({"false,true,true", "true,false,true", "true,true,false"})
    void everyPaidCardFoundationIsRequiredIndependently(boolean catalog, boolean issue, boolean use) {
        runner.withPropertyValues("shop.pay.paid-card.enabled=true",
                "shop.pay.entitlement.catalog.enabled=" + catalog,
                "shop.pay.entitlement.issue.enabled=" + issue,
                "shop.pay.entitlement.use.enabled=" + use).run(context ->
                        rejected(context.getBean(PayEntitlementFeatureGate.class)::requirePaidCard));
    }

    @Test
    void enablingCatalogDoesNotEnableOtherBusinessOrPromotionFlags() {
        runner.withPropertyValues("shop.pay.entitlement.catalog.enabled=true").run(context -> {
            var pay = context.getBean(PayEntitlementFeatureGate.class);
            pay.requireCatalog();
            rejected(pay::requireIssuance);
            rejected(pay::requireUse);
            rejected(pay::requireStoredValue);
            rejected(pay::requirePaidCard);
            rejected(context.getBean(PromotionFeatureGate.class)::requirePresets);
            rejected(context.getBean(PromotionFeatureGate.class)::requireActivity);
        });
    }

    @Test
    void stoppingNewIssuanceAndCardSalesDoesNotDisableUseOfExistingAssets() {
        runner.withPropertyValues("shop.pay.entitlement.use.enabled=true").run(context -> {
            var pay = context.getBean(PayEntitlementFeatureGate.class);
            pay.requireUse();
            rejected(pay::requireIssuance);
            rejected(pay::requirePaidCard);
        });
    }

    @Test
    void anOpenFlagStillReportsMissingAndUnknownCapabilitiesWithoutAcceptingThemAsReady() {
        runner.withPropertyValues("shop.promotion.presets.enabled=true").run(context -> {
            var promotion = context.getBean(PromotionFeatureGate.class);
            var required = new ArrayList<>(List.of("MERCHANT_ACTIVE", "MEMBER_IDENTITY", "FUTURE_PLAY",
                    "MEMBER_IDENTITY"));
            var ready = EnumSet.of(ActivityCapability.MERCHANT_ACTIVE);
            var assessment = promotion.assessPreset(required, ready);
            required.clear();
            ready.add(ActivityCapability.MEMBER_IDENTITY);
            assertThat(assessment.available()).isFalse();
            assertThat(assessment.missingCapabilities()).containsExactly("FUTURE_PLAY", "MEMBER_IDENTITY");
            assertThatThrownBy(() -> assessment.missingCapabilities().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            assertThat(promotion.assessPreset(List.of("FUTURE_PLAY"),
                    EnumSet.allOf(ActivityCapability.class)).available()).isFalse();
        });
    }

    @Test
    void malformedRequirementsCannotBecomeAnEmptyReadyPreset() {
        runner.run(context -> {
            var gate = context.getBean(PromotionFeatureGate.class);
            assertThatThrownBy(() -> gate.assessPreset(List.of(), EnumSet.noneOf(ActivityCapability.class)))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> gate.assessPreset(List.of(" MEMBER_IDENTITY"),
                    EnumSet.allOf(ActivityCapability.class))).isInstanceOf(IllegalArgumentException.class);
        });
    }

    private void rejected(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(BizException.class,
                error -> assertThat(error.errorCode()).isEqualTo(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE));
    }
}
