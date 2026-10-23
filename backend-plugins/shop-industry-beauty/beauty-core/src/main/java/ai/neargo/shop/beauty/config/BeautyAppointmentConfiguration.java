package ai.neargo.shop.beauty.config;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.shop.beauty.job.AppointmentRecoveryJob;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.CustomerOwnership;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
import ai.neargo.shop.beauty.policy.ServiceSubmissionAccess;
import ai.neargo.shop.beauty.port.AppointmentSourceAuthorityPortImpl;
import ai.neargo.shop.beauty.service.ServiceCatalogQueryService;
import ai.neargo.shop.beauty.service.ServiceSubmissionService;
import ai.neargo.shop.beauty.service.impl.AppointmentAvailabilityServiceImpl;
import ai.neargo.shop.beauty.service.impl.AppointmentCoordinator;
import ai.neargo.shop.beauty.service.impl.AppointmentMatching;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.AppointmentPreparationService;
import ai.neargo.shop.beauty.service.impl.AppointmentRecovery;
import ai.neargo.shop.beauty.service.impl.AppointmentServiceImpl;
import ai.neargo.shop.beauty.service.impl.AppointmentSourceAuthority;
import ai.neargo.shop.beauty.service.impl.GuestRegistrationServiceImpl;
import ai.neargo.shop.beauty.service.impl.ReceptionCoordinator;
import ai.neargo.shop.beauty.service.impl.ServiceOperationQueryServiceImpl;
import ai.neargo.shop.beauty.service.impl.ServiceOperationRecoveryServiceImpl;
import ai.neargo.shop.beauty.service.impl.ServiceSubmissionQuery;
import ai.neargo.shop.beauty.service.impl.WorkOrderCoordinator;
import ai.neargo.shop.beauty.service.impl.WorkOrderPreparation;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.personnel.PersonnelFactsPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.schedule.ScheduleWindowsPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/** Source authority has no eager executor dependency; external provider capabilities resolve per call. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
@Import(BeautyReceptionConfiguration.class)
public class BeautyAppointmentConfiguration {
    @Bean
    AppointmentPersistence appointmentPersistence(BeautyMappers.AppointmentMapper roots,
            BeautyMappers.AppointmentPreparationMapper preparations, BeautyMappers.AppointmentOperationMapper
            operations, BeautyMappers.AppointmentCurrentMapper currents, BeautyMappers.AppointmentArrangementMapper
            arrangements, BeautyMappers.AppointmentResourceIntentMapper intents,
            BeautyMappers.AppointmentResourceFenceMapper fences, BeautyMappers.AppointmentItemMapper items,
            BeautyMappers.AppointmentAssignmentMapper assignments, BeautyMappers.ActionLogMapper actions,
            ObjectMapper json, PlatformTransactionManager
            manager) {
        return new AppointmentPersistence(roots, preparations, operations, currents, arrangements, intents, fences,
                items, assignments, actions, json, manager);
    }
    @Bean
    AppointmentAccess appointmentAccess(ObjectProvider<MerchantOperatorAccessPort> access,
            CustomerOwnership ownership) {
        return new AppointmentAccess(access, ownership);
    }
    @Bean
    AppointmentMatching appointmentMatching(ServiceCatalogQueryService catalog,
            ObjectProvider<ServiceCatalogFactsPort> stores, ObjectProvider<ResourceCandidatesPort> candidates,
            ObjectProvider<PersonnelFactsPort> personnel, ObjectProvider<ScheduleWindowsPort> schedules,
            BeautyMappers.ServiceRequirementMapper requirements) {
        return new AppointmentMatching(catalog, stores, candidates, personnel, schedules, requirements);
    }
    @Bean
    AppointmentPreparationService appointmentPreparationService(AppointmentPersistence db, AppointmentAccess access,
            ObjectProvider<CustomerIdentityPort> customers, ObjectProvider<ServiceCatalogFactsPort> products,
            ReceptionIdentity identity, BeautyMappers.ReceptionOperationMapper receptions,
            WorkOrderPreparation workOrders, @Qualifier("beautyReceptionClock") Clock clock) {
        return new AppointmentPreparationService(db, access, customers, products, identity, receptions, workOrders,
                clock);
    }

    @Bean
    AppointmentCoordinator appointmentCoordinator(
            AppointmentPersistence db,
            AppointmentAccess access,
            ObjectProvider<ResourceBookingPort> owner,
            @Qualifier("beautyOutboxPublisher") OutboxPublisher publisher,
            ReceptionAccess receptionAccess) {
        return new AppointmentCoordinator(db, access, owner, publisher, receptionAccess);
    }
    @Bean
    AppointmentSourceAuthority appointmentSourceAuthority(AppointmentPersistence db, AppointmentAccess access,
            BeautyMappers.AppointmentResourceDecisionMapper decisions, ReceptionAccess receptionAccess) {
        return new AppointmentSourceAuthority(db, access, decisions, receptionAccess);
    }
    @Bean
    AppointmentSourceAuthorityPortImpl appointmentSourceAuthorityPortImpl(AppointmentSourceAuthority authority) {
        return new AppointmentSourceAuthorityPortImpl(authority);
    }
    @Bean
    AppointmentServiceImpl appointmentServiceImpl(AppointmentPersistence db, AppointmentAccess access,
            AppointmentMatching matching, AppointmentPreparationService preparation, AppointmentCoordinator
            coordinator, ReceptionAccess receptionAccess, WorkOrderCoordinator workOrders) {
        return new AppointmentServiceImpl(db, access, matching, preparation, coordinator, receptionAccess, workOrders);
    }
    @Bean
    AppointmentAvailabilityServiceImpl appointmentAvailabilityServiceImpl(AppointmentMatching matching,
            AppointmentAccess access) {
        return new AppointmentAvailabilityServiceImpl(matching, access);
    }

    @Bean
    ServiceOperationQueryServiceImpl serviceOperationQueryServiceImpl(
            AppointmentPersistence db,
            AppointmentAccess access,
            BeautyMappers.ReceptionOperationMapper receptions,
            BeautyMappers.ServiceSubmissionMapper submissions,
            ServiceSubmissionQuery query,
            ServiceSubmissionAccess sourceAccess) {
        return new ServiceOperationQueryServiceImpl(
                db, access, receptions, submissions, query, sourceAccess);
    }
    @Bean
    AppointmentRecovery appointmentRecovery(AppointmentPersistence db, AppointmentCoordinator coordinator) {
        return new AppointmentRecovery(db, coordinator);
    }
    @Bean
    @Profile("api")
    AppointmentRecoveryJob appointmentRecoveryJob(AppointmentRecovery recovery) {
        return new AppointmentRecoveryJob(recovery);
    }
    /** External workers discover the bounded reconciliation task; this declaration starts no timer. */
    @Bean
    @Profile("api")
    JobDeclaration beautyAppointmentRecoveryDeclaration() {
        return new JobDeclaration("beauty-appointment-recovery", "美业预约原操作恢复",
                "仅核验已绑定的原终态；需要用户授权的步骤保持待处理", "beauty-core",
                "0 * * * * *", true, 60, 120, true, true);
    }

    @Bean
    ServiceOperationRecoveryServiceImpl serviceOperationRecoveryServiceImpl(
            AppointmentPersistence db,
            AppointmentAccess access,
            AppointmentCoordinator coordinator,
            BeautyMappers.ReceptionOperationMapper receptions,
            ReceptionAccess receptionAccess,
            ReceptionCoordinator reception,
            WorkOrderCoordinator orders,
            GuestRegistrationServiceImpl guests,
            BeautyMappers.ServiceSubmissionMapper submissions,
            ServiceSubmissionService sources) {
        return new ServiceOperationRecoveryServiceImpl(
                db,
                access,
                coordinator,
                receptions,
                receptionAccess,
                reception,
                orders,
                guests,
                submissions,
                sources);
    }
}
