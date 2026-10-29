package ai.neargo.shop.beauty.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ai.neargo.shop.beauty.config.BeautyAppointmentConfiguration;
import ai.neargo.shop.beauty.config.BeautyChannelAutoConfiguration;
import ai.neargo.shop.beauty.config.BeautyCheckoutClientConfiguration;
import ai.neargo.shop.beauty.config.BeautyMerchantClientConfiguration;
import ai.neargo.shop.beauty.dto.OpenAppointmentWorkOrderReq;
import ai.neargo.shop.beauty.job.ReceptionRecoveryJob;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.port.RemoteCheckoutPort;
import ai.neargo.shop.beauty.server.api.InternalBeautyGuestController;
import ai.neargo.shop.beauty.server.api.InternalBeautyOperationController;
import ai.neargo.shop.beauty.server.api.InternalBeautyReceptionController;
import ai.neargo.shop.beauty.server.api.InternalBeautySubmissionController;
import ai.neargo.shop.beauty.server.api.InternalBeautyTraceController;
import ai.neargo.shop.beauty.server.api.InternalBeautyWorkOrderController;
import ai.neargo.shop.beauty.service.AppointmentService;
import ai.neargo.shop.beauty.service.GuestRegistrationService;
import ai.neargo.shop.beauty.service.ReceptionService;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.spi.BeautyReceptionInternalApi;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.svc.InternalHttp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

/**
 * Context selection is component evidence only; packaged/two-process deployment remains deferred.
 */
class BeautyBm04AssemblyTest {
    @Test
    void deliveredAppointmentOpeningRequiresEveryImplementation() throws Exception {
        assertThat(
                        AppointmentService.class
                                .getMethod(
                                        "openWorkOrderFromAppointment",
                                        String.class,
                                        String.class,
                                        String.class,
                                        String.class,
                                        OpenAppointmentWorkOrderReq.class,
                                        String.class)
                                .isDefault())
                .isFalse();
    }

