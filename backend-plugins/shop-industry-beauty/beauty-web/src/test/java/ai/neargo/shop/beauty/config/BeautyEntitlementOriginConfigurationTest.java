package ai.neargo.shop.beauty.config;

import ai.neargo.shop.beauty.dto.CustomerIdentityVO;
import ai.neargo.shop.beauty.dto.WorkOrderDetailVO;
import ai.neargo.shop.beauty.dto.WorkOrderTaskVO;
import ai.neargo.shop.beauty.dto.WorkOrderVO;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.entitlement.ServiceWorkOrderOriginPort.Origin;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BeautyEntitlementOriginConfigurationTest {
    @Test
    void resolvesOnlyCurrentUnboundMemberTaskFromScopedWorkOrder() {
        var service = mock(WorkOrderService.class);
        var order = mock(WorkOrderVO.class);
        var task = mock(WorkOrderTaskVO.class);
        var detail = mock(WorkOrderDetailVO.class);
        when(service.getWorkOrder("E1", "S1", "A1", "WO1")).thenReturn(detail);
        when(detail.workOrder()).thenReturn(order);
        when(detail.customer()).thenReturn(new CustomerIdentityVO("USER", "U1"));
        when(detail.tasks()).thenReturn(List.of(task));
        when(order.workOrderNo()).thenReturn("WO1");
        when(order.status()).thenReturn("OPEN");
        when(order.scopeRevision()).thenReturn(2L);
        when(task.taskNo()).thenReturn("TASK1");
        when(task.status()).thenReturn("PENDING");
        when(task.revision()).thenReturn(3L);
        when(task.uom()).thenReturn("COUNT");
        when(task.skuNo()).thenReturn("SKU1");
        when(task.qty()).thenReturn(new BigDecimal("2.00"));
        when(task.doneQty()).thenReturn(BigDecimal.ZERO);
        when(task.voidedQty()).thenReturn(BigDecimal.ZERO);

        var beans = new StaticListableBeanFactory();
        beans.addBean("workOrderService", service);
        var origin = new BeautyEntitlementOriginConfiguration()
                .beautyEntitlementOrigin(beans.getBeanProvider(WorkOrderService.class));

        assertEquals(new Origin("WO1", "TASK1", 2, 3, "U1", "SKU1", 2),
                origin.resolve("E1", "S1", "A1", "WO1", "TASK1"));
        assertThrows(BizException.class, () -> origin.resolve("E1", "S1", "A1", "WO1", "TASK2"));

        when(task.bindingRevision()).thenReturn(1L);
        assertThrows(BizException.class, () -> origin.resolve("E1", "S1", "A1", "WO1", "TASK1"));
        when(task.bindingRevision()).thenReturn(0L);
        when(detail.customer()).thenReturn(new CustomerIdentityVO("GUEST", "G1"));
        assertThrows(BizException.class, () -> origin.resolve("E1", "S1", "A1", "WO1", "TASK1"));
    }
}
