package ai.neargo.shop.pay.entitlement.service;

import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeRequest;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeView;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.ResolvedCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;

public interface EntitlementCodeService {
    CodeView issue(EntitlementContext context, String userNo, String instanceNo,
                   CodeRequest request, String requestKey);
    ResolvedCode resolve(EntitlementContext context, String entityNo, String storeNo, String token, String operationNo);
    int invalidate(EntitlementContext context, String userNo, String instanceNo);
}
