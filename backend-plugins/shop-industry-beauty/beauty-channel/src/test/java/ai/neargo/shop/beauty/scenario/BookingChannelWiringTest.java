package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import ai.neargo.shop.beauty.config.BeautyChannelAutoConfiguration;
import ai.neargo.shop.beauty.port.RemoteAppointmentSourceAuthorityPort;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.AppointmentAvailabilityService;
import ai.neargo.shop.beauty.service.ServiceOperationQueryService;
import ai.neargo.shop.beauty.service.ServiceOperationRecoveryService;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.svc.InternalHttp;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Real channel-only host wiring contains one reverse provider and no eager HTTP calls. */
class BookingChannelWiringTest {
    @Test void channelOnlyHostHasExactlyOneRemoteSourceAndFourTypedServicesWithoutResolvingTargets() {
        var runner = new ApplicationContextRunner().withConfiguration(
                AutoConfigurations.of(BeautyChannelAutoConfiguration.class))
                .withBean(InternalHttp.class, () -> new InternalHttp(service -> {
                    throw new AssertionError("HTTP target lookup during bean construction creates topology cycle");
                }, "key"));
        runner.withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=standalone").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(ResourceSourceAuthorityPort.class)
                    .hasSingleBean(AppointmentService.class).hasSingleBean(AppointmentAvailabilityService.class)
                    .hasSingleBean(ServiceOperationQueryService.class).hasSingleBean(ServiceOperationRecoveryService.class);
            assertThat(ctx.getBean(ResourceSourceAuthorityPort.class)).isInstanceOf(RemoteAppointmentSourceAuthorityPort.class);
        });
        for (String[] properties : new String[][] {{"shop.beauty.enabled=false", "shop.beauty.deployment=standalone"},
                {"shop.beauty.enabled=true", "shop.beauty.deployment=embedded"}}) {
            runner.withPropertyValues(properties).run(ctx -> assertThat(ctx).hasNotFailed()
                    .doesNotHaveBean(ResourceSourceAuthorityPort.class).doesNotHaveBean(AppointmentService.class));
        }
    }
}
