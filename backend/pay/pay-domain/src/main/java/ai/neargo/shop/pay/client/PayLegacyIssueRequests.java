package ai.neargo.shop.pay.client;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponIssueViews.Command;
import ai.neargo.shop.spi.entitlement.ContractChecks;
public final class PayLegacyIssueRequests {
    private PayLegacyIssueRequests() { }
    public record Issue(CatalogScope scope,Command command,String requestKey) {
        public Issue {ContractChecks.present(scope);ContractChecks.present(command);ContractChecks.id(requestKey);}
    }
    public record Get(CatalogScope scope,String issueNo) {public Get {ContractChecks.present(scope);ContractChecks.id(issueNo);}}
    public record Resume(CatalogScope scope,String issueNo,int limit) {
        public Resume {ContractChecks.present(scope);ContractChecks.id(issueNo);ContractChecks.require(limit>=1 && limit<=1000);}
    }
    public record History(CatalogScope scope,String sourceSystem,String sourceTemplateNo) {
        public History {ContractChecks.present(scope);ContractChecks.require("PMT".equals(sourceSystem) || "MKT_LEGACY".equals(sourceSystem));ContractChecks.optionalId(sourceTemplateNo);}
    }
}
