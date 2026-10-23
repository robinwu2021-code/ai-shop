package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.shop.beauty.config.BeautyAppointmentConfiguration;
import ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration;
import ai.neargo.shop.beauty.job.AppointmentRecoveryJob;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.port.AppointmentSourceAuthorityPortImpl;
import ai.neargo.shop.beauty.port.RemoteResourceBookingPort;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.svc.InternalHttp;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/** Actual core + remote-owner configurations prove local source/provider/executor graph is acyclic. */
class BookingServerWiringTest {
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApplicationContextRunner runner() {
        var runner = new ApplicationContextRunner().withUserConfiguration(
                BeautyAppointmentConfiguration.class, BeautyMerchantClientConfiguration.class)
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=standalone")
                .withBean(InternalHttp.class, () -> new InternalHttp(service -> {
                    throw new AssertionError("target lookup while constructing source/executor graph");
                }, "key"))
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
                .withBean(ServiceCatalogQueryService.class, () -> mock(ServiceCatalogQueryService.class))
                .withBean("beautyOutboxPublisher", OutboxPublisher.class, () -> mock(OutboxPublisher.class));
        for (Class type : new Class[] {BeautyMappers.AppointmentMapper.class,
                BeautyMappers.AppointmentPreparationMapper.class, BeautyMappers.AppointmentOperationMapper.class,
                BeautyMappers.AppointmentCurrentMapper.class, BeautyMappers.AppointmentArrangementMapper.class,
                BeautyMappers.AppointmentResourceIntentMapper.class, BeautyMappers.AppointmentResourceFenceMapper.class,
                BeautyMappers.AppointmentItemMapper.class, BeautyMappers.AppointmentAssignmentMapper.class,
                BeautyMappers.ActionLogMapper.class, BeautyMappers.AppointmentResourceDecisionMapper.class,
                BeautyMappers.ServiceRequirementMapper.class, BeautyMappers.ServiceDefinitionMapper.class,
                BeautyMappers.ServiceDefinitionVersionMapper.class, BeautyMappers.StorePolicyMapper.class,
                BeautyMappers.StorePolicyVersionMapper.class, BeautyMappers.ReceptionOperationMapper.class,
                BeautyMappers.GuestClaimReceiptMapper.class, BeautyMappers.ArrivalChallengeMapper.class,
                BeautyMappers.WorkOrderMapper.class, BeautyMappers.WorkOrderContextMapper.class,
                BeautyMappers.ServiceTaskMapper.class, BeautyMappers.ServiceStepMapper.class,
                BeautyMappers.ServiceParticipationMapper.class, BeautyMappers.ServiceSubmissionMapper.class}) {
            runner = runner.withBean(type, () -> mock(type));
        }
        return runner;
    }
    @Test void standaloneServerHasLocalUniqueSourceAndRemoteOwnerWithoutProviderConstructorCycle() {
        runner().withPropertyValues("spring.profiles.active=api").run(ctx -> {
            assertThat(ctx).hasNotFailed().hasSingleBean(ResourceSourceAuthorityPort.class)
                    .hasSingleBean(ResourceBookingPort.class).hasSingleBean(WorkOrderService.class)
                    .hasSingleBean(AppointmentRecoveryJob.class)
                    .hasSingleBean(JobDeclaration.class);
            assertThat(ctx.getBean(JobDeclaration.class).handlerName())
                    .isEqualTo(ctx.getBean(AppointmentRecoveryJob.class).name());
            assertThat(ctx.getBean(ResourceSourceAuthorityPort.class)).isInstanceOf(AppointmentSourceAuthorityPortImpl.class);
            assertThat(ctx.getBean(ResourceBookingPort.class)).isInstanceOf(RemoteResourceBookingPort.class);
        });
    }
    @Test void opsAndNoApiShapeDoNotRegisterRecoveryJob() {
        for (String profiles : new String[] {"ops", ""}) {
            runner().withPropertyValues("spring.profiles.active=" + profiles).run(ctx -> assertThat(ctx)
                    .hasNotFailed().doesNotHaveBean(AppointmentRecoveryJob.class).doesNotHaveBean(JobDeclaration.class));
        }
    }
}
