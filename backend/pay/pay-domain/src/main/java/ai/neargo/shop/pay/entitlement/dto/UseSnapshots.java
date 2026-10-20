package ai.neargo.shop.pay.entitlement.dto;

import ai.neargo.shop.spi.entitlement.EntitlementDefinition;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewQuery;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.QuoteView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.CouponAllocation;
import java.util.List;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.PreviewView;
import ai.neargo.shop.spi.entitlement.EntitlementUsePort.UseCommand;
import ai.neargo.shop.spi.entitlement.EntitlementUseSourcePort.UseSourceFact;
import ai.neargo.shop.spi.entitlement.SourceDecisionPort.AuthorizationDecision;
import java.time.LocalDate;

/** Immutable source/selection proof survives expiration and source changes after a successful hold. */
public final class UseSnapshots {
    private UseSnapshots() { }
    public record Preview(PreviewQuery query, PreviewView view, LocalDate serviceDay) { }
    public record Quoted(QuoteCommand command, QuoteView view, long assetRevision) { }
    public record Prepared(UseCommand command, UseSourceFact source, EntitlementDefinition definition,
                           AuthorizationDecision authorization, String selectionDigest, LocalDate serviceDay,
                           long discountMinor, long assetRevision, List<CouponAllocation> couponAllocations) { }
}
