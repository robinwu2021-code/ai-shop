package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.ReceptionIdentity;
import ai.neargo.shop.beauty.policy.ServiceSubmissionAccess;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.ServiceSourceBinding;
import ai.neargo.shop.beauty.service.impl.ServiceSourceObservation;
import ai.neargo.shop.beauty.service.impl.ServiceSubmissionQuery;
import ai.neargo.shop.beauty.service.impl.ServiceSubmissionServiceImpl;
import ai.neargo.shop.beauty.service.impl.WorkOrderBillServiceImpl;
import ai.neargo.shop.beauty.service.impl.WorkOrderPreparation;
import ai.neargo.shop.spi.trade.CheckoutPort;
import ai.neargo.shop.spi.trade.ManualServiceAdmissionPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * Local source orchestration keeps shared checkout ownership and current consumer authority
 * separate.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautySourceConfiguration {
    @Bean
    ServiceSourceObservation serviceSourceObservation(
            BeautyMappers.SourceObservationMapper rows,
            ObjectProvider<CheckoutPort> checkout,
            ObjectProvider<ManualServiceAdmissionPort> manual,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json,
            @Qualifier("beautyReceptionClock") Clock clock) {
        return new ServiceSourceObservation(rows, checkout, manual, manager, json, clock);
    }

    @Bean
    ServiceSubmissionAccess serviceSubmissionAccess(
            AppointmentAccess appointments,
            ReceptionIdentity identity,
            BeautyMappers.GuestClaimReceiptMapper receipts,
            ObjectProvider<MerchantOperatorAccessPort> operators) {
        return new ServiceSubmissionAccess(appointments, identity, receipts, operators);
    }

    @Bean
    ServiceSubmissionQuery serviceSubmissionQuery(
            AppointmentPersistence db,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.WorkOrderContextMapper contexts,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ReceptionOperationMapper receptions,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.ServiceDefinitionMapper definitions,
            BeautyMappers.ServiceDefinitionVersionMapper versions) {
        return new ServiceSubmissionQuery(
                db, orders, contexts, tasks, receptions, submissions, definitions, versions);
    }

    @Bean
    ServiceSourceBinding serviceSourceBinding(
            AppointmentPersistence db,
            ServiceSubmissionQuery query,
            BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings,
            @Qualifier("beautyReceptionClock") Clock clock) {
        return new ServiceSourceBinding(
                db, query, observations, guards, orders, tasks, submissions, bindings, clock);
    }

    @Bean
    ServiceSubmissionServiceImpl serviceSubmissionServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionAccess access,
            ServiceSubmissionQuery query,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            ObjectProvider<CheckoutPort> checkout,
            ObjectProvider<ManualServiceAdmissionPort> manual,
            @Qualifier("beautyReceptionClock") Clock clock,
            ServiceSourceObservation observations,
            ServiceSourceBinding binding,
            WorkOrderPreparation preparation,
            BeautyMappers.TradeBindingMapper bindings) {
        return new ServiceSubmissionServiceImpl(
                db,
                access,
                query,
                orders,
                tasks,
                submissions,
                bindings,
                checkout,
                clock,
                observations,
                binding,
                preparation,
                manual);
    }

    @Bean
    WorkOrderBillServiceImpl workOrderBillServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionAccess access,
            ServiceSubmissionQuery query,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings,
            ObjectProvider<CheckoutPort> checkout,
            ObjectProvider<ManualServiceAdmissionPort> manual,
            ServiceSourceObservation observations,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            @Qualifier("beautyReceptionClock") Clock clock) {
        return new WorkOrderBillServiceImpl(
                db,
                access,
                query,
                orders,
                tasks,
                submissions,
                bindings,
                checkout,
                observations,
                manager,
                clock,
                manual);
    }
}
