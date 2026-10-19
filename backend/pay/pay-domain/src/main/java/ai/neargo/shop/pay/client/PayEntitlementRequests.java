package ai.neargo.shop.pay.client;

import ai.neargo.shop.pay.entitlement.dto.CatalogScope;
import ai.neargo.shop.pay.entitlement.dto.IssuePolicyViews.SavePolicy;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.EntitlementCatalogPort.ReferenceQuery;
import ai.neargo.shop.spi.entitlement.EntitlementGrantPort;
import ai.neargo.shop.spi.entitlement.ContractChecks;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort;
import ai.neargo.shop.spi.entitlement.EntitlementQueryPort;
import ai.neargo.shop.spi.entitlement.EntitlementUseFactsPort;
import ai.neargo.shop.pay.entitlement.dto.IssueBatchViews;
import ai.neargo.shop.pay.entitlement.dto.CodeViews;
import java.util.Objects;

/** Internal transport only; public controllers construct trusted scope from the authenticated session. */
public final class PayEntitlementRequests {
    private PayEntitlementRequests() { }
    public record ListTemplates(CatalogScope scope, String kind, String status, long page, long size) {
        public ListTemplates {
            Objects.requireNonNull(scope);
        }
    }
    public record GetTemplate(CatalogScope scope, String templateNo) {
        public GetTemplate {
            Objects.requireNonNull(scope);
            ContractChecks.id(templateNo);
        }
    }
    public record CreateTemplate(CatalogScope scope, EntitlementDefinition definition, String requestKey) {
        public CreateTemplate {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(definition);
            ContractChecks.id(requestKey);
        }
    }
    public record DraftTemplate(CatalogScope scope, String templateNo, long expectedRevision,
            EntitlementDefinition definition, String requestKey) {
        public DraftTemplate {
            Objects.requireNonNull(scope);
            ContractChecks.id(templateNo);
            Objects.requireNonNull(definition);
            ContractChecks.id(requestKey);
        }
    }
    public record PublishTemplate(CatalogScope scope, String templateNo, long expectedRevision, String requestKey) {
        public PublishTemplate {
            Objects.requireNonNull(scope);
            ContractChecks.id(templateNo);
            ContractChecks.id(requestKey);
        }
    }
    public record StatusTemplate(CatalogScope scope, String templateNo, long expectedRevision, String status,
            String requestKey) {
        public StatusTemplate {
            Objects.requireNonNull(scope);
            ContractChecks.id(templateNo);
            ContractChecks.id(requestKey);
        }
    }
    public record ConfigureIssue(CatalogScope scope, String templateNo, SavePolicy command, String requestKey) {
        public ConfigureIssue {
            Objects.requireNonNull(scope);
            ContractChecks.id(templateNo);
            Objects.requireNonNull(command);
            ContractChecks.id(requestKey);
        }
    }
    public record CatalogVersion(EntitlementContext context, String entityNo, String versionNo) {
        public CatalogVersion {
            Objects.requireNonNull(context);
            ContractChecks.id(entityNo);
            ContractChecks.id(versionNo);
        }
    }
    public record ValidateCatalogReference(EntitlementContext context, ReferenceQuery query) {
        public ValidateCatalogReference {
            Objects.requireNonNull(context);
            Objects.requireNonNull(query);
        }
    }
    public record GrantPrepare(EntitlementContext context, EntitlementGrantPort.GrantCommand command) {
        public GrantPrepare {
            Objects.requireNonNull(context);
            Objects.requireNonNull(command);
        }
    }

    public record GrantCommit(EntitlementContext context, EntitlementGrantPort.GrantDecision command) {
        public GrantCommit {
            Objects.requireNonNull(context);
            Objects.requireNonNull(command);
        }
    }

    public record GrantAbort(EntitlementContext context, EntitlementGrantPort.AbortGrant command) {
        public GrantAbort {
            Objects.requireNonNull(context);
            Objects.requireNonNull(command);
        }
    }

    public record GrantQuery(EntitlementContext context, String entityNo, String grantNo) {
        public GrantQuery {
            Objects.requireNonNull(context);
            ContractChecks.id(entityNo);
            ContractChecks.id(grantNo);
        }
    }

    public record UseQuote(EntitlementContext context, EntitlementUsePort.QuoteCommand command) {
        public UseQuote {
            Objects.requireNonNull(context);
            Objects.requireNonNull(command);
        }
    }

    public record UsePreview(EntitlementContext context, EntitlementUsePort.PreviewQuery query) {
        public UsePreview {
            Objects.requireNonNull(context);
            Objects.requireNonNull(query);
        }
    }

