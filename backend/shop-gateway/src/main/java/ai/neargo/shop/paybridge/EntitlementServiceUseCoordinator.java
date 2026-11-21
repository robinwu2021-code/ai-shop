package ai.neargo.shop.paybridge;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import java.time.LocalDate;

public interface EntitlementServiceUseCoordinator {
    default PreviewView preview(EntitlementContext context, String entityNo, String storeNo, String serviceOrderNo,
                                String instanceNo) {
        return preview(context, entityNo, storeNo, serviceOrderNo, instanceNo, null);
    }
    PreviewView preview(EntitlementContext context, String entityNo, String storeNo, String serviceOrderNo,
                        String instanceNo, LocalDate requestedDay);
    HoldView submit(EntitlementContext context, String entityNo, String storeNo, ServiceUseSubmit command);
    HoldView query(EntitlementContext context, String entityNo, String operationNo);
    HoldView recover(EntitlementContext context, String entityNo, String operationNo);
    HoldView cancel(EntitlementContext context, String entityNo, String storeNo, String no, String reason);
}
