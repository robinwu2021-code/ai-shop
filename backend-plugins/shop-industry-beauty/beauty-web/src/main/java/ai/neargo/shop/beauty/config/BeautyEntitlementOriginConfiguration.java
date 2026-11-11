package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort.Origin;
import java.math.BigDecimal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class BeautyEntitlementOriginConfiguration {
    @Bean
    ServiceWorkOrderOriginPort beautyEntitlementOrigin(ObjectProvider<WorkOrderService> orders) {
        return (entity, store, actor, workOrder, taskNo) -> {
            var owner = orders.getIfAvailable();
            if (owner == null) throw BizException.of(ErrorCode.BEAUTY_DEPENDENCY_UNAVAILABLE);
            var detail = owner.getWorkOrder(entity, store, actor, workOrder);
            var task = detail.tasks().stream().filter(row -> taskNo.equals(row.taskNo())).findFirst()
                    .orElseThrow(() -> BizException.of(ErrorCode.NOT_FOUND));
            if (!workOrder.equals(detail.workOrder().workOrderNo())
                    || !"OPEN".equals(detail.workOrder().status()) || !"USER".equals(detail.customer().kind())
                    || !"PENDING".equals(task.status()) || task.bindingRevision() != 0
                    || task.revision() == null || !"COUNT".equals(task.uom())
                    || task.doneQty().signum() != 0 || task.voidedQty().signum() != 0
                    || task.qty().stripTrailingZeros().scale() > 0
                    || task.qty().compareTo(BigDecimal.ONE) < 0
                    || task.qty().compareTo(BigDecimal.valueOf(100)) > 0) {
                throw BizException.of(ErrorCode.ENTITLEMENT_FEATURE_UNAVAILABLE);
            }
            return new Origin(workOrder, taskNo, detail.workOrder().scopeRevision(), task.revision(),
                    detail.customer().reference(), task.skuNo(), task.qty().intValueExact());
        };
    }
}
