package ai.neargo.shop.pay.entitlement.service;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ActionView;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
public interface EntitlementExceptionService {
    ExceptionView captureGrantFailure(EntitlementContext context,String entityNo,String grantNo);
    ExceptionView get(CatalogScope scope,String exceptionNo);
    PageData<ExceptionView> list(CatalogScope scope,String kind,String status,long page,long size);
    PageData<ActionView> actions(CatalogScope scope,String exceptionNo,long page,long size);
    PageData<ExceptionView> listAcross(OperatorEntityScope scope, String kind, String status, long page, long size);
}
