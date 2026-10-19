package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.ReferenceQuery;
import java.util.Objects;

/** Internal transport only; public controllers construct trusted scope from the authenticated session. */
public final class PayEntitlementRequests {
    private PayEntitlementRequests() { }
    public record ListTemplates(CatalogScope scope, String kind, String status, long page, long size) {
        public ListTemplates { Objects.requireNonNull(scope); }
    }
    public record GetTemplate(CatalogScope scope, String templateNo) {
        public GetTemplate { Objects.requireNonNull(scope); }
    }
    public record CreateTemplate(CatalogScope scope, EntitlementDefinition definition, String requestKey) {
        public CreateTemplate { Objects.requireNonNull(scope); }
    }
    public record DraftTemplate(CatalogScope scope, String templateNo, long expectedRevision,
            EntitlementDefinition definition, String requestKey) {
        public DraftTemplate { Objects.requireNonNull(scope); }
    }
    public record PublishTemplate(CatalogScope scope, String templateNo, long expectedRevision, String requestKey) {
        public PublishTemplate { Objects.requireNonNull(scope); }
    }
    public record StatusTemplate(CatalogScope scope, String templateNo, long expectedRevision, String status,
            String requestKey) {
        public StatusTemplate { Objects.requireNonNull(scope); }
    }
    public record ConfigureIssue(CatalogScope scope, String templateNo, SavePolicy command, String requestKey) {
        public ConfigureIssue { Objects.requireNonNull(scope); }
    }
    public record CatalogVersion(EntitlementContext context, String entityNo, String versionNo) {
        public CatalogVersion { Objects.requireNonNull(context); }
    }
    public record ValidateCatalogReference(EntitlementContext context, ReferenceQuery query) {
        public ValidateCatalogReference { Objects.requireNonNull(context); }
    }
}
