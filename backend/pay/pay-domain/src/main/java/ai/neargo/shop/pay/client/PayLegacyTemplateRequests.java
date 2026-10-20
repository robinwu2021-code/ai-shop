package ai.neargo.shop.pay.client;
import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.migration.dto.LegacyCouponTemplateViews.*;
import ai.neargo.shop.spi.entitlement.ContractChecks;
public final class PayLegacyTemplateRequests {
    private PayLegacyTemplateRequests() { }
    public record Archive(CatalogScope scope,Reference reference,boolean archived,String requestKey) {
        public Archive {ContractChecks.present(scope);ContractChecks.present(reference);ContractChecks.id(requestKey);}
    }
    public record Get(CatalogScope scope,Reference reference) {
        public Get {ContractChecks.present(scope);ContractChecks.present(reference);}
    }
    public record Status(CatalogScope scope,Reference reference,String status,String requestKey) {
        public Status {ContractChecks.present(scope);ContractChecks.present(reference);ContractChecks.present(status);ContractChecks.id(requestKey);}
    }
    public record Save(CatalogScope scope,Reference reference,DefinitionChange change,String requestKey) {
        public Save {ContractChecks.present(scope);ContractChecks.present(reference);ContractChecks.present(change);ContractChecks.id(requestKey);}
    }
}
