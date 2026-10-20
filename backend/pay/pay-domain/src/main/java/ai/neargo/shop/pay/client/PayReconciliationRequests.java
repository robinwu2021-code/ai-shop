package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.ReconciliationViews.Check;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import java.time.LocalDate;

/** Only authenticated internal callers construct the trusted scope; public bodies never supply it. */
public final class PayReconciliationRequests {
    private PayReconciliationRequests() { }
    public record Start(CatalogScope scope,Check command,String requestKey) {
        public Start {
            ContractChecks.present(scope); ContractChecks.present(command); ContractChecks.id(requestKey);
        }
    }
    public record ListRuns(CatalogScope scope,LocalDate bizDate,long page,long size) {
        public ListRuns { ContractChecks.present(scope); }
    }
    public record GetRun(CatalogScope scope,String reconNo) {
        public GetRun { ContractChecks.present(scope); ContractChecks.id(reconNo); }
    }
    public record Differences(CatalogScope scope,String reconNo,long page,long size) {
        public Differences { ContractChecks.present(scope); ContractChecks.id(reconNo); }
    }
}
