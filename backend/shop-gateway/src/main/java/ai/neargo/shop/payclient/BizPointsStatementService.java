package ai.neargo.shop.payclient;

import ai.neargo.shop.pay.points.admin.PointsOperatorJournal.OperatorResult;
import java.util.List;

/** Store statements available to finance staff; no agreement signing or payment instruction. */
public interface BizPointsStatementService {
    View statement(String typeCode, String statementNo);
    ai.neargo.shop.pay.points.admin.PointsWorkbenchService.StatementLinePage lines(
            String typeCode, String statementNo, long revision, long after, int limit);
    Page statements(String typeCode, long after, int limit);
    OperatorResult confirm(Change request, String actor);
    OperatorResult dispute(Change request, String actor);

    record Change(String requestId, String typeCode, String statementNo, long revision, String reason) {}
    record Revision(long number, long points, String reason) {}
    record View(long id, String statementNo, String typeCode, String storeNo, long fromMillis,
                long toMillis, String state, long revision, long points, String disputeReason,
                List<Revision> revisions) {}
    record Page(List<View> rows, long next, boolean hasMore) {}
}
