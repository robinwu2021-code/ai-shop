package ai.neargo.shop.trade.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort.UseFact;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.AssetKind;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.HoldView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.trade.dto.ServiceOrderCreate;
import ai.neargo.shop.trade.dto.ServiceOrderIdentifiedCreate;
import ai.neargo.shop.trade.dto.ServiceOrderSnapshot;
import ai.neargo.shop.trade.dto.ServiceUseSubmit;
import java.time.LocalDate;
import java.util.List;

public interface EntitlementServiceOrderService {
    String operationNoForKey(EntitlementContext context, String entityNo, String key);
    ServiceOrderSnapshot create(EntitlementContext context, String entityNo, ServiceOrderCreate command, String key);
    ServiceOrderSnapshot createIdentified(EntitlementContext context, String entityNo,
                                          ServiceOrderIdentifiedCreate command, String key);
    ServiceOrderSnapshot requireMerchant(EntitlementContext context, String entityNo, String storeNo, String no);
    ServiceOrderSnapshot requireMerchant(EntitlementContext context, String entityNo, String no);
    List<ServiceOrderSnapshot> pending(String tenantNo, String userNo, int size);
    ServiceOrderSnapshot mine(String tenantNo, String userNo, String no);
    ServiceOrderSnapshot findOperation(String tenantNo, String entityNo, String operationNo);
    UseSourceFact source(EntitlementContext context, SourceRef source);
    ServiceOrderSnapshot bindPreview(EntitlementContext context, String entityNo, String storeNo, String no,
                                     AssetKind kind, PreviewView preview, LocalDate serviceDay);
    ServiceOrderSnapshot stage(EntitlementContext context, String entityNo, String storeNo, ServiceUseSubmit command);
    ServiceOrderSnapshot accept(EntitlementContext context, String entityNo, UseFact fact);
    ServiceOrderSnapshot cancel(EntitlementContext context, String entityNo, String storeNo, String no, String reason);
    void acknowledge(EntitlementContext context, String entityNo, HoldView result);
}
