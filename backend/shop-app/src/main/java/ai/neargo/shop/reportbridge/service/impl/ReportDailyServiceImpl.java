package ai.neargo.shop.reportbridge.service.impl;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;
import java.util.function.BiConsumer;

import ai.neargo.shop.report.config.ReportStoreProperties;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.report.support.ReportMetricCalculator;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.reportbridge.service.ReportDailyService;
import ai.neargo.shop.reportbridge.service.ReportSourceCollectionService;
import ai.neargo.shop.spi.report.ReportContracts.BuildReq;
import ai.neargo.shop.spi.report.ReportContracts.Owner;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Publication;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts.SourceQuery;
import ai.neargo.shop.spi.report.ReportDayPort;

/** One daily execution contains recovery, contiguous planning, dirty-day rebuild and atomic publication. */
public final class ReportDailyServiceImpl implements ReportDailyService {
    private static final String ACTOR="REPORT_DAILY_JOB";
    private final ReportStorageService storage;
    private final ReportSourceCollectionService sources;
    private final ReportDayPort days;
    private final ReportMetricCatalog catalog;
    private final ReportMetricCalculator calculator;
    private final ReportStoreProperties.Food props;
    private final Clock clock;
    private BiConsumer<String,String> maintenance=(tenant,entity)->{};
    public void setMaintenance(BiConsumer<String,String> maintenance) { this.maintenance=maintenance; }
    public ReportDailyServiceImpl(ReportStorageService storage,ReportSourceCollectionService sources,ReportDayPort days,
            ReportMetricCatalog catalog,ReportMetricCalculator calculator,ReportStoreProperties.Food props,Clock clock) {
        this.storage=storage; this.sources=sources; this.days=days; this.catalog=catalog; this.calculator=calculator; this.props=props; this.clock=clock;
    }
    @Override public Outcome runDaily(String runId,LocalDate through) {
        int published=0,failed=0,deferred=0,maintenanceFailed=0;
        var enabled=storage.enabledScopesForWorker(1000);
        for (var cursor:enabled) {
            if(cursor.partition().metricVersion()!=props.getMetricVersion()) continue;
            var scope=cursor.partition().scope(); int version=cursor.partition().metricVersion();
            int budget=props.getMaxPartitions();
            for (var retry:storage.recoverableBuilds(scope,version,Math.max(1,budget/3))) {
                budget--;
                try { rebuild(retry); published++; } catch (RuntimeException ex) { failed++; }
            }
            LocalDate date=cursor.lastPlannedDate()==null?cursor.firstDate():cursor.lastPlannedDate().plusDays(1);
            while (budget>1 && (through==null || !date.isAfter(through))) {
                var partition=new Partition(scope,date,version);
                try {
                    var evidence=days.day(scope,date);
                    if (clock.instant().isBefore(evidence.endsAt().plusSeconds(props.getBufferMinutes()*60L))) { deferred++; break; }
                    var req=request(partition,"DAILY:"+version+":"+date,"INITIAL",0,runId);
                    budget--;
                    try { rebuild(req); published++; } catch (RuntimeException ex) { failed++; }
                } catch (RuntimeException ex) { failed++; break; }
                date=date.plusDays(1);
            }
            for (var dirty:storage.pendingDirty(scope,version,Math.max(1,budget))) {
                if (budget<=0) break;
                if (clock.instant().isBefore(dirty.readyAfter())) { deferred++; continue; }
                budget--;
                try {
                    rebuild(request(dirty.partition(),"DIRTY:"+version+":"+dirty.partition().businessDate()+":"+dirty.generation(),
                            "DIRTY",dirty.generation(),runId)); published++;
                } catch (RuntimeException ex) { failed++; }
            }
        }
        var maintained=new HashSet<String>();
        for (var cursor:enabled) {
            var scope=cursor.partition().scope();
            if (maintained.add(scope.tenantNo()+":"+scope.entityNo())) {
                try { maintenance.accept(scope.tenantNo(),scope.entityNo()); }
                catch (RuntimeException failure) { maintenanceFailed++; }
            }
        }
        return new Outcome(published,failed,deferred,maintenanceFailed);
    }
    private BuildReq request(Partition partition,String key,String reason,long dirty,String runId) {
        return new BuildReq(partition,days.day(partition.scope(),partition.businessDate()),ACTOR,key,catalog.hash(),
                Set.of(new SourceKey(Owner.FOOD,"ORDER"),new SourceKey(Owner.CORE,"ORDER")),dirty,reason,runId);
    }
    @Override public Publication rebuild(BuildReq request) {
        if (request.partition().metricVersion()!=props.getMetricVersion()
                || !request.requiredSources().equals(Set.of(new SourceKey(Owner.FOOD,"ORDER"),new SourceKey(Owner.CORE,"ORDER"))))
            throw new IllegalArgumentException("REPORT_BUILD_POLICY_MISMATCH");
        var original=days.day(request.partition().scope(),request.partition().businessDate());
        if (!request.day().accepts(original) || clock.instant().isBefore(original.endsAt().plusSeconds(props.getBufferMinutes()*60L)))
            throw new IllegalStateException("REPORT_DAY_NOT_READY");
        var lease=storage.beginBuild(request,UUID.randomUUID().toString());
        if (lease.alreadyPublished()) return storage.publish(lease);
        try {
            var snapshots=sources.collect(new SourceQuery(request.partition(),request.day(),request.actorNo(),List.of(),props.getRowLimit()));
            if(snapshots.stream().anyMatch(s->"FOOD_ORDER_BINDING_UNRESOLVED".equals(s.unknownReason())))
                throw new IllegalStateException("REPORT_FOOD_BINDING_UNRESOLVED");
            var facts=calculator.aggregate(request.partition(),snapshots);
            storage.stage(lease,facts,snapshots);
            return storage.publish(lease);
        } catch (RuntimeException failure) {
            try {
                String code=failure.getMessage();
                storage.fail(lease,code!=null&&code.matches("REPORT_[A-Z0-9_]{1,56}")?code:"REPORT_BUILD_FAILED");
            } catch (RuntimeException stale) { failure.addSuppressed(stale); }
            throw failure;
        }
    }
}
