package ai.neargo.shop.payclient.impl;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import java.time.LocalDate;
import ai.neargo.shop.pay.entitlement.service.EntitlementReconciliationService;
import ai.neargo.shop.payclient.EntitlementReconciliationAppService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/** Embedded mode delegates directly to the same pay transaction boundary used by standalone. */
@Service
@ConditionalOnProperty(name="shop.pay.deployment",havingValue="embedded",matchIfMissing=true)
public class EntitlementReconciliationAppServiceImpl implements EntitlementReconciliationAppService {
    private final EntitlementReconciliationService pay;
    public EntitlementReconciliationAppServiceImpl(EntitlementReconciliationService pay) { this.pay=pay; }
    @Override public RunView check(CatalogScope s,Check c,String key) { return pay.check(s,c,key); }
    @Override public RunView get(CatalogScope s,String no) { return pay.get(s,no); }
    @Override public PageData<RunView> list(CatalogScope s,LocalDate date,long page,long size) {
        return pay.list(s,date,page,size);
    }
    @Override public PageData<DiffView> differences(CatalogScope s,String no,long page,long size) {
        return pay.differences(s,no,page,size);
    }
}
