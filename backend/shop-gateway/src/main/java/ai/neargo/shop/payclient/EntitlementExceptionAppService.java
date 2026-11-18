package ai.neargo.shop.payclient;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ActionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
public interface EntitlementExceptionAppService {
    PageData<ExceptionView> list(CatalogScope scope,String kind,String status,long page,long size);
    ExceptionView get(CatalogScope scope,String no);
    ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage deadLetters(
            ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope scope,String cursor,int limit);
    ExceptionView querySource(CatalogScope scope,String no,RecoveryCommand command,String key);
    ExceptionView retryEvent(CatalogScope scope,String no,RecoveryCommand command,String key);
    ExceptionView retryGrant(CatalogScope scope,String no,RecoveryCommand command,String key);
    PageData<ActionView> actions(CatalogScope scope,String exceptionNo,long page,long size);
    PageData<ExceptionView> listAcross(OperatorEntityScope scope, String kind, String status, long page, long size);
}
