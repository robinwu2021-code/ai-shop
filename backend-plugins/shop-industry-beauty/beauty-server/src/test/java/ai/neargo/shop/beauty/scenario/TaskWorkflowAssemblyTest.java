package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import ai.neargo.shop.beauty.config.BeautyAppointmentConfiguration;
import ai.neargo.shop.beauty.config.BeautyCheckoutClientConfiguration;
import ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration;
import ai.neargo.shop.beauty.config.BeautySourceConfiguration;
import ai.neargo.shop.beauty.config.TaskWorkflowConfiguration;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.mapper.TaskWorkflowMapper;
import ai.neargo.shop.beauty.mapper.WorkOrderWorkbenchMapper;
import ai.neargo.shop.beauty.port.RemoteExecutionRuntimePort;
import ai.neargo.shop.beauty.port.ServiceTaskStartAuthorityPortImpl;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.service.ServiceTaskActionService;
import ai.neargo.shop.beauty.service.ServiceTaskWorkflowService;
import ai.neargo.shop.beauty.service.impl.ServiceTaskActionServiceImpl;
import ai.neargo.shop.beauty.service.impl.ServiceTaskAssignmentServiceImpl;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl;
import ai.neargo.shop.beauty.service.impl.ServiceTaskStockPreparationCoordinator;
import ai.neargo.shop.beauty.service.impl.ServiceTaskWorkflowServiceImpl;
import ai.neargo.shop.beauty.service.impl.TaskAcceptanceCoordinator;
import ai.neargo.shop.beauty.service.impl.TaskStartCoordinator;
import ai.neargo.shop.beauty.service.impl.TaskStartOriginalReader;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.spi.resource.ResourceBookingExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceExecutionEvidencePort;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.resource.ResourceOccupationPort;
import ai.neargo.shop.spi.resource.ResourceQualificationPort;
import ai.neargo.shop.spi.resource.ServiceTaskStartAuthorityPort;
import ai.neargo.shop.spi.resource.ServiceTaskWorkflowAuthorityPort;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ServiceExecutionCompletionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.spi.trade.ServiceExecutionStartPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import javax.sql.DataSource;

/**
 * Cold production source-bean assembly only; no two-process deployment, database migration or owner
 * effect is claimed.
 */
class TaskWorkflowAssemblyTest {
    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApplicationContextRunner source(DataSource dataSource) {
        var runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(
                                BeautyAppointmentConfiguration.class,
                                BeautySourceConfiguration.class,
                                TaskWorkflowConfiguration.class)
                        .withPropertyValues(
                                "shop.beauty.enabled=true",
                                "spring.profiles.active=api",
                                "shop.job.enabled=false",
                                "shop.services.internal-token=assembly-key")
                        .withBean("dataSource", DataSource.class, () -> dataSource)
                        .withBean(
                                "transactionManager",
                                PlatformTransactionManager.class,
                                () -> mock(PlatformTransactionManager.class))
                        .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                        .withBean(
                                ServiceCatalogQueryService.class,
                                () -> mock(ServiceCatalogQueryService.class))
                        .withBean(
                                "beautyOutboxPublisher",
                                OutboxPublisher.class,
                                () -> mock(OutboxPublisher.class))
                        .withBean(TaskWorkflowMapper.class, () -> mock(TaskWorkflowMapper.class))
                        .withBean(
                                WorkOrderWorkbenchMapper.class,
                                () -> mock(WorkOrderWorkbenchMapper.class));
        for (Class type : BeautyMappers.class.getDeclaredClasses()) {
            if (type.isInterface()) runner = runner.withBean(type, () -> mock(type));
        }
        return runner;
    }

    private void actualSourceGraph(AssertableApplicationContext context) {
        assertThat(context)
                .hasNotFailed()
                .hasSingleBean(BeautySourceConfiguration.class)
                .hasSingleBean(TaskWorkflowConfiguration.class)
                .hasSingleBean(ServiceTaskExecutionServiceImpl.class)
                .hasSingleBean(ServiceTaskStockPreparationCoordinator.class)
                .hasSingleBean(TaskStartOriginalReader.class)
                .hasSingleBean(TaskStartCoordinator.class)
                .hasSingleBean(TaskAcceptanceCoordinator.class)
                .hasSingleBean(ServiceTaskAssignmentServiceImpl.class)
                .hasSingleBean(ServiceTaskActionService.class)
                .hasSingleBean(ServiceTaskWorkflowService.class)
                .hasSingleBean(ServiceTaskStartAuthorityPort.class)
                .hasSingleBean(ServiceTaskWorkflowAuthorityPort.class);
        assertThat(context.getBean(ServiceTaskActionService.class))
                .isInstanceOf(ServiceTaskActionServiceImpl.class);
        assertThat(context.getBean(ServiceTaskStartAuthorityPort.class))
                .isInstanceOf(ServiceTaskStartAuthorityPortImpl.class);
        assertThat(context.getBean(ServiceTaskWorkflowService.class))
                .isInstanceOf(ServiceTaskWorkflowServiceImpl.class)
                .isSameAs(context.getBean(ServiceTaskWorkflowAuthorityPort.class));
    }

