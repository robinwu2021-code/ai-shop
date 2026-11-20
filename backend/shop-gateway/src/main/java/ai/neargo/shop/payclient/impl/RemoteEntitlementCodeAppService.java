package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeRequest;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.CodeView;
import ai.neargo.shop.pay.entitlement.dto.CodeViews.ResolvedCode;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.payclient.EntitlementCodeAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayEntitlementRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

@Service
@ConditionalOnProperty(name = "shop.pay.deployment", havingValue = "standalone")
public class RemoteEntitlementCodeAppService implements EntitlementCodeAppService {
    private final PayInternalApi pay;
    public RemoteEntitlementCodeAppService(PayInternalApi pay) { this.pay = pay; }
    @Override public CodeView issue(EntitlementContext c, String user, String instance,
            CodeRequest request, String key) {
        return RemoteEntitlementCalls.call(() ->
                pay.entCodeIssue(new PayEntitlementRequests.CodeIssue(c, user, instance, request, key)));
    }
    @Override public ResolvedCode resolve(EntitlementContext c, String entity, String store,
            String token, String operation) {
        return RemoteEntitlementCalls.call(() ->
                pay.entCodeResolve(new PayEntitlementRequests.CodeResolve(c, entity, store, token, operation)));
    }
    @Override public int invalidate(EntitlementContext c, String user, String instance) {
        var result = RemoteEntitlementCalls.call(() -> pay.entCodeInvalidate(
                new PayEntitlementRequests.CodeInvalidate(c, user, instance)));
        if (!user.equals(result.userNo()) || !instance.equals(result.instanceNo())) {
            throw BizException.of(ErrorCode.CONFLICT);
        }
        return result.revokedCount();
    }
}
