package ai.neargo.shop.payclient;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import java.time.LocalDate;

/** Deployment-neutral access to pay-owned snapshots; no financial state is adjusted. */
public interface EntitlementReconciliationAppService {
    RunView check(CatalogScope scope,Check command,String key);
    RunView get(CatalogScope scope,String no);
    PageData<RunView> list(CatalogScope scope,LocalDate date,long page,long size);
    PageData<DiffView> differences(CatalogScope scope,String no,long page,long size);
}
