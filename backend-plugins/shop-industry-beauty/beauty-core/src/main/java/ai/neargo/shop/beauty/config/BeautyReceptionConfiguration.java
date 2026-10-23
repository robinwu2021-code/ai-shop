package ai.neargo.shop.beauty.config;

import ai.neargo.job.api.JobDeclaration;
import ai.neargo.shop.beauty.job.ReceptionRecoveryJob;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.CustomerOwnership;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
import ai.neargo.shop.beauty.service.impl.AppointmentCoordinator;
import ai.neargo.shop.beauty.service.impl.AppointmentMatching;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.AppointmentPreparationService;
import ai.neargo.shop.beauty.service.impl.GuestRegistrationServiceImpl;
import ai.neargo.shop.beauty.service.impl.ReceptionCoordinator;
import ai.neargo.shop.beauty.service.impl.ReceptionPreparation;
import ai.neargo.shop.beauty.service.impl.ReceptionReassignment;
import ai.neargo.shop.beauty.service.impl.ReceptionRecovery;
import ai.neargo.shop.beauty.service.impl.ReceptionServiceImpl;
import ai.neargo.shop.beauty.service.impl.WorkOrderCoordinator;
import ai.neargo.shop.beauty.service.impl.WorkOrderPreparation;
import ai.neargo.shop.beauty.service.impl.WorkOrderQuery;
import ai.neargo.shop.beauty.service.impl.WorkOrderServiceImpl;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.product.ServiceCatalogFactsPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** Local reception capabilities use lazy real owner ports, retaining disabled and remote-only deployment shapes. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautyReceptionConfiguration {
    @Bean ReceptionRecovery receptionRecovery(AppointmentPersistence db, AppointmentCoordinator appointments,
            AppointmentPreparationService preparation, BeautyMappers.ReceptionOperationMapper rows,
            @Qualifier("beautyReceptionClock") Clock clock) {
        return new ReceptionRecovery(db, appointments, preparation, rows, clock);
    }
    @Bean
    @Profile("api")
    ReceptionRecoveryJob receptionRecoveryJob(ReceptionRecovery recovery) { return new ReceptionRecoveryJob(recovery); }
    /** Registers metadata only; no local timer or synthetic staff authority is installed. */
    @Bean
    @Profile("api")
    JobDeclaration beautyReceptionRecoveryDeclaration() {
        return new JobDeclaration("beauty-reception-recovery", "Beauty reception original operation recovery",
                "Reconcile original resources only; never accept commands, arrive or open orders", "beauty-core",
                "0 * * * * *", true, 60, 120, true, true);
    }
    @Bean CustomerOwnership customerOwnership(BeautyMappers.GuestClaimReceiptMapper receipts) {
        return new CustomerOwnership(receipts);
    }
    @Bean ReceptionAccess receptionAccess(AppointmentAccess access, ObjectProvider<MerchantOperatorAccessPort> owner) {
        return new ReceptionAccess(access, owner);
    }
    @Bean ReceptionIdentity receptionIdentity(ReceptionAccess access, ObjectProvider<GuestRegistrationPort> guests,
            ObjectProvider<CustomerIdentityPort> customers) { return new ReceptionIdentity(access, guests, customers); }
    @Bean ReceptionPreparation receptionPreparation(AppointmentPersistence db,
            BeautyMappers.ReceptionOperationMapper operations) { return new ReceptionPreparation(db, operations); }
    @Bean GuestRegistrationServiceImpl guestRegistrationServiceImpl(AppointmentPersistence db, ReceptionAccess access,
            ReceptionIdentity identity, ReceptionPreparation preparations, ObjectProvider<GuestRegistrationPort> owner,
            BeautyMappers.ReceptionOperationMapper operations, BeautyMappers.GuestClaimReceiptMapper receipts,
            @Qualifier("beautyOutboxPublisher") OutboxPublisher publisher) {
        return new GuestRegistrationServiceImpl(db, access, identity, preparations, owner, operations,
                receipts, publisher);
    }
    @Bean Clock beautyReceptionClock() { return Clock.systemUTC(); }
    @Bean WorkOrderPreparation workOrderPreparation(AppointmentPersistence db, AppointmentCoordinator appointments,
            BeautyMappers.ServiceDefinitionMapper definitions, BeautyMappers.ServiceDefinitionVersionMapper versions,
            BeautyMappers.ServiceRequirementMapper requirements, BeautyMappers.StorePolicyMapper policies,
            BeautyMappers.StorePolicyVersionMapper policyVersions, ObjectProvider<ServiceCatalogFactsPort> products) {
        return new WorkOrderPreparation(db, appointments, definitions, versions, requirements, policies,
                policyVersions, products);
    }
    @Bean ReceptionReassignment receptionReassignment(AppointmentPersistence db, AppointmentAccess access,
            ReceptionAccess receptionAccess, AppointmentCoordinator appointments, AppointmentMatching matching,
            AppointmentPreparationService preparations, WorkOrderPreparation workOrders) {
        return new ReceptionReassignment(db, access, receptionAccess, appointments, matching, preparations, workOrders);
    }
    @Bean WorkOrderCoordinator workOrderCoordinator(AppointmentPersistence db, AppointmentAccess access,
            ReceptionAccess receptionAccess, ReceptionIdentity identities, CustomerOwnership ownership,
            ReceptionPreparation operations,
            WorkOrderPreparation preparation, ReceptionReassignment reassignment, AppointmentCoordinator appointments,
            BeautyMappers.ReceptionOperationMapper receptionRows, BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.WorkOrderContextMapper contexts, BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            @Qualifier("beautyOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("beautyReceptionClock") Clock clock) {
        return new WorkOrderCoordinator(db, access, receptionAccess, identities, ownership, operations, preparation,
                reassignment, appointments, receptionRows, orders, contexts, tasks, submissions, publisher, clock);
    }
    @Bean WorkOrderQuery workOrderQuery(AppointmentPersistence db, BeautyMappers.WorkOrderContextMapper contexts,
            BeautyMappers.ServiceTaskMapper tasks, BeautyMappers.ServiceStepMapper steps,
            BeautyMappers.ServiceParticipationMapper participations,
            BeautyMappers.ServiceSubmissionMapper submissions, BeautyMappers.ReceptionOperationMapper operations) {
        return new WorkOrderQuery(db, contexts, tasks, steps, participations, submissions, operations);
    }
    @Bean WorkOrderServiceImpl workOrderServiceImpl(WorkOrderCoordinator coordinator, WorkOrderQuery query,
            AppointmentPersistence db, AppointmentAccess access, BeautyMappers.WorkOrderMapper orders) {
        return new WorkOrderServiceImpl(coordinator, query, db, access, orders);
    }
    @Bean ReceptionCoordinator receptionCoordinator(AppointmentPersistence db, AppointmentAccess access,
            ReceptionAccess receptionAccess, ReceptionIdentity identities, CustomerOwnership ownership,
            ReceptionPreparation preparations, AppointmentCoordinator appointments,
            BeautyMappers.ReceptionOperationMapper operations, BeautyMappers.ArrivalChallengeMapper challenges,
            @Qualifier("beautyOutboxPublisher") OutboxPublisher publisher,
            @Qualifier("beautyReceptionClock") Clock clock, ReceptionReassignment reassignment,
            AppointmentMatching matching, AppointmentPreparationService appointmentPreparation,
            WorkOrderCoordinator workOrders) {
        return new ReceptionCoordinator(db, access, receptionAccess, identities, ownership, preparations,
                appointments, operations, challenges, publisher, clock, reassignment,
                matching, appointmentPreparation, workOrders);
    }
    @Bean ReceptionServiceImpl receptionServiceImpl(AppointmentPersistence db, AppointmentAccess access,
            BeautyMappers.ArrivalChallengeMapper challenges, @Qualifier("beautyReceptionClock") Clock clock,
                    ReceptionCoordinator coordinator) {
        return new ReceptionServiceImpl(db, access, challenges, clock, coordinator);
    }
}