    @Test
    void wireAddsNoMigrationAndPublicApiCountsRemainStable() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("backend/pom.xml"))) {
            root = root.getParent();
        }
        var frozen =
                Map.ofEntries(
                        Map.entry(
                                "V1__beauty_p1_tables.sql",
                                "ac2a539664c42fdb7e4893f1359879fc5affbe4b24f95aae1809cd25f11061a4"),
                        Map.entry(
                                "V2__beauty_appointment_resource_fence.sql",
                                "5b6768928018b68635c9c081b200a2cb6202d40645c5db62c91c558cc49d4114"),
                        Map.entry(
                                "V3__beauty_appointment_business.sql",
                                "c8d54df07cabc5b0903e0dd4fba73e2b7fc4aa75539a148837cc5837deaa8da7"),
                        Map.entry(
                                "V4__beauty_appointment_resource_decision.sql",
                                "7df4e8d2e3aeeb57bf1e704004957d996912a17021c5f58842ac91152e7a003a"),
                        Map.entry(
                                "V5__beauty_reception_work_order.sql",
                                "8e9aec144736ee3502fb9c0f9c4c95edf6f4a02c1cecbd597ff0c3b51aa4eab5"),
                        Map.entry(
                                "V6__beauty_reception_identity_guard.sql",
                                "220ed777c56a77c22cf835f2a5bcc42ccb41cc98a4348831df06e0e404b89f20"),
                        Map.entry(
                                "V7__beauty_appointment_assignment_units.sql",
                                "8894786048bf04ca45eff129a83fa7776da867994d72dff2343a91b215d6d3f2"),
                        Map.entry(
                                "V8__beauty_source_observation.sql",
                                "c5b54d574624a5265366cf4bf7897ec949d107bd302f171539c461a89a9d8343"),
                        Map.entry(
                                "V599__checkout_source_foundation.sql",
                                "5a8e346341e5b378403761f2a77555c0558ff6b27a6071584207a9d8f9e14d3b"),
                        Map.entry(
                                "V600__checkout_payment_operation.sql",
                                "ca76b6512cf79c6f25fa71a4441f533e03cc609c6320611a0dcdc9634a10f745"));
        for (var entry : frozen.entrySet()) {
            String prefix =
                    entry.getKey().startsWith("V53") || entry.getKey().startsWith("V54")
                            ? "backend/shop-app/src/main/resources/db/migration/"
                            : "backend-plugins/shop-industry-beauty/beauty-core/src/main/resources/db/industry/beauty/";
            assertThat(
                            HexFormat.of()
                                    .formatHex(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(
                                                            Files.readAllBytes(
                                                                    root.resolve(
                                                                            prefix
                                                                                    + entry
                                                                                            .getKey())))))
                    .isEqualTo(entry.getValue());
        }
        var metadata =
                JsonMapper.builder()
                        .build()
                        .readTree(
                                Files.readString(
                                        root.resolve(
                                                "docs/technical/design/schema/beauty-api-design.json")));
        int targets = 0;
        for (var module : metadata.path("modules")) {
            targets += module.path("apis").size();
        }
        assertThat(targets).isEqualTo(160);
        assertThat(metadata.path("bm04Protocol").path("publicMethods").size()).isEqualTo(18);
        assertThat(
                        Arrays.stream(BeautyReceptionInternalApi.class.getDeclaredMethods())
                                .filter(
                                        method ->
                                                method.getReturnType().equals(InternalReply.class))
                                .count())
                .isEqualTo(19);
        assertThat(metadata.path("bm04Protocol").path("status").asString())
                .contains("DEPLOYMENT_DEFERRED");
    }

    private ApplicationContextRunner host() {
        return new ApplicationContextRunner()
                .withClassLoader(
                        new FilteredClassLoader(
                                "ai.neargo.shop.beauty.config.BeautyAutoConfiguration"))
                .withUserConfiguration(BeautyChannelAutoConfiguration.class)
                .withBean(
                        InternalHttp.class,
                        () -> new InternalHttp(service -> Optional.empty(), "secret"))
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withPropertyValues(
                        "shop.services.internal-token=secret", "shop.beauty.deployment=standalone");
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApplicationContextRunner server() {
        var runner =
                new ApplicationContextRunner()
                        .withUserConfiguration(
                                BeautyAppointmentConfiguration.class,
                                BeautyMerchantClientConfiguration.class,
                                BeautyCheckoutClientConfiguration.class,
                                InternalBeautyGuestController.class,
                                InternalBeautyOperationController.class,
                                InternalBeautyReceptionController.class,
                                InternalBeautySubmissionController.class,
                                InternalBeautyTraceController.class,
                                InternalBeautyWorkOrderController.class)
                        .withPropertyValues(
                                "shop.beauty.enabled=true", "shop.beauty.deployment=standalone")
                        .withBean(
                                InternalHttp.class,
                                () ->
                                        new InternalHttp(
                                                service -> {
                                                    throw new AssertionError(
                                                            "target lookup while constructing"
                                                                + " source/executor graph");
                                                },
                                                "key"))
                        .withBean(ObjectMapper.class, ObjectMapper::new)
                        .withBean(
                                "transactionManager",
                                PlatformTransactionManager.class,
                                () -> mock(PlatformTransactionManager.class))
                        .withBean(
                                ServiceCatalogQueryService.class,
                                () -> mock(ServiceCatalogQueryService.class))
                        .withBean(
                                "beautyOutboxPublisher",
                                OutboxPublisher.class,
                                () -> mock(OutboxPublisher.class));
        for (Class type :
                new Class[] {
                    BeautyMappers.AppointmentMapper.class,
                    BeautyMappers.AppointmentPreparationMapper.class,
                    BeautyMappers.AppointmentOperationMapper.class,
                    BeautyMappers.AppointmentCurrentMapper.class,
                    BeautyMappers.AppointmentArrangementMapper.class,
                    BeautyMappers.AppointmentResourceIntentMapper.class,
                    BeautyMappers.AppointmentResourceFenceMapper.class,
                    BeautyMappers.AppointmentItemMapper.class,
                    BeautyMappers.AppointmentAssignmentMapper.class,
                    BeautyMappers.ActionLogMapper.class,
                    BeautyMappers.AppointmentResourceDecisionMapper.class,
                    BeautyMappers.ServiceRequirementMapper.class,
                    BeautyMappers.ServiceDefinitionMapper.class,
                    BeautyMappers.ServiceDefinitionVersionMapper.class,
                    BeautyMappers.StorePolicyMapper.class,
                    BeautyMappers.StorePolicyVersionMapper.class,
                    BeautyMappers.ReceptionOperationMapper.class,
                    BeautyMappers.GuestClaimReceiptMapper.class,
                    BeautyMappers.ArrivalChallengeMapper.class,
                    BeautyMappers.WorkOrderMapper.class,
                    BeautyMappers.WorkOrderContextMapper.class,
                    BeautyMappers.ServiceTaskMapper.class,
                    BeautyMappers.ServiceStepMapper.class,
                    BeautyMappers.ServiceParticipationMapper.class,
                    BeautyMappers.ServiceSubmissionMapper.class,
                    BeautyMappers.SourceObservationMapper.class,
                    BeautyMappers.SourceAllocationGuardMapper.class,
                    BeautyMappers.TradeBindingMapper.class
                }) {
            runner = runner.withBean(type, () -> mock(type));
        }
        return runner;
    }

    @Test
    void embeddedHostServerRetailDisabledAndOpsSelectOnlyExpectedBeans() {
        server().withPropertyValues("spring.profiles.active=api")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .hasSingleBean(CheckoutPort.class)
                                    .hasSingleBean(InternalBeautyWorkOrderController.class)
                                    .hasSingleBean(WorkOrderService.class)
                                    .hasSingleBean(ServiceSubmissionService.class)
                                    .hasSingleBean(WorkOrderBillService.class);
                            assertThat(context.getBean(CheckoutPort.class))
                                    .isInstanceOf(RemoteCheckoutPort.class);
                            assertThat(
                                            context.getBean(WorkOrderService.class)
                                                    .getClass()
                                                    .getSimpleName())
                                    .isEqualTo("WorkOrderServiceImpl");
                        });
        for (String profile : new String[] {"ops", ""}) {
            server().withPropertyValues("spring.profiles.active=" + profile)
                    .run(
                            context -> {
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(InternalBeautyGuestController.class)
                                        .doesNotHaveBean(InternalBeautyOperationController.class)
                                        .doesNotHaveBean(InternalBeautyReceptionController.class)
                                        .doesNotHaveBean(InternalBeautySubmissionController.class)
                                        .doesNotHaveBean(InternalBeautyTraceController.class)
                                        .doesNotHaveBean(InternalBeautyWorkOrderController.class)
                                        .doesNotHaveBean(ReceptionRecoveryJob.class);
                            });
        }
        host().withPropertyValues("shop.beauty.enabled=true", "shop.services.internal-token=")
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .hasSingleBean(WorkOrderService.class));
        host().withPropertyValues("shop.beauty.enabled=true")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            for (Class<?> service :
                                    new Class<?>[] {
                                        GuestRegistrationService.class,
                                        ReceptionService.class,
                                        WorkOrderService.class,
                                        ServiceSubmissionService.class,
                                        WorkOrderBillService.class
                                    }) {
                                assertThat(context.getBeansOfType(service)).hasSize(1);
                                assertThat(context.getBean(service).getClass().getSimpleName())
                                        .startsWith("Remote");
                            }
                            assertThat(context).doesNotHaveBean(DataSource.class);
                        });
        host().withPropertyValues("shop.beauty.enabled=false")
                .run(
                        context -> {
                            assertThat(context)
                                    .hasNotFailed()
                                    .doesNotHaveBean(GuestRegistrationService.class)
                                    .doesNotHaveBean(WorkOrderService.class)
                                    .doesNotHaveBean(DataSource.class);
                        });
        host().run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(GuestRegistrationService.class));
        new ApplicationContextRunner()
                .withUserConfiguration(BeautyChannelAutoConfiguration.class)
                .withBean(
                        InternalHttp.class,
                        () -> new InternalHttp(service -> Optional.empty(), "secret"))
                .withPropertyValues("shop.beauty.enabled=true", "shop.beauty.deployment=embedded")
                .run(
                        context ->
                                assertThat(context)
                                        .hasNotFailed()
                                        .doesNotHaveBean(GuestRegistrationService.class));
    }
}
