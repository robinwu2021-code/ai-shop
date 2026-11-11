package ai.neargo.shop.beauty.config;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.client.RemoteWorkOrderEntitlementUses;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUsePort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.svc.InternalBindings;
import ai.neargo.shop.svc.InternalHttp;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkOrderEntitlementClientWiringTest {
    @Test void onlyExplicitStandaloneGetsTheRealTypedRemoteProvider() {
        var runner = new ApplicationContextRunner().withUserConfiguration(BeautyEntitlementUseClientConfiguration.class);
        runner.withPropertyValues("shop.beauty.enabled=false").run(c -> assertThat(c)
                .hasNotFailed().doesNotHaveBean(ServiceWorkOrderUsePort.class));
        var enabled = runner.withPropertyValues("shop.beauty.enabled=true")
                .withBean(InternalHttp.class, () -> new InternalHttp(n -> Optional.of("http://127.0.0.1:1"), "wire"))
                .withBean(InternalBindings.class, () -> new InternalBindings("wire"));
        enabled.withPropertyValues("shop.beauty.deployment=standalone").run(c -> {
            assertThat(c).hasNotFailed().hasSingleBean(ServiceWorkOrderUsePort.class);
            assertThat(c.getBean(ServiceWorkOrderUsePort.class)).isInstanceOf(RemoteWorkOrderEntitlementUses.class);
            assertThat(c).hasSingleBean(ServiceWorkOrderUseFactsPort.class);
        });
        enabled.withPropertyValues("shop.beauty.deployment=embedded").run(c -> assertThat(c)
                .hasNotFailed().doesNotHaveBean(ServiceWorkOrderUsePort.class));
    }
}
