package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.mapper.TaskWorkflowMapper;
import ai.neargo.shop.beauty.policy.ServiceExecutionAccess;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.ServiceTaskWorkflowServiceImpl;
import ai.neargo.shop.beauty.service.impl.TaskAcceptanceCoordinator;
import ai.neargo.shop.beauty.service.impl.TaskStartCoordinator;
import ai.neargo.shop.beauty.service.impl.TaskStartOriginalReader;
import ai.neargo.shop.spi.resource.ResourceFactsPort;
import ai.neargo.shop.spi.resource.ResourceOccupationLifecyclePort;
import ai.neargo.shop.spi.trade.ServiceExecutionCompletionPort;
import ai.neargo.shop.spi.trade.ServiceExecutionEvidencePort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class TaskWorkflowConfiguration {
    @Bean
    ServiceTaskWorkflowServiceImpl serviceTaskWorkflowServiceImpl(
            AppointmentPersistence db,
            BeautyMappers.WorkOrderMapper orders,
            BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceStepMapper steps,
            BeautyMappers.ServiceParticipationMapper people,
            BeautyMappers.ServiceSubmissionMapper submissions,
            BeautyMappers.ServiceExecutionFenceMapper fences,
            TaskWorkflowMapper flows,
            ServiceExecutionAccess access,
            TaskStartOriginalReader originals,
            ObjectProvider<TaskStartCoordinator> starts,
            ObjectProvider<TaskAcceptanceCoordinator> acceptance,
            ObjectProvider<ResourceOccupationLifecyclePort> resources,
            ObjectProvider<ServiceExecutionCompletionPort> completion,
            ObjectProvider<ServiceExecutionEvidencePort> evidence,
            ObjectProvider<ResourceFactsPort> facts,
            ObjectProvider<MerchantOperatorAccessPort> operators,
            @Qualifier("beautyReceptionClock") Clock clock,
            @Qualifier("dataSource") DataSource source) {
        var service =
                new ServiceTaskWorkflowServiceImpl(
                        db,
                        orders,
                        tasks,
                        steps,
                        people,
                        submissions,
                        fences,
                        flows,
                        access,
                        originals,
                        starts,
                        acceptance,
                        resources,
                        completion,
                        facts,
                        operators,
                        clock);
        service.setWorkflowSchema(source);
        service.setCoreEvidence(evidence);
        return service;
    }
}