    @Test
    void embeddedLoadsUniqueRealSourceWorkflowGraphWithNativeAndCoreOwnerBoundaries() {
        var dataSource = mock(DataSource.class);
        source(dataSource)
                .withPropertyValues("shop.beauty.deployment=embedded")
                .withBean(
                        MerchantOperatorAccessPort.class,
                        () -> mock(MerchantOperatorAccessPort.class))
                .withBean(ResourceOccupationPort.class, () -> mock(ResourceOccupationPort.class))
                .withBean(
                        ResourceOccupationLifecyclePort.class,
                        () -> mock(ResourceOccupationLifecyclePort.class))
                .withBean(
                        ResourceExecutionEvidencePort.class,
                        () -> mock(ResourceExecutionEvidencePort.class))
                .withBean(
                        ResourceBookingExecutionEvidencePort.class,
                        () -> mock(ResourceBookingExecutionEvidencePort.class))
                .withBean(
                        ResourceQualificationPort.class,
                        () -> mock(ResourceQualificationPort.class))
                .withBean(ServiceExecutionPort.class, () -> mock(ServiceExecutionPort.class))
                .withBean(
                        ServiceExecutionStartPort.class,
                        () -> mock(ServiceExecutionStartPort.class))
                .withBean(
                        ServiceExecutionCompletionPort.class,
                        () -> mock(ServiceExecutionCompletionPort.class))
                .withBean(
                        ServiceExecutionEvidencePort.class,
                        () -> mock(ServiceExecutionEvidencePort.class))
                .withBean(
                        ServiceExecutionPreparationPort.class,
                        () -> mock(ServiceExecutionPreparationPort.class))
                .run(
                        context -> {
                            actualSourceGraph(context);
                            assertThat(context).doesNotHaveBean(RemoteExecutionRuntimePort.class);
                            verifyNoInteractions(dataSource);
                        });
    }

    @Test
    void
            standaloneLoadsSameRealSourceGraphWithActualTypedRemoteRuntimeAndNoNativeImplementationClasses() {
        var dataSource = mock(DataSource.class);
        source(dataSource)
                .withClassLoader(
                        new FilteredClassLoader("ai.neargo.shop.merchant", "ai.neargo.shop.trade"))
                .withPropertyValues("shop.beauty.deployment=standalone")
                .withUserConfiguration(
                        BeautyCheckoutClientConfiguration.class,
                        BeautyMerchantClientConfiguration.class)
                .withBean(
                        InternalHttp.class,
                        () ->
                                new InternalHttp(
                                        service -> Optional.of("http://127.0.0.1:19999"),
                                        "assembly-key"))
                .run(
                        context -> {
                            actualSourceGraph(context);
                            assertThat(context)
                                    .hasSingleBean(RemoteExecutionRuntimePort.class)
                                    .hasSingleBean(ResourceOccupationPort.class)
                                    .hasSingleBean(ResourceOccupationLifecyclePort.class)
                                    .hasSingleBean(ResourceExecutionEvidencePort.class)
                                    .hasSingleBean(ResourceQualificationPort.class)
                                    .hasSingleBean(ResourceBookingExecutionEvidencePort.class)
                                    .hasSingleBean(ServiceExecutionStartPort.class)
                                    .hasSingleBean(ServiceExecutionCompletionPort.class)
                                    .hasSingleBean(ServiceExecutionEvidencePort.class)
                                    .hasSingleBean(ServiceExecutionPort.class)
                                    .hasSingleBean(ServiceExecutionPreparationPort.class)
                                    .hasSingleBean(CheckoutPort.class);
                            var remote = context.getBean(RemoteExecutionRuntimePort.class);
                            for (Class<?> port :
                                    new Class<?>[] {
                                        ResourceOccupationPort.class,
                                        ResourceOccupationLifecyclePort.class,
                                        ResourceExecutionEvidencePort.class,
                                        ResourceQualificationPort.class,
                                        ResourceBookingExecutionEvidencePort.class,
                                        ServiceExecutionStartPort.class,
                                        ServiceExecutionCompletionPort.class,
                                        ServiceExecutionEvidencePort.class
                                    }) {
                                assertThat(context.getBean(port)).isSameAs(remote);
                            }
                            verifyNoInteractions(dataSource);
                        });
    }

    @Test
    void disabledPluginLoadsNoWorkflowAndRequiresNeitherDataSourceNorOwnerProvider() {
        new ApplicationContextRunner()
                .withUserConfiguration(
                        BeautyAppointmentConfiguration.class,
                        BeautySourceConfiguration.class,
                        TaskWorkflowConfiguration.class)
                .withPropertyValues("shop.beauty.enabled=false", "shop.beauty.deployment=embedded")
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(DataSource.class)
                                        .doesNotHaveBean(TaskStartCoordinator.class)
                                        .doesNotHaveBean(TaskAcceptanceCoordinator.class)
                                        .doesNotHaveBean(ServiceTaskWorkflowService.class)
                                        .doesNotHaveBean(ResourceOccupationPort.class));
    }
}
