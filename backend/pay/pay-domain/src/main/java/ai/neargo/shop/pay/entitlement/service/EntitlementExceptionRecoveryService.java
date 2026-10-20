package ai.neargo.shop.pay.entitlement.service;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.ExceptionView;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
public interface EntitlementExceptionRecoveryService {
    ai.neargo.shop.pay.entitlement.dto.ExceptionViews.DeadLetterPage deadLetters(
            ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope scope,String cursor,int limit);
    ExceptionView querySource(CatalogScope scope,String exceptionNo,RecoveryCommand command,String requestKey);
    ExceptionView retryEvent(CatalogScope scope,String exceptionNo,RecoveryCommand command,String requestKey);
    ExceptionView retryGrant(CatalogScope scope,String exceptionNo,RecoveryCommand command,String requestKey);
}
