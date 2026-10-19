package ai.neargo.shop.pay.points.statement;

import java.util.List;

/**
 * Store-period statements freeze their source set while retaining every revision for later review.
 */
public interface StoreStatementService {
    Snapshot draft(Period period, String actor);

    /** A store-period can contain multiple immutable original bank routes. */
    List<Snapshot> draftGroups(Period period, String actor);

    Snapshot redraft(String statementNo, long revision, String actor);

    Snapshot confirm(String statementNo, long revision, String actor);

    Snapshot dispute(String statementNo, long revision, String actor, String reason);

    /** Reopens an unpaid confirmed statement atomically before its source is frozen for refund. */
    void reopenForRefund(String positionNo);

    record Period(
            String typeCode, String storeNo, long fromMillis, long toMillis, String routeKey) {
        public Period(String typeCode, String storeNo, long fromMillis, long toMillis) {
            this(typeCode, storeNo, fromMillis, toMillis, null);
        }
    }

    record Line(String positionNo, long sourceVersion, long points) {}

    record Revision(long number, List<Line> lines, long points, String actor, String reason, Long sourceVersionSum) {
        public Revision(long number,List<Line> lines,long points,String actor,String reason) {
            this(number,lines,points,actor,reason,0L);
        }
        public Revision {
            sourceVersionSum = sourceVersionSum == null ? 0L : sourceVersionSum;
            lines = List.copyOf(lines);
        }
    }

    record Snapshot(
            String statementNo,
            Period period,
            List<String> positionIds,
            List<Revision> revisions,
            String state,
            String disputeReason, String sourceBuildNo) {
        public Snapshot(String no,Period period,List<String> ids,List<Revision> revisions,String state,String reason) {
            this(no,period,ids,revisions,state,reason,null);
        }
        public Snapshot {
            positionIds = List.copyOf(positionIds);
            revisions = List.copyOf(revisions);
        }

        public Revision current() {
            return revisions.getLast();
        }
    }
}
