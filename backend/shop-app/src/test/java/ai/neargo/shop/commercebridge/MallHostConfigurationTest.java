package ai.neargo.shop.commercebridge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.starter.http.*;
import ai.neargo.shop.auth.*;
import ai.neargo.shop.config.MallOperatorSessions;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MallHostConfigurationTest {
    @Test
    void configurationOnlyBootsWithoutGatewayRecipientOrInventoryAndExposesHonestReadiness() {
        new ApplicationContextRunner()
                .withUserConfiguration(ShopMallConfiguration.class)
                .withPropertyValues(
                        "spring.profiles.active=api",
                        "commerce.enabled=true",
                        "commerce.catalog.enabled=true",
                        "commerce.mall.enabled=true",
                        "commerce.mall.http.enabled=true",
                        "commerce.mall.payment.enabled=false",
                        "commerce.mall.refund.enabled=false")
                .withBean(
                        ai.neargo.commerce.api.foundation.FoundationAuthorityPort.class,
                        () -> mock(ai.neargo.commerce.api.foundation.FoundationAuthorityPort.class))
                .withBean(
                        ai.neargo.commerce.api.mall.StorefrontFacade.class,
                        () -> mock(ai.neargo.commerce.api.mall.StorefrontFacade.class))
                .withBean(AccountSessionVerifier.class, () -> mock(AccountSessionVerifier.class))
                .withBean(
                        MallOperatorSessions.class,
                        () -> new MallOperatorSessions(mock(AccountSessionVerifier.class)))
                .withBean(BizIdentityResolver.class, () -> mock(BizIdentityResolver.class))
                .withBean(
                        ShopCommerceStoreAdapter.class, () -> mock(ShopCommerceStoreAdapter.class))
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(MallHttpContextPort.class)
                                    .hasSingleBean(MallHttpEnvelopePort.class)
                                    .hasSingleBean(CatalogAuthorizationPort.class)
                                    .hasSingleBean(ActorAuthorizationPort.class)
                                    .hasSingleBean(BusinessEndReadinessPort.class)
                                    .hasSingleBean(CheckoutReadinessPort.class)
                                    .doesNotHaveBean(PlatformPaymentPort.class);
                            var readiness =
                                    context.getBean(CheckoutReadinessPort.class)
                                            .evaluate(
                                                    new StoreSnapshot(
                                                            new StoreRef("MAIN", "S"),
                                                            "merchant",
                                                            "RETAIL",
                                                            StoreStatus.ACTIVE,
                                                            CommerceModel.STORE_V1),
                                                    new Money("TWD", 69900),
                                                    java.util.List.of());
                            assertThat(readiness.available()).isFalse();
                            assertThat(readiness.reasons())
                                    .contains(
                                            "PAYMENT_COORDINATION_DISABLED",
                                            "PAYER_BINDING_UNPROVEN");
                            assertThat(
                                            context.getBean(BusinessEndReadinessPort.class)
                                                    .acceptedDeployments("MAIN"))
                                    .isEmpty();
                        });
    }
}
