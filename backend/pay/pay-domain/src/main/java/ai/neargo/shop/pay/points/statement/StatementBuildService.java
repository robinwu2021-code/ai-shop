package ai.neargo.shop.pay.points.statement;

/** Each invocation commits at most one bounded candidate page. READY is not a payable statement. */
public interface StatementBuildService {
    Build start(StoreStatementService.Period period, String actor);
    Build advance(String buildNo);
    Build advance(String typeCode,String buildNo);
    LinePage lines(String statementNo, long revision, long after, int limit);
    record SourceLine(long readySeq,String positionNo,long sourceVersion,long points) {}
    record LinePage(java.util.List<SourceLine> items,long nextAfter,boolean hasMore) {}


    record Build(String buildNo, String typeCode, String storeNo, long fromMillis, long toMillis,
                 long highWater, long cursor, long sourceCount, String state, long version) {}
}
