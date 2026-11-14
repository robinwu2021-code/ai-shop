package ai.neargo.shop.pay.entitlement.dto;

import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.AudienceSnapshot;
import ai.neargo.shop.spi.entitlement.EntitlementIssueAudiencePort.Recipient;
import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import java.time.Instant;
import java.util.List;

/** Batch commands contain an owner-held audience reference, not client supplied recipient identities. */
public final class IssueBatchViews {
    private IssueBatchViews() { }
    public record CreateBatch(String templateVersionNo, String audienceFactRef) { }
    public record Item(String userNo, String grantNo, String status, String reasonCode) { }
    public record BatchView(String batchNo, String status, int plannedCount, int issuedCount,
                            int skippedCount, int failedCount, int pendingCount, List<Item> results, String documentNo) {
        public BatchView(String batchNo, String status, int plannedCount, int issuedCount, int skippedCount,
                         int failedCount, int pendingCount, List<Item> results) {
            this(batchNo, status, plannedCount, issuedCount, skippedCount, failedCount, pendingCount, results, null);
        }
    }
    public record BatchSnapshot(AudienceSnapshot audience, String templateVersionNo,
                                EntitlementDefinition definition, String benefitHash, Instant createdAt) { }
}
