package ai.neargo.commerce.starter;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.mall.MallFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.mall.MallRepository;
import ai.neargo.commerce.core.spi.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CommerceMallWiringTest {
    ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                CommerceMallAutoConfiguration.class,
                                CommerceFulfillmentAutoConfiguration.class,
                                CommerceRefundAutoConfiguration.class));
    }

    @Test
    void disabledByDefault() {
        runner().run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(0, c.getBeansOfType(MallFacade.class).size());
                        });
    }

    @Test
    void globalOptInAloneDoesNotEnablePurchases() {
        runner().withPropertyValues("commerce.enabled=true")
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(0, c.getBeansOfType(MallFacade.class).size());
                        });
    }

    @Test
    void mallOptInRequiresTrustedHostPortsAndPersistence() {
        runner().withPropertyValues("commerce.enabled=true", "commerce.mall.enabled=true")
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    <T> T unused(Class<T> type) {
        return type.cast(
                java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (p, m, a) -> {
                            throw new UnsupportedOperationException(
                                    "startup must not invoke business ports");
                        }));
    }

    ApplicationContextRunner wired() {
        return wiredWithoutReadiness().withBean(CheckoutReadinessPort.class,
                () -> (s,m,skus) -> new CheckoutReadinessPort.Readiness(false, java.util.List.of("TEST_UNAVAILABLE"), java.util.List.of()));
    }

    ApplicationContextRunner wiredWithoutReadiness() {
        return runner().withPropertyValues("commerce.enabled=true", "commerce.mall.enabled=true")
                .withBean(MallRepository.class, () -> unused(MallRepository.class))
                .withBean(CatalogRepository.class, () -> unused(CatalogRepository.class))
                .withBean(
                        SubscriptionAccountRepository.class,
                        () -> unused(SubscriptionAccountRepository.class))
                .withBean(CommerceUnitOfWork.class, () -> unused(CommerceUnitOfWork.class))
                .withBean(StoreDirectoryPort.class, () -> s -> java.util.Optional.empty())
                .withBean(
                        CommerceIdGenerator.class,
                        () -> n -> java.util.UUID.randomUUID().toString());
    }

    @Test
    void specificallyMissingCheckoutReadinessFailsClosed() {
        wiredWithoutReadiness().withBean(ActorAuthorizationPort.class,
                () -> (a,s,p) -> new ai.neargo.commerce.api.common.AuthorizationDecision(false,"NONE",java.util.Optional.empty()))
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void specificallyMissingActorAuthorizationFailsClosed() {
        wired().run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void explicitTrustedPortsCreateOneFacade() {
        wired().withBean(
                        ActorAuthorizationPort.class,
                        () ->
                                (a, s, p) ->
                                        new ai.neargo.commerce.api.common.AuthorizationDecision(
                                                false, "NONE", java.util.Optional.empty()))
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(1, c.getBeansOfType(MallFacade.class).size());
                        });
    }

    @Test
    void paymentOptInWithoutPaymentPortsFailsClosed() {
        wired().withPropertyValues(
                        "commerce.mall.payment.enabled=true",
                        "commerce.mall.payment.platform-merchant-no=platform",
                        "commerce.mall.payment.routes=APP:WECHAT")
                .withBean(
                        ActorAuthorizationPort.class,
                        () ->
                                (a, s, p) ->
                                        new ai.neargo.commerce.api.common.AuthorizationDecision(
                                                false, "NONE", java.util.Optional.empty()))
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void completePaymentAssemblyExposesBothTrustedFacades() {
        wired().withAllowBeanDefinitionOverriding(true)
                .withPropertyValues(
                        "commerce.mall.payment.enabled=true",
                        "commerce.mall.payment.platform-merchant-no=platform",
                        "commerce.mall.payment.routes=APP:WECHAT")
                .withBean(
                        CommerceUnitOfWork.class,
                        () ->
                                new CommerceUnitOfWork() {
                                    int depth;

                                    public <T> T required(java.util.function.Supplier<T> work) {
                                        depth++;
                                        try {
                                            return work.get();
                                        } finally {
                                            depth--;
                                        }
                                    }

                                    public void requireOutsideTransaction() {
                                        if (depth != 0) throw new IllegalStateException();
                                    }
                                })
                .withBean(
                        ActorAuthorizationPort.class,
                        () ->
                                (a, s, p) ->
                                        new ai.neargo.commerce.api.common.AuthorizationDecision(
                                                false, "NONE", java.util.Optional.empty()))
                .withBean(
                        ai.neargo.commerce.core.fulfillment.FulfillmentRepository.class,
                        () ->
                                unused(
                                        ai.neargo.commerce.core.fulfillment.FulfillmentRepository
                                                .class))
                .withBean(SubscriptionRepository.class, () -> unused(SubscriptionRepository.class))
                .withBean(
                        CommerceEventPersistencePort.class,
                        () -> unused(CommerceEventPersistencePort.class))
                .withBean(PlatformPaymentPort.class, () -> unused(PlatformPaymentPort.class))
                .withBean(HardwareInventoryPort.class, () -> unused(HardwareInventoryPort.class))
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(
                                    1,
                                    c.getBeansOfType(
                                                    ai.neargo.commerce.api.fulfillment.PaymentFacade
                                                            .class)
                                            .size());
                            assertEquals(
                                    1,
                                    c.getBeansOfType(
                                                    ai.neargo.commerce.api.fulfillment
                                                            .FulfillmentFacade.class)
                                            .size());
                        });
    }

    @Test
    void explicitRefundOptInWiresTheSameMallFacadeWithTrustedRefundCoordinator() {
        wired().withAllowBeanDefinitionOverriding(true)
                .withPropertyValues(
                        "commerce.mall.payment.enabled=true",
                        "commerce.mall.refund.enabled=true",
                        "commerce.mall.payment.platform-merchant-no=platform",
                        "commerce.mall.payment.routes=APP:WECHAT")
                .withBean(
                        CommerceUnitOfWork.class,
                        () ->
                                new CommerceUnitOfWork() {
                                    int depth;

                                    public <T> T required(java.util.function.Supplier<T> work) {
                                        depth++;
                                        try {
                                            return work.get();
                                        } finally {
                                            depth--;
                                        }
                                    }

                                    public void requireOutsideTransaction() {
                                        if (depth != 0) throw new IllegalStateException();
                                    }
                                })
                .withBean(
                        ActorAuthorizationPort.class,
                        () ->
                                (a, s, p) ->
                                        new ai.neargo.commerce.api.common.AuthorizationDecision(
                                                false, "NONE", java.util.Optional.empty()))
                .withBean(
                        ai.neargo.commerce.core.fulfillment.FulfillmentRepository.class,
                        () ->
                                unused(
                                        ai.neargo.commerce.core.fulfillment.FulfillmentRepository
                                                .class))
                .withBean(SubscriptionRepository.class, () -> unused(SubscriptionRepository.class))
                .withBean(
                        CommerceEventPersistencePort.class,
                        () -> unused(CommerceEventPersistencePort.class))
                .withBean(
                        ai.neargo.commerce.core.mall.RefundRepository.class,
                        () -> unused(ai.neargo.commerce.core.mall.RefundRepository.class))
                .withBean(PlatformPaymentPort.class, () -> unused(PlatformPaymentPort.class))
                .withBean(HardwareInventoryPort.class, () -> unused(HardwareInventoryPort.class))
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(
                                    1,
                                    c.getBeansOfType(ai.neargo.commerce.api.mall.RefundFacade.class)
                                            .size());
                            assertEquals(1, c.getBeansOfType(MallFacade.class).size());
                            assertEquals(
                                    1,
                                    c.getBeansOfType(
                                                    ai.neargo.commerce.api.fulfillment.PaymentFacade
                                                            .class)
                                            .size());
                            assertEquals(
                                    1,
                                    c.getBeansOfType(
                                                    ai.neargo.commerce.api.fulfillment
                                                            .FulfillmentFacade.class)
                                            .size());
                        });
    }
}
