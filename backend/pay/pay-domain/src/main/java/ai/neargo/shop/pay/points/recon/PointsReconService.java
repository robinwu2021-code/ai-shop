package ai.neargo.shop.pay.points.recon;

import java.util.List;

/**
 * Read-only financial reconciliation. Differences never trigger balance edits or fabricated
 * evidence.
 */
public interface PointsReconService {
    Report scan(String typeCode, long now);

    record Difference(String code, String reference, long expected, long actual) {}

    record Report(String typeCode, long checkedAt, boolean complete, List<Difference> differences) {
        public Report {
            differences = List.copyOf(differences);
        }

        public boolean balanced() {
            return complete && differences.isEmpty();
        }
    }
}
