package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.service.WorkOrderBillService;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import ai.neargo.shop.beauty.service.impl.WorkOrderSettlementServiceImpl;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautySettlementConfiguration {
    @Bean
    WorkOrderSettlementService workOrderSettlementService(WorkOrderBillService bills, ObjectMapper json) {
        return new WorkOrderSettlementServiceImpl(bills, json);
    }
}
