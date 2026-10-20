package ai.neargo.shop.pay.client;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.OperatorEntityScope;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.pay.entitlement.dto.ExceptionViews.RecoveryCommand;
public final class PayExceptionRequests {
    private PayExceptionRequests() { }
    public record DeadLetters(OperatorEntityScope scope,String cursor,int limit) {
        public DeadLetters { ContractChecks.present(scope); ContractChecks.require(limit>=1 && limit<=100);
            ContractChecks.require(cursor==null || (!cursor.isBlank() && cursor.length()<=512)); }
    }
    public record ListExceptions(CatalogScope scope,String kind,String status,long page,long size) {
        public ListExceptions { ContractChecks.present(scope); }
    }
    public record GetException(CatalogScope scope,String exceptionNo) {
        public GetException { ContractChecks.present(scope); ContractChecks.id(exceptionNo); }
    }
    public record RetryGrant(CatalogScope scope,String exceptionNo,RecoveryCommand command,String requestKey) {
        public RetryGrant {
            ContractChecks.present(scope); ContractChecks.id(exceptionNo);
            ContractChecks.present(command); ContractChecks.id(requestKey);
        }
    }
    public record Actions(CatalogScope scope,String exceptionNo,long page,long size) {
        public Actions { ContractChecks.present(scope); ContractChecks.id(exceptionNo); }
    }
    public record ListAcross(OperatorEntityScope scope, String kind, String status, long page, long size) {
        public ListAcross {
            ContractChecks.present(scope); ContractChecks.require(page >= 1 && page <= 1000000 && size >= 1 && size <= 100);
        }
    }
}
