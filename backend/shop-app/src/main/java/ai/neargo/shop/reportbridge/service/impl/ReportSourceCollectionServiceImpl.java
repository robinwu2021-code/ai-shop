package ai.neargo.shop.reportbridge.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import ai.neargo.shop.reportbridge.service.ReportSourceCollectionService;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportSourceFactsPort;

/** No database transaction spans owner calls. Re-read required owners to detect changes during collection. */
public final class ReportSourceCollectionServiceImpl implements ReportSourceCollectionService {
    private static final SourceKey FOOD=new SourceKey(Owner.FOOD,"ORDER");
    private static final SourceKey CORE=new SourceKey(Owner.CORE,"ORDER");
    private final Map<SourceKey,ReportSourceFactsPort> ports;
    public ReportSourceCollectionServiceImpl(List<ReportSourceFactsPort> owners) {
        Map<SourceKey,ReportSourceFactsPort> found=new HashMap<>();
        for (var owner:owners) if (found.putIfAbsent(owner.source(),owner)!=null)
            throw new IllegalStateException("REPORT_DUPLICATE_SOURCE_OWNER");
        ports=Map.copyOf(found);
    }
    @Override public List<SourceSnapshot> collect(SourceQuery query) {
        var food=read(FOOD,query);
        var coreQuery=new SourceQuery(query.partition(),query.day(),query.actorNo(),food.sourceRefs(),query.rowLimit());
        var core=read(CORE,coreQuery);
        SourceSnapshot dependentCore=core;
        if(food.coverage()!=Coverage.COMPLETE&&food.coverage()!=Coverage.EMPTY_VERIFIED
                && (core.coverage()==Coverage.COMPLETE||core.coverage()==Coverage.EMPTY_VERIFIED)) {
            dependentCore=new SourceSnapshot(core.partition(),core.source(),core.snapshotRef(),core.sourceVersion(),core.asOfAt(),
                    core.rowCount(),core.checksum(),Coverage.PARTIAL,"FOOD_BINDING_COVERAGE_INCOMPLETE",core.facts(),core.sourceRefs());
        }
        List<SourceSnapshot> snapshots=new ArrayList<>(List.of(food,dependentCore));
        for (SourceKey key:List.of(new SourceKey(Owner.PAY,"PAYMENT"),new SourceKey(Owner.INVENTORY,"STOCK"),
                new SourceKey(Owner.EXPENSE,"EXPENSE"))) {
            if (!ports.containsKey(key)) snapshots.add(unavailable(query,key,"OWNER_SOURCE_NOT_AVAILABLE"));
            else {
                try { snapshots.add(read(key,coreQuery)); }
                catch (RuntimeException ex) { snapshots.add(unavailable(query,key,"OWNER_SOURCE_READ_FAILED")); }
            }
        }
        var foodAgain=read(FOOD,query); var coreAgain=read(CORE,coreQuery);
        if (!same(food,foodAgain) || !same(core,coreAgain)) throw new IllegalStateException("REPORT_SOURCE_CHANGED_DURING_COLLECTION");
        return List.copyOf(snapshots);
    }
    private static SourceSnapshot unavailable(SourceQuery query,SourceKey key,String reason) {
        return new SourceSnapshot(query.partition(),key,null,null,Instant.now(),0,null,Coverage.UNKNOWN,reason,List.of(),List.of());
    }
    private SourceSnapshot read(SourceKey key,SourceQuery q) {
        var port=ports.get(key);
        if (port==null) throw new IllegalStateException("REPORT_REQUIRED_OWNER_NOT_AVAILABLE");
        var result=port.readSnapshot(q);
        if (!result.partition().equals(q.partition()) || !result.source().equals(key) || result.rowCount()>q.rowLimit())
            throw new IllegalStateException("REPORT_OWNER_SCOPE_MISMATCH");
        return result;
    }
    private static boolean same(SourceSnapshot a,SourceSnapshot b) {
        return a.coverage()==b.coverage() && Objects.equals(a.checksum(),b.checksum())
                && Objects.equals(a.sourceVersion(),b.sourceVersion()) && a.sourceRefs().equals(b.sourceRefs());
    }
}
