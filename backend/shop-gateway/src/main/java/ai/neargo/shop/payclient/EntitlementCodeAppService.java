package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeRequest;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeView;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.ResolvedCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;

public interface EntitlementCodeAppService {
    CodeView issue(EntitlementContext c, String user, String instance, CodeRequest request, String key);
    ResolvedCode resolve(EntitlementContext c, String entity, String store, String token, String operation);
    int invalidate(EntitlementContext c, String user, String instance);
}
