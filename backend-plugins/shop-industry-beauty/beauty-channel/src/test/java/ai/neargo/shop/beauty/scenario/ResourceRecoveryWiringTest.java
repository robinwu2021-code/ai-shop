package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration;
import ai.neargo.shop.beauty.port.RemoteMerchantOperatorAccessPort;
import ai.neargo.shop.beauty.port.RemoteResourceCommandFactsPort;
import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.svc.InternalHttp;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Independent deployment gets remote reads; an existing local owner keeps sole authority. */
class ResourceRecoveryWiringTest {
    @Test
    void registersRemoteReadsOnlyWhenTheLocalOwnerIsAbsent() {
        var runner = new ApplicationContextRunner().withUserConfiguration(BeautyMerchantClientConfiguration.class)
                .withBean(InternalHttp.class, () -> new InternalHttp(service -> Optional.empty(), "facts-key"));
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(MerchantOperatorAccessPort.class)
                    .hasSingleBean(ResourceCommandFactsPort.class);
            assertThat(ctx.getBean(MerchantOperatorAccessPort.class))
                    .isInstanceOf(RemoteMerchantOperatorAccessPort.class);
            assertThat(ctx.getBean(ResourceCommandFactsPort.class))
                    .isInstanceOf(RemoteResourceCommandFactsPort.class);
        });
        MerchantOperatorAccessPort localAccess = query -> false;
        ResourceCommandFactsPort localCommands = request -> new ResourceCommandFactsPort.Lookup(null);
        runner.withBean(MerchantOperatorAccessPort.class, () -> localAccess)
                .withBean(ResourceCommandFactsPort.class, () -> localCommands).run(ctx -> {
                    assertThat(ctx).hasNotFailed().hasSingleBean(MerchantOperatorAccessPort.class)
                            .hasSingleBean(ResourceCommandFactsPort.class);
                    assertThat(ctx.getBean(MerchantOperatorAccessPort.class)).isSameAs(localAccess);
                    assertThat(ctx.getBean(ResourceCommandFactsPort.class)).isSameAs(localCommands);
                });
    }
}
