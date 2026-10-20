package ai.neargo.shop.payclient.impl;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ActionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
import ai.neargo.shop.payclient.EntitlementExceptionAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayExceptionRequests;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteEntitlementExceptionAppService implements EntitlementExceptionAppService {
    private final PayInternalApi pay;
    public RemoteEntitlementExceptionAppService(PayInternalApi pay) { this.pay=pay; }
    @Override public PageData<ExceptionView> list(CatalogScope s,String kind,String status,long page,long size) {
        return RemoteEntitlementCalls.call(() -> pay.exceptionsList(new PayExceptionRequests.ListExceptions(s,kind,status,page,size)));
    }
    @Override public ExceptionView get(CatalogScope s,String no) {
        return RemoteEntitlementCalls.call(() -> pay.exceptionGet(new PayExceptionRequests.GetException(s,no)));
    }
    @Override public ExceptionView querySource(CatalogScope s,String no,RecoveryCommand c,String key) {
        return RemoteEntitlementCalls.call(() -> pay.exceptionSource(new PayExceptionRequests.RetryGrant(s,no,c,key)));
    }
    @Override public ExceptionView retryEvent(CatalogScope s,String no,RecoveryCommand c,String key) {
        return RemoteEntitlementCalls.call(() -> pay.exceptionRetryEvent(new PayExceptionRequests.RetryGrant(s,no,c,key)));
    }
    @Override public ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage deadLetters(
            OperatorEntityScope scope,String cursor,int limit) {
        return RemoteEntitlementCalls.call(() -> pay.exceptionDeadLetters(new PayExceptionRequests.DeadLetters(scope,cursor,limit)));
    }
    @Override public ExceptionView retryGrant(CatalogScope s,String no,RecoveryCommand c,String key) {
        return RemoteEntitlementCalls.call(() -> pay.exceptionRetry(new PayExceptionRequests.RetryGrant(s,no,c,key)));
    }
    @Override public PageData<ActionView> actions(CatalogScope s,String no,long page,long size) {
        return RemoteEntitlementCalls.call(() -> pay.exceptionActions(new PayExceptionRequests.Actions(s,no,page,size)));
    }
    @Override public PageData<ExceptionView> listAcross(OperatorEntityScope scope, String kind, String status,
                                                       long page, long size) {
        return RemoteEntitlementCalls.call(() -> pay.exceptionsListAcross(
                new PayExceptionRequests.ListAcross(scope, kind, status, page, size)));
    }
}
