package ai.neargo.shop.spi.entitlement;

import java.util.List;

/** Only the core audience owner can freeze recipients; public batch requests accept a reference, never user IDs. */
public interface EntitlementIssueAudiencePort {
    AudienceSnapshot resolve(EntitlementContext context, String entityNo, String audienceFactRef);
    record Recipient(String userNo, boolean eligible, String reasonCode) { }
    record AudienceSnapshot(String tenantNo, String entityNo, String factRef, List<Recipient> recipients) {
        public AudienceSnapshot { recipients = List.copyOf(recipients); }
    }
}
