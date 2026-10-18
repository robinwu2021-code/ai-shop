package ai.neargo.shop.trade.order.points;

import ai.neargo.shop.spi.points.*;

import java.time.Instant;
import java.util.List;

/** Source stages are short transactions; quote/hold/release always run between them. */
public interface PointsOrderPlacementCoordinator {
    Placement stage(Draft draft);

    Placement inspect(String orderNo);

    Placement resume(String orderNo);

    void recordCancellation(String orderNo);

    List<String> pending(int limit);

    enum Phase {
        RAW,
        ADMITTED,
        UNKNOWN,
        READY,
        RECONFIRM,
        CANCEL_REQUESTED,
        CANCELLED
    }

    record Candidate(
            OrderPointsTargetFactory.PlacementTarget target,
            long policyVersion,
            boolean earning,
            boolean redemption,
            List<PointsEarnPort.EarnLine> rules) {
        public Candidate {
            rules = List.copyOf(rules);
        }
    }

    record Draft(
            String orderNo,
            String userNo,
            String market,
            String currency,
            String scene,
            String client,
            long wantPoints,
            long basePayMinor,
            String requestHash,
            List<Candidate> candidates,
            List<String> allSubOrderNos) {
        public Draft {
            candidates = List.copyOf(candidates);
            allSubOrderNos = List.copyOf(allSubOrderNos);
        }
    }

    record EarningSnapshot(
            int schemaVersion, long fallbackRevision, List<PointsEarnPort.EarnLine> lines) {
        public EarningSnapshot {
            lines = List.copyOf(lines);
        }
    }

    record Resolved(
            List<OrderPointsBindingService.Admission> admissions,
            PointsRedeemPort.RedeemQuote quote,
            PointsRedeemPort.HoldCommand command,
            PointsRedeemPort.ReleaseCommand cancellationCommand) {
        public Resolved {
            admissions = List.copyOf(admissions);
        }

        public Resolved(
                List<OrderPointsBindingService.Admission> admissions,
                PointsRedeemPort.RedeemQuote quote,
                PointsRedeemPort.HoldCommand command) {
            this(admissions, quote, command, null);
        }
    }

    record Placement(
            Draft draft,
            Phase phase,
            long version,
            Resolved resolved,
            PointsRedeemPort.HoldOutcome outcome,
            String reason) {}

    record SourceOrder(
            String status,
            String userNo,
            String currency,
            long payMinor,
            long version,
            Instant changedAt) {
        public SourceOrder(
                String status, String userNo, String currency, long payMinor, long version) {
            this(status, userNo, currency, payMinor, version, Instant.EPOCH);
        }
    }

    /** Implemented by the existing order storage boundary, never by pay. */
    interface SourceOrders {
        SourceOrder lock(String orderNo);

        void finish(Draft draft, PointsRedeemPort.HoldOutcome outcome);
    }
}
