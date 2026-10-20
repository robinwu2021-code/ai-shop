package ai.neargo.shop.spi.promotion;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;

/** Shared, service-authenticated internal wire contract. Never expose these DTOs as public user commands. */
public final class ActivityParticipationInternal {
    private ActivityParticipationInternal() { }
    public static final String BASE = "/internal/core/promotion/participation";
    public record ReserveRequest(EntitlementContext context, ActivityParticipationPort.ReserveActivity command) { }
    public record ConfirmRequest(EntitlementContext context, ActivityParticipationPort.ConfirmParticipation command) { }
    public record ReleaseRequest(EntitlementContext context, ActivityParticipationPort.ReleaseParticipation command) { }
    public record QueryRequest(EntitlementContext context, SourceRef source) { }
}
