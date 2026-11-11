package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.ServiceSubmissionQuery;
import ai.neargo.shop.beauty.service.impl.WorkOrderEntitlementService;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderUseFactsPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautyEntitlementUseConfiguration {
    @Bean
    @ConditionalOnBean(ServiceWorkOrderUseFactsPort.class)
    WorkOrderEntitlementService workOrderEntitlementService(AppointmentPersistence db, AppointmentAccess access,
            ServiceSubmissionQuery query, BeautyMappers.WorkOrderMapper orders, BeautyMappers.ServiceTaskMapper tasks,
            BeautyMappers.ServiceSubmissionMapper submissions, BeautyMappers.SourceAllocationGuardMapper guards,
            BeautyMappers.TradeBindingMapper bindings, ServiceWorkOrderUseFactsPort facts,
            MerchantOperatorAccessPort operators) {
        return new WorkOrderEntitlementService(db, access, query, orders, tasks, submissions, guards, bindings,
                facts, operators);
    }
}
