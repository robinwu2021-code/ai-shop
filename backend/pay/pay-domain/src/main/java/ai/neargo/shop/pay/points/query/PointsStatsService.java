package ai.neargo.shop.pay.points.query;

import java.time.LocalDate;
import java.util.Map;

/** Daily projections are replaceable metadata; the ledger and immutable decisions remain authoritative. */
public interface PointsStatsService {
    Snapshot rebuild(String typeCode,LocalDate day);
    Snapshot read(String typeCode,LocalDate day,String scopeNo);
    int project(String typeCode,int limit);
    record Snapshot(String typeCode,LocalDate day,String scopeNo,Map<String,Long> metrics,
                    Map<String,Long> skipReasons,long throughVoucherId,long rebuiltAt) {
        public Snapshot { metrics=Map.copyOf(metrics); skipReasons=Map.copyOf(skipReasons); }
    }
}