    public record UsePrepare(EntitlementContext context, EntitlementUsePort.UseCommand command) {
        public UsePrepare {
            Objects.requireNonNull(context);
            Objects.requireNonNull(command);
        }
    }

    public record UseCommit(EntitlementContext context, EntitlementUsePort.UseDecision command) {
        public UseCommit {
            Objects.requireNonNull(context);
            Objects.requireNonNull(command);
        }
    }

    public record UseRelease(EntitlementContext context, EntitlementUsePort.CancelUse command) {
        public UseRelease {
            Objects.requireNonNull(context);
            Objects.requireNonNull(command);
        }
    }

    public record UseReverse(EntitlementContext context, EntitlementUsePort.OrderBenefitReverse command) {
        public UseReverse {
            Objects.requireNonNull(context);
            Objects.requireNonNull(command);
        }
    }

    public record ReverseQuery(EntitlementContext context, String entityNo, String reverseNo) {
        public ReverseQuery {
            Objects.requireNonNull(context);
            ContractChecks.id(entityNo);
            ContractChecks.id(reverseNo);
        }
    }

    public record UseQuery(EntitlementContext context, String entityNo, String operationNo) {
        public UseQuery {
            Objects.requireNonNull(context);
            ContractChecks.id(entityNo);
            ContractChecks.id(operationNo);
        }
    }

    public record Mine(EntitlementContext context, EntitlementQueryPort.AssetQuery query) {
        public Mine {
            Objects.requireNonNull(context);
            Objects.requireNonNull(query);
        }
    }

    public record Detail(EntitlementContext context, EntitlementQueryPort.DetailQuery query) {
        public Detail {
            Objects.requireNonNull(context);
            Objects.requireNonNull(query);
        }
    }

    public record Usage(EntitlementContext context, EntitlementQueryPort.UsageQuery query) {
        public Usage {
            Objects.requireNonNull(context);
            Objects.requireNonNull(query);
        }
    }

    public record Allowances(EntitlementContext context, EntitlementQueryPort.AllowanceQuery query) {
        public Allowances {
            Objects.requireNonNull(context);
            Objects.requireNonNull(query);
        }
    }

    public record Activation(EntitlementContext context, EntitlementQueryPort.DetailQuery query) {
        public Activation {
            Objects.requireNonNull(context);
            Objects.requireNonNull(query);
        }
    }

    public record UseFacts(EntitlementContext context, String entityNo, String operationNo) {
        public UseFacts {
            Objects.requireNonNull(context);
            ContractChecks.id(entityNo);
            ContractChecks.id(operationNo);
        }
    }

    public record PaymentAttempts(EntitlementContext context, String orderNo) {
        public PaymentAttempts {
            Objects.requireNonNull(context);
            ContractChecks.id(orderNo);
        }
    }

    public record BatchCreate(CatalogScope scope, IssueBatchViews.CreateBatch command, String requestKey) {
        public BatchCreate {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(command);
            ContractChecks.id(requestKey);
        }
    }

    public record BatchGet(CatalogScope scope, String batchNo) {
        public BatchGet {
            Objects.requireNonNull(scope);
            ContractChecks.id(batchNo);
        }
    }

    public record BatchResume(CatalogScope scope, String batchNo, int limit) {
        public BatchResume {
            Objects.requireNonNull(scope);
            ContractChecks.id(batchNo);
            ContractChecks.require(limit >= 1 && limit <= 100);
        }
    }

    public record CodeIssue(EntitlementContext context, String userNo,
            String instanceNo, CodeViews.CodeRequest request, String requestKey) {
        public CodeIssue {
            Objects.requireNonNull(context);
            ContractChecks.id(userNo);
            ContractChecks.id(instanceNo);
            Objects.requireNonNull(request);
            ContractChecks.id(requestKey);
        }
    }

    public record CodeResolve(EntitlementContext context, String entityNo,
            String storeNo, String token, String operationNo) {
        public CodeResolve {
            Objects.requireNonNull(context);
            ContractChecks.id(entityNo);
            ContractChecks.id(storeNo);
            Objects.requireNonNull(token);
            ContractChecks.id(operationNo);
        }
        @Override public String toString() { return "CodeResolve[token=REDACTED]"; }
    }

    public record CodeInvalidate(EntitlementContext context, String userNo, String instanceNo) {
        public CodeInvalidate {
            Objects.requireNonNull(context);
            ContractChecks.id(userNo);
            ContractChecks.id(instanceNo);
        }
    }

}
