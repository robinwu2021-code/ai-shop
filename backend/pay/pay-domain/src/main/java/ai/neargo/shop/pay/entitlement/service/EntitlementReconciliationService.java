package ai.neargo.shop.pay.entitlement.service;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.DiffView;
import ai.neargo.shop.common.PageData;
import java.time.LocalDate;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.CheckRights;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.RunView;
public interface EntitlementReconciliationService {
    RunView check(CatalogScope scope,Check command,String key);
    PageData<RunView> list(CatalogScope scope,LocalDate date,long page,long size);
    PageData<DiffView> differences(CatalogScope scope,String reconNo,long page,long size);
    RunView checkRights(CatalogScope scope,CheckRights command,String key);
    RunView get(CatalogScope scope,String reconNo);
}
