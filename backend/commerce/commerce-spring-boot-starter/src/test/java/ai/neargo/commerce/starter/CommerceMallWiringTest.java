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
                .withConfiguration(AutoConfigurations.of(CommerceMallAutoConfiguration.class));
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
}
