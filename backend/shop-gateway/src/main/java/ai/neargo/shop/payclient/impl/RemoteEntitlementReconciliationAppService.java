package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import java.time.LocalDate;
import ai.neargo.shop.pay.client.PayInternalApi;
import ai.neargo.shop.pay.client.PayReconciliationRequests;
import ai.neargo.shop.payclient.EntitlementReconciliationAppService;
import ai.neargo.shop.payclient.RemoteEntitlementCalls;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Remote calls preserve the original key; transport failure never invents another run. */
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="standalone")
public class RemoteEntitlementReconciliationAppService implements EntitlementReconciliationAppService {
    private final PayInternalApi pay;
    public RemoteEntitlementReconciliationAppService(PayInternalApi pay) { this.pay=pay; }
    @Override public RunView check(CatalogScope s,Check c,String key) {
        return RemoteEntitlementCalls.call(() -> pay.reconciliationCheck(new PayReconciliationRequests.Start(s,c,key)));
    }
    @Override public RunView get(CatalogScope s,String no) {
        return RemoteEntitlementCalls.call(() -> pay.reconciliationGet(new PayReconciliationRequests.GetRun(s,no)));
    }
    @Override public PageData<RunView> list(CatalogScope s,LocalDate date,long page,long size) {
        return RemoteEntitlementCalls.call(() -> pay.reconciliationsList(
                new PayReconciliationRequests.ListRuns(s,date,page,size)));
    }
    @Override public PageData<DiffView> differences(CatalogScope s,String no,long page,long size) {
        return RemoteEntitlementCalls.call(() -> pay.reconciliationDiffs(
                new PayReconciliationRequests.Differences(s,no,page,size)));
    }
}
