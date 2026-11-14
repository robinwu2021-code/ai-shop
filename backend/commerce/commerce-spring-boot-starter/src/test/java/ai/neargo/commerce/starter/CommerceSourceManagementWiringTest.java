package ai.neargo.commerce.starter;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.api.subscription.SourceManagementFacade;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.spi.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class CommerceSourceManagementWiringTest {
    private <T> T unused(Class<T> type) {
        return type.cast(
                java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class[] {type},
                        (p, m, a) -> {
                            throw new AssertionError("no startup business calls");
                        }));
    }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(CommerceSourceManagementAutoConfiguration.class));
    }

    @Test
    void disabledByDefault() {
        runner().run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertTrue(c.getBeansOfType(SourceManagementFacade.class).isEmpty());
                        });
    }

    private ApplicationContextRunner wired() {
        return runner().withPropertyValues(
                        "commerce.enabled=true",
                        "commerce.catalog.enabled=true",
                        "commerce.mall.http.enabled=true",
                        "commerce.mall.payment.enabled=false")
                .withBean(SubscriptionRepository.class, () -> unused(SubscriptionRepository.class))
                .withBean(CatalogRepository.class, () -> unused(CatalogRepository.class))
                .withBean(CommerceUnitOfWork.class, () -> unused(CommerceUnitOfWork.class))
                .withBean(StoreDirectoryPort.class, () -> unused(StoreDirectoryPort.class))
                .withBean(
                        BusinessEndReadinessPort.class,
                        () -> unused(BusinessEndReadinessPort.class))
                .withBean(
                        CommerceEventPersistencePort.class,
                        () -> unused(CommerceEventPersistencePort.class))
                .withBean(CommerceIdGenerator.class, () -> unused(CommerceIdGenerator.class));
    }

    @Test
    void paymentIsNotRequiredButFreshAuthorityIs() {
        wired().run(c -> assertNotNull(c.getStartupFailure()));
        wired().withBean(ActorAuthorizationPort.class, () -> unused(ActorAuthorizationPort.class))
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(1, c.getBeansOfType(SourceManagementFacade.class).size());
                        });
    }
}
