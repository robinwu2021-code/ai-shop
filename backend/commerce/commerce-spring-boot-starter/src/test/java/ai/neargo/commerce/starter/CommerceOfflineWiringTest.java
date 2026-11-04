package ai.neargo.commerce.starter;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.common.AuthorizationDecision;
import ai.neargo.commerce.api.offline.OfflinePurchaseFacade;
import ai.neargo.commerce.api.port.*;
import ai.neargo.commerce.core.catalog.CatalogRepository;
import ai.neargo.commerce.core.fulfillment.FulfillmentRepository;
import ai.neargo.commerce.core.mall.MallRepository;
import ai.neargo.commerce.core.offline.OfflineRepository;
import ai.neargo.commerce.core.spi.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.*;
import java.util.function.Supplier;

/** Offline purchase is a separate opt-in and fails closed without trusted money/evidence ports. */
class CommerceOfflineWiringTest {
    static <T> T unused(Class<T> type) {
        return type.cast(
                java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (p, m, a) -> {
                            if (m.isDefault())
                                return java.lang.reflect.InvocationHandler.invokeDefault(p, m, a);
                            throw new UnsupportedOperationException(
                                    "startup must not invoke business ports");
                        }));
    }

    static final class Uow implements CommerceUnitOfWork {
        int depth;

        public <T> T required(Supplier<T> work) {
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
    }

    ApplicationContextRunner payment() {
        return new ApplicationContextRunner()
                .withConfiguration(
                        AutoConfigurations.of(
                                CommerceMallAutoConfiguration.class,
                                CommerceFulfillmentAutoConfiguration.class,
                                CommerceRefundAutoConfiguration.class,
                                CommerceOfflineAutoConfiguration.class))
                .withPropertyValues(
                        "commerce.enabled=true",
                        "commerce.mall.enabled=true",
                        "commerce.mall.payment.enabled=true",
                        "commerce.mall.payment.platform-merchant-no=platform",
                        "commerce.mall.payment.routes=OFFLINE:BANK_TRANSFER")
                .withBean(MallRepository.class, () -> unused(MallRepository.class))
                .withBean(CatalogRepository.class, () -> unused(CatalogRepository.class))
                .withBean(
                        SubscriptionAccountRepository.class,
                        () -> unused(SubscriptionAccountRepository.class))
                .withBean(CommerceUnitOfWork.class, Uow::new)
                .withBean(StoreDirectoryPort.class, () -> s -> Optional.empty())
                .withBean(CommerceIdGenerator.class, () -> n -> UUID.randomUUID().toString())
                .withBean(
                        CheckoutReadinessPort.class,
                        () ->
                                (s, m, skus) ->
                                        new CheckoutReadinessPort.Readiness(
                                                false, List.of("TEST_UNAVAILABLE"), List.of()))
                .withBean(
                        ActorAuthorizationPort.class,
                        () -> (a, s, p) -> new AuthorizationDecision(false, "NONE", Optional.empty()))
                .withBean(FulfillmentRepository.class, () -> unused(FulfillmentRepository.class))
                .withBean(SubscriptionRepository.class, () -> unused(SubscriptionRepository.class))
                .withBean(
                        CommerceEventPersistencePort.class,
                        () -> unused(CommerceEventPersistencePort.class))
                .withBean(PlatformPaymentPort.class, () -> unused(PlatformPaymentPort.class))
                .withBean(HardwareInventoryPort.class, () -> unused(HardwareInventoryPort.class))
                .withBean(OfflineRepository.class, () -> unused(OfflineRepository.class));
    }

    ApplicationContextRunner offline() {
        return payment()
                .withPropertyValues(
                        "commerce.mall.offline.enabled=true",
                        "commerce.mall.offline.profile-no=twd-bank-v1");
    }

    @Test
    void paymentWithoutOfflineOptInInstallsNoOfflineFacade() {
        payment().run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(0, c.getBeansOfType(OfflinePurchaseFacade.class).size());
                        });
    }

    @Test
    void offlineOptInWithoutCollectionPortFailsClosed() {
        offline().withBean(PrivateEvidencePort.class, () -> unused(PrivateEvidencePort.class))
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void offlineOptInWithoutPrivateEvidencePortFailsClosed() {
        offline().withBean(OfflineCollectionPort.class, () -> unused(OfflineCollectionPort.class))
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void offlineOptInWithoutProfileFailsClosed() {
        payment().withPropertyValues("commerce.mall.offline.enabled=true")
                .withBean(OfflineCollectionPort.class, () -> unused(OfflineCollectionPort.class))
                .withBean(PrivateEvidencePort.class, () -> unused(PrivateEvidencePort.class))
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void completeOfflineAssemblyExposesOneFacadeWithoutTouchingPorts() {
        offline().withBean(OfflineCollectionPort.class, () -> unused(OfflineCollectionPort.class))
                .withBean(PrivateEvidencePort.class, () -> unused(PrivateEvidencePort.class))
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(1, c.getBeansOfType(OfflinePurchaseFacade.class).size());
                        });
    }

    @Test
    void offlineQuoteWithoutPublishedTermsIsUnavailableBeforeAnyCommerceTransaction() {
        offline().withBean(OfflineCollectionPort.class, () -> new OfflineCollectionPort() {})
                .withBean(PrivateEvidencePort.class, () -> unused(PrivateEvidencePort.class))
                .run(
                        c -> {
                            var facade = c.getBean(OfflinePurchaseFacade.class);
                            var actor =
                                    new ai.neargo.commerce.api.common.ActorContext(
                                            "MAIN",
                                            ai.neargo.commerce.api.common.SubjectKind.ACCOUNT,
                                            "USR:buyer",
                                            "session");
                            var e =
                                    assertThrows(
                                            ai.neargo.commerce.api.mall.MallException.class,
                                            () ->
                                                    facade.quote(
                                                            actor,
                                                            new ai.neargo.commerce.api.mall
                                                                    .QuoteCommand(
                                                                    new ai.neargo.commerce.api
                                                                            .common.StoreRef(
                                                                            "MAIN", "S1"),
                                                                    List.of(),
                                                                    "TWD")));
                            assertEquals("OFFLINE_UNAVAILABLE", e.code());
                        });
    }
}
