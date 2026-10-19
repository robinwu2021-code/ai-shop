package ai.neargo.shop.spi.points;

import java.util.List;

/**
 * Authoritative ordered source scan, implemented by the host integration after the points kernel is
 * complete.
 */
public interface SourceFactsPort {
    Page scan(String typeCode, long afterSequence, int limit);

    record Fact(
            long sequence,
            PointsFactPort.LifecycleFact lifecycle,
            PointsEarnPort.GrantCommand earning,
            PointsFactPort.RefundClosureFact closure,
            PointsRefundPort.ZeroCashCompletionFact zeroCash,
            PointsFactPort.RefundConfirmationFact refund) {}

    record Page(long nextSequence, List<Fact> facts) {
        public Page {
            facts = List.copyOf(facts);
        }
    }
}
