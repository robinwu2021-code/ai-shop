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
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionService;
import ai.neargo.shop.pay.entitlement.service.EntitlementExceptionRecoveryService;
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class EntitlementExceptionAppServiceImpl implements EntitlementExceptionAppService {
    private final EntitlementExceptionService exceptions;
    private final EntitlementExceptionRecoveryService recovery;
    public EntitlementExceptionAppServiceImpl(EntitlementExceptionService exceptions,EntitlementExceptionRecoveryService recovery) {
        this.exceptions=exceptions; this.recovery=recovery;
    }
    @Override public PageData<ExceptionView> list(CatalogScope s,String kind,String status,long page,long size) {
        return exceptions.list(s,kind,status,page,size);
    }
    @Override public ExceptionView get(CatalogScope s,String no) { return exceptions.get(s,no); }
    @Override public ExceptionView querySource(CatalogScope s,String no,RecoveryCommand c,String key) {
        return recovery.querySource(s,no,c,key);
    }
    @Override public ExceptionView retryEvent(CatalogScope s,String no,RecoveryCommand c,String key) {
        return recovery.retryEvent(s,no,c,key);
    }
    @Override public ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage deadLetters(
            OperatorEntityScope scope,String cursor,int limit) {
        return recovery.deadLetters(scope,cursor,limit);
    }
    @Override public ExceptionView retryGrant(CatalogScope s,String no,RecoveryCommand c,String key) {
        return recovery.retryGrant(s,no,c,key);
    }
    @Override public PageData<ActionView> actions(CatalogScope s,String no,long page,long size) {
        return exceptions.actions(s,no,page,size);
    }
    @Override public PageData<ExceptionView> listAcross(OperatorEntityScope scope, String kind, String status,
                                                       long page, long size) {
        return exceptions.listAcross(scope, kind, status, page, size);
    }
}
