package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeRequest;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeView;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.ResolvedCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.payclient.EntitlementCodeAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.entitlement.service.EntitlementCodeService;

@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "embedded", matchIfMissing = true)
public class EntitlementCodeAppServiceImpl implements EntitlementCodeAppService {
    private final EntitlementCodeService owner;
    public EntitlementCodeAppServiceImpl(EntitlementCodeService owner) { this.owner = owner; }
    @Override public CodeView issue(EntitlementContext c, String user, String instance,
            CodeRequest request, String key) {
        return owner.issue(c, user, instance, request, key);
    }
    @Override public ResolvedCode resolve(EntitlementContext c, String entity, String store,
            String token, String operation) {
        return owner.resolve(c, entity, store, token, operation);
    }
    @Override public int invalidate(EntitlementContext c, String user, String instance) {
        return owner.invalidate(c, user, instance);
    }
}
