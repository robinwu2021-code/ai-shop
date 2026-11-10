package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.mapper.WorkOrderWorkbenchMapper;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.service.WorkOrderWorkbenchService;
import ai.neargo.shop.beauty.service.impl.AppointmentPersistence;
import ai.neargo.shop.beauty.service.impl.WorkOrderWorkbenchServiceImpl;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Store queue has no writer, new migration, execution provider or financial dependency. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBeautyEnabled
public class BeautyWorkbenchConfiguration {
    @Bean
    WorkOrderWorkbenchService workOrderWorkbenchService(
            AppointmentAccess access, AppointmentPersistence db, WorkOrderWorkbenchMapper rows) {
        return new WorkOrderWorkbenchServiceImpl(access, db, rows);
    }
}
