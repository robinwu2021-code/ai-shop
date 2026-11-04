package ai.neargo.commerce.starter;

import static org.junit.jupiter.api.Assertions.*;

import ai.neargo.commerce.api.catalog.CatalogFacade;
import ai.neargo.commerce.api.fulfillment.PaymentFacade;
import ai.neargo.commerce.api.mall.*;
import ai.neargo.commerce.starter.http.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.*;

class CommerceMallHttpWiringTest {
    private <T> T unused(Class<T> type) {
        return type.cast(
                java.lang.reflect.Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (p, m, a) -> {
                            throw new AssertionError("startup must not call business ports");
                        }));
    }

    private WebApplicationContextRunner web() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(CommerceMallHttpAutoConfiguration.class));
    }

    @Test
    void disabledByDefaultAndUnavailableWithoutServletClasses() {
        web().run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(
                                    0,
                                    c.getBeansOfType(CommerceCatalogHttpController.class).size());
                        });
        new ApplicationContextRunner()
                .withClassLoader(
                        new FilteredClassLoader("org.springframework.web", "jakarta.servlet"))
                .withConfiguration(AutoConfigurations.of(CommerceMallHttpAutoConfiguration.class))
                .withPropertyValues("commerce.enabled=true", "commerce.mall.http.enabled=true")
                .run(c -> assertNull(c.getStartupFailure()));
    }

    @Test
    void catalogManagementBootsWithPaymentAndRefundOff() {
        web().withPropertyValues(
                        "commerce.enabled=true",
                        "commerce.catalog.enabled=true",
                        "commerce.mall.http.enabled=true",
                        "commerce.mall.payment.enabled=false",
                        "commerce.mall.refund.enabled=false")
                .withBean(CatalogFacade.class, () -> unused(CatalogFacade.class))
                .withBean(MallHttpContextPort.class, () -> unused(MallHttpContextPort.class))
                .withBean(MallHttpEnvelopePort.class, () -> unused(MallHttpEnvelopePort.class))
                .run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(
                                    1,
                                    c.getBeansOfType(CommerceCatalogHttpController.class).size());
                            assertEquals(0, c.getBeansOfType(PaymentFacade.class).size());
                            assertEquals(0, c.getBeansOfType(RefundFacade.class).size());
                        });
    }

    @Test
    void explicitlyEnabledHttpRequiresTrustedContext() {
        web().withPropertyValues(
                        "commerce.enabled=true",
                        "commerce.catalog.enabled=true",
                        "commerce.mall.http.enabled=true")
                .withBean(CatalogFacade.class, () -> unused(CatalogFacade.class))
                .withBean(MallHttpEnvelopePort.class, () -> unused(MallHttpEnvelopePort.class))
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    private WebApplicationContextRunner mall() {
        return web().withPropertyValues(
                        "commerce.enabled=true",
                        "commerce.catalog.enabled=true",
                        "commerce.mall.enabled=true",
                        "commerce.mall.http.enabled=true")
                .withBean(CatalogFacade.class, () -> unused(CatalogFacade.class))
                .withBean(MallFacade.class, () -> unused(MallFacade.class))
                .withBean(StorefrontFacade.class, () -> unused(StorefrontFacade.class))
                .withBean(MallQueryFacade.class, () -> unused(MallQueryFacade.class))
                .withBean(
                        ai.neargo.commerce.api.offline.OfflinePurchaseFacade.class,
                        () -> unused(ai.neargo.commerce.api.offline.OfflinePurchaseFacade.class))
                .withBean(MallHttpContextPort.class, () -> unused(MallHttpContextPort.class))
                .withBean(MallHttpEnvelopePort.class, () -> unused(MallHttpEnvelopePort.class));
    }

    /** Split deployment: public instance mounts only /biz, internal instance only /ops. */
    @Test
    void eachDeploymentSurfaceMountsOnlyItsOwnRoutes() {
        mall().run(
                        c -> {
                            assertNull(c.getStartupFailure());
                            assertEquals(1, c.getBeansOfType(CommerceBuyerMallHttpController.class).size());
                            assertEquals(1, c.getBeansOfType(CommerceOpsMallHttpController.class).size());
                            assertEquals(1, c.getBeansOfType(CommerceOfflineBuyerHttpController.class).size());
                            assertEquals(1, c.getBeansOfType(CommerceOfflineOpsHttpController.class).size());
                        });
        mall().withPropertyValues("commerce.mall.http.operator.enabled=false")
                .run(
                        c -> {
                            assertEquals(1, c.getBeansOfType(CommerceBuyerMallHttpController.class).size());
                            assertEquals(1, c.getBeansOfType(CommerceOfflineBuyerHttpController.class).size());
                            assertEquals(0, c.getBeansOfType(CommerceOpsMallHttpController.class).size());
                            assertEquals(0, c.getBeansOfType(CommerceCatalogHttpController.class).size());
                            assertEquals(0, c.getBeansOfType(CommerceSourceHttpController.class).size());
                            assertEquals(0, c.getBeansOfType(CommerceOfflineOpsHttpController.class).size());
                        });
        mall().withPropertyValues("commerce.mall.http.buyer.enabled=false")
                .run(
                        c -> {
                            assertEquals(0, c.getBeansOfType(CommerceBuyerMallHttpController.class).size());
                            assertEquals(0, c.getBeansOfType(CommerceOfflineBuyerHttpController.class).size());
                            assertEquals(1, c.getBeansOfType(CommerceOpsMallHttpController.class).size());
                            assertEquals(1, c.getBeansOfType(CommerceCatalogHttpController.class).size());
                            assertEquals(1, c.getBeansOfType(CommerceOfflineOpsHttpController.class).size());
                        });
    }
}
