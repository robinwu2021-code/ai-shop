package ai.neargo.shop.trade.order.points;

import java.util.List;

/** Bounded source-side recovery; money execution remains with the pay-domain workers. */
public interface OrderPointsRecoveryService {
    Report recover(int limit);

    Inspection inspect(String subOrderNo);

    record Failure(String reference, String code) {}

    record Report(int attempted, int completed, List<Failure> failures) {
        public Report {
            failures = List.copyOf(failures);
        }
    }

    record Inspection(
            String subOrderNo,
            String bindingPhase,
            long bindingVersion,
            String placementPhase,
            long placementVersion,
            String originalHoldNo,
            List<String> refundPhases,
            long pendingSourceEvents,
            boolean paymentVerified,
            List<String> differences) {
        public Inspection {
            refundPhases = List.copyOf(refundPhases);
            differences = List.copyOf(differences);
        }
    }
}
