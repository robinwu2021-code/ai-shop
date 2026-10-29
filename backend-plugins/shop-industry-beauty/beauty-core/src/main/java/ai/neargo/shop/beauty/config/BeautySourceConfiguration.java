package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.policy.ServiceExecutionStockIntentSchemaVerifier;
import ai.neargo.shop.beauty.service.impl.ServiceTaskStockPreparationCoordinator;
import ai.neargo.shop.beauty.service.impl.ServiceTaskPreparationServiceImpl;
import ai.neargo.shop.beauty.service.ServiceTaskPreparationService;
import ai.neargo.shop.spi.trade.ServiceExecutionPreparationPort;
import ai.neargo.shop.beauty.job.ServiceExecutionRecoveryJob;
import ai.neargo.job.api.JobDeclaration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import ai.neargo.shop.beauty.policy.ServiceExecutionSchemaVerifier;
import ai.neargo.shop.beauty.port.ServiceExecutionSourcePortImpl;
import ai.neargo.shop.beauty.service.impl.ServiceExecutionFenceCoordinator;
import ai.neargo.shop.beauty.service.impl.ServiceTaskExecutionServiceImpl;
import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;
import ai.neargo.shop.spi.personnel.PersonnelOperatorFactsPort;
import ai.neargo.shop.spi.trade.ServiceExecutionPort;
import javax.sql.DataSource;
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
import org.springframework.context.annotation.Profile;
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
    ServiceExecutionSchemaVerifier beautyServiceExecutionSchemaVerifier(
            @Qualifier("dataSource") DataSource source) {
        return new ServiceExecutionSchemaVerifier(source);
    }

    @Bean
    ServiceExecutionAccess serviceExecutionAccess(ObjectProvider<PersonnelOperatorFactsPort> personnel,
            @Qualifier("beautyReceptionClock") Clock clock) {
        return new ServiceExecutionAccess(personnel, clock);
    }

    @Bean
    ServiceExecutionFenceCoordinator serviceExecutionFenceCoordinator(ServiceExecutionSchemaVerifier schema,
            BeautyMappers.ServiceExecutionFenceMapper fences, BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards, BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks, BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.TradeBindingMapper bindings, ObjectMapper json,
            @Qualifier("beautyReceptionClock") Clock clock) {
        return new ServiceExecutionFenceCoordinator(schema, fences, observations, guards, orders, tasks,
                submissions, bindings, json, clock);
    }

    @Bean
    ServiceTaskExecutionServiceImpl serviceTaskExecutionServiceImpl(
            AppointmentPersistence db,
            ServiceSubmissionQuery query,
            ServiceExecutionAccess access,
            ServiceExecutionSchemaVerifier schema,
            BeautyMappers.SourceObservationMapper observations,
            BeautyMappers.SourceAllocationGuardMapper guards,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.TradeBindingMapper bindings,
            BeautyMappers.ServiceExecutionFenceMapper fences,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.GuestClaimReceiptMapper claims,
            ObjectProvider<ServiceExecutionPort> owner,
            ObjectProvider<ServiceTaskStockPreparationCoordinator> stock,
            @Qualifier("beautyReceptionClock") Clock clock) {
        var service =
                new ServiceTaskExecutionServiceImpl(
                        db,
                        query,
                        access,
                        schema,
                        observations,
                        guards,
                        orders,
                        tasks,
                        bindings,
                        fences,
                        submissions,
                        claims,
                        owner,
                        clock);
        service.setStockObservationProvider(stock);
        return service;
    }

    @Bean
    ServiceExecutionStockIntentSchemaVerifier beautyStockIntentSchema(
            @Qualifier("dataSource") DataSource source) {
        return new ServiceExecutionStockIntentSchemaVerifier(source);
    }

    @Bean
    ServiceTaskStockPreparationCoordinator beautyStockPreparation(
            AppointmentPersistence db,
            BeautyMappers.ServiceExecutionStockIntentMapper rows,
            BeautyMappers.ServiceSubmissionMapper submissions,
            ServiceExecutionStockIntentSchemaVerifier schema,
            ServiceExecutionAccess access,
            ObjectProvider<ServiceTaskExecutionServiceImpl> quantity,
            ObjectProvider<ServiceExecutionPreparationPort> bridge,
            @Qualifier("beautyReceptionClock") Clock clock) {
        return new ServiceTaskStockPreparationCoordinator(
                db, rows, submissions, schema, access, quantity, bridge, clock);
    }

    @Bean
    ServiceTaskPreparationService beautyTaskPreparationService(
            ServiceTaskExecutionService quantity, ServiceTaskStockPreparationCoordinator stock) {
        return new ServiceTaskPreparationServiceImpl(quantity, stock);
    }

    @Bean
    ServiceExecutionSourcePortImpl serviceExecutionSourcePort(ServiceTaskExecutionService service) {
        return new ServiceExecutionSourcePortImpl(service);
    }

    @Bean
    ServiceSourceObservation serviceSourceObservation(
            BeautyMappers.SourceObservationMapper rows,
            ObjectProvider<CheckoutPort> checkout,
            ObjectProvider<ManualServiceAdmissionPort> manual,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            ObjectMapper json,
            @Qualifier("beautyReceptionClock") Clock clock,
            ServiceExecutionFenceCoordinator executionFences) {
        return new ServiceSourceObservation(rows, checkout, manual, manager, json, clock, executionFences);
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
            @Qualifier("beautyReceptionClock") Clock clock,
            ServiceExecutionFenceCoordinator executionFences) {
        return new ServiceSourceBinding(
                db, query, observations, guards, orders, tasks, submissions, bindings, clock, executionFences);
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

    @Bean(name = "beautyServiceExecutionRecoveryJob")
    @Profile("api")
    @ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
    ServiceExecutionRecoveryJob beautyServiceExecutionRecoveryJob(ServiceTaskExecutionService service) {
        return new ServiceExecutionRecoveryJob(service);
    }

    @Bean(name = "beautyServiceExecutionRecoveryDeclaration")
    @Profile("api")
    @ConditionalOnProperty(name = "shop.job.enabled", havingValue = "true")
    JobDeclaration beautyServiceExecutionRecoveryDeclaration() {
        return new JobDeclaration("beauty-service-execution-recovery", "美业执行凭证恢复",
                "核对原数量授权，不重放员工操作", "beauty-core",
                "0 * * * * *", true, 120, 120, true, true);
    }
}
