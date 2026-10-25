package ai.neargo.shop.report.service.impl;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.report.dao.ReportBuildBatchDao;
import ai.neargo.shop.report.dao.ReportDayPublicationDao;
import ai.neargo.shop.report.dao.ReportDirtyDayDao;
import ai.neargo.shop.report.dao.ReportFoodMetricDailyDao;
import ai.neargo.shop.report.dao.ReportScopeCursorDao;
import ai.neargo.shop.report.dao.ReportSourceManifestDao;
import ai.neargo.shop.report.dto.ReportRows.BuildRow;
import ai.neargo.shop.report.dto.ReportRows.CursorRow;
import ai.neargo.shop.report.dto.ReportRows.DirtyRow;
import ai.neargo.shop.report.dto.ReportRows.PublicationRow;
import ai.neargo.shop.report.service.ReportStorageService;
import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.report.support.ReportMetricCatalog;
import ai.neargo.shop.spi.report.ReportContracts.BuildLease;
import ai.neargo.shop.spi.report.ReportContracts.BuildReq;
import ai.neargo.shop.spi.report.ReportContracts.BuildProblem;
import ai.neargo.shop.spi.report.ReportContracts.BuildStatus;
import ai.neargo.shop.spi.report.ReportContracts.Coverage;
import ai.neargo.shop.spi.report.ReportContracts.MetricFact;
import ai.neargo.shop.spi.report.ReportContracts.Partition;
import ai.neargo.shop.spi.report.ReportContracts.Publication;
import ai.neargo.shop.spi.report.ReportContracts.PublishedDay;
import ai.neargo.shop.spi.report.ReportContracts.Scope;
import ai.neargo.shop.spi.report.ReportContracts.SourceSnapshot;
import ai.neargo.shop.spi.report.ReportContracts.SourceKey;
import ai.neargo.shop.spi.report.ReportContracts;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A single storage boundary. Every write owns the partition fence; no RPC inside transactions. */
public class ReportStorageServiceImpl implements ReportStorageService {
    private final ReportDayPublicationDao publications;
    private final ReportBuildBatchDao builds;
    private final ReportSourceManifestDao manifests;
    private final ReportFoodMetricDailyDao metrics;
    private final ReportDirtyDayDao dirty;
    private final ReportScopeCursorDao cursors;
    private final ReportMetricCatalog catalog;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    private final int leaseSeconds;
    private final int rowLimit;

    public ReportStorageServiceImpl(ReportDayPublicationDao publications, ReportBuildBatchDao builds,
            ReportSourceManifestDao manifests, ReportFoodMetricDailyDao metrics, ReportDirtyDayDao dirty,
            ReportScopeCursorDao cursors, ReportMetricCatalog catalog, PlatformTransactionManager manager,
            int leaseSeconds, int rowLimit) {
        this.publications=publications; this.builds=builds; this.manifests=manifests; this.metrics=metrics;
        this.dirty=dirty; this.cursors=cursors; this.catalog=catalog; this.leaseSeconds=leaseSeconds; this.rowLimit=rowLimit;
        write=new TransactionTemplate(manager);
        read=new TransactionTemplate(manager); read.setReadOnly(true);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Override public List<BuildProblem> buildProblems(Scope scope,LocalDate from,LocalDate to,int version,int limit) {
        range(from,to);if(limit<1||limit>1000) throw new IllegalArgumentException("REPORT_INVALID_LIMIT");
        var rows=read.execute(status->builds.problems(scope,from,to,version,limit));
        if(rows.size()>limit) conflict("REPORT_QUERY_METADATA_LIMIT");return rows;
    }
    @Override public List<CursorRow> enabledScopesForWorker(int limit) {
        var rows=read.execute(status -> cursors.enabledForWorker(limit));
        if (rows.size()>limit) conflict("REPORT_WORKER_SCOPE_LIMIT");
        return rows;
    }
    @Override public List<BuildReq> recoverableBuilds(Scope scope,int version,int limit) {
        return read.execute(status -> builds.recoverable(scope,version,limit));
    }
    @Override public void enableScope(Scope scope,int version,LocalDate first) {
        new Partition(scope,first,version);
        write.executeWithoutResult(status -> cursors.enable(scope,version,first));
    }
    @Override public List<CursorRow> scopes(Scope scope) { return read.execute(status -> cursors.find(scope,false)); }

    @Override public BuildLease beginBuild(BuildReq req,String worker) {
        identifier(worker,64);
        if (!catalog.hash().equals(req.specHash())) conflict("REPORT_SPEC_MISMATCH");
        return write.execute(status -> {
            boolean enabled=cursors.find(req.partition().scope(),false).stream().anyMatch(c -> c.enabled()
                    && c.partition().metricVersion()==req.partition().metricVersion()
                    && !req.partition().businessDate().isBefore(c.firstDate()));
            if (!enabled) conflict("REPORT_SCOPE_DISABLED");
            publications.ensure(req.partition());
            var p=publications.find(req.partition(),true).orElseThrow();
            String hash=requestHash(req);
            var old=builds.byRequest(req.partition().scope(),req.requestKey(),true);
            if (old.isPresent() && !old.get().requestHash().equals(hash)) conflict("REPORT_IDEMPOTENCY_CONFLICT");
            if (old.isPresent() && old.get().status()==BuildStatus.PUBLISHED) {
                if ("INITIAL".equals(req.reason()))
                    cursors.advanceContiguous(req.partition().scope(),req.partition().metricVersion(),req.partition().businessDate());
                return lease(old.get(),true);
            }
            if (p.databaseNow().isBefore(req.day().endsAt())) conflict("REPORT_DAY_NOT_ENDED");
            if (p.leaseUntil()!=null && p.leaseUntil().isAfter(p.databaseNow())) conflict("REPORT_PARTITION_BUSY");
            long attempt=old.map(b -> Math.addExact(b.attempt(),1L)).orElse(1L);
            long fence=Math.addExact(p.fence(),1L);
            var lease=new BuildLease(req.partition(),old.map(BuildRow::buildNo).orElseGet(() -> BizKey.next("RBLD")),
                    attempt,fence,p.generation(),worker,p.databaseNow().plusSeconds(leaseSeconds),hash,false);
            publications.claim(req.partition(),p.fence(),worker,lease.leaseUntil());
            if (old.isPresent()) builds.reclaim(lease,old.get().attempt()); else builds.insert(req,lease);
            if ("INITIAL".equals(req.reason()))
                cursors.advanceContiguous(req.partition().scope(),req.partition().metricVersion(),req.partition().businessDate());
            return lease;
        });
    }

    @Override public void stage(BuildLease lease,List<MetricFact> facts,List<SourceSnapshot> sources) {
        if (facts.size()>rowLimit || sources.size()>32) conflict("REPORT_SNAPSHOT_LIMIT");
        write.executeWithoutResult(status -> {
            BuildRow b=owned(lease);
            validate(b,facts,sources);
            String hash=contentHash(facts,sources);
            if (b.validationHash()!=null) {
                if (!b.validationHash().equals(hash)) conflict("REPORT_STAGE_REPLAY_CONFLICT");
                return;
            }
            for (SourceSnapshot snapshot:sources) manifests.insert(lease,receipt(snapshot));
            for (MetricFact fact:facts) metrics.insert(lease,fact,catalog.get(fact.metricCode()).source().family());
            builds.staged(lease,hash);
        });
    }

    @Override public Publication publish(BuildLease lease) {
        return write.execute(status -> {
            if (lease.alreadyPublished()) return published(builds.byNo(lease.partition().scope(),lease.buildNo(),false).orElseThrow());
            BuildRow b=owned(lease);
            var facts=metrics.find(lease.partition(),lease.buildNo(),lease.attemptNo());
            var sources=manifests.find(lease.partition(),lease.buildNo(),lease.attemptNo());
            validate(b,facts,sources);
            if (b.validationHash()==null || !b.validationHash().equals(contentHash(facts,sources))) conflict("REPORT_VALIDATION_MISMATCH");
            publications.publish(lease,ReportEvidenceCodec.json(sources.stream().map(s -> Arrays.asList(s.source(),s.coverage(),s.unknownReason())).toList()));
            builds.published(lease,publications.find(lease.partition(),false).orElseThrow().publishedAt());
            dirty.acknowledge(lease.partition(),b.request().dirtyGeneration());
            var cursor=cursors.find(lease.partition().scope(),true);
            if (cursor.stream().noneMatch(CursorRow::defaultForReads)
                    && cursor.stream().filter(CursorRow::enabled).count()==1)
                cursors.defaultVersion(lease.partition().scope(),lease.partition().metricVersion());
            return publication(lease.partition(),publications.find(lease.partition(),false).orElseThrow(),b.request().specHash());
        });
    }

    @Override public Optional<Publication> findResult(Scope scope,String requestKey) {
        identifier(requestKey,128);
        return read.execute(status -> builds.byRequest(scope,requestKey,false)
                .filter(b -> b.status()==BuildStatus.PUBLISHED).map(this::published));
    }

    @Override public void fail(BuildLease lease,String code) {
        identifier(code,64);
        write.executeWithoutResult(status -> {
            owned(lease);
            builds.failed(lease,code,Instant.now().plusSeconds(60));
            publications.release(lease);
        });
    }

    @Override public List<PublishedDay> readDays(Scope scope,LocalDate from,LocalDate to,int version) {
        range(from,to); new Partition(scope,from,version);
        return read.execute(status -> {
            var result=new ArrayList<PublishedDay>();
            for (LocalDate date=from;date.isBefore(to);date=date.plusDays(1)) {
                var partition=new Partition(scope,date,version);
                var p=publications.find(partition,false);
                if (p.isEmpty() || p.get().buildNo()==null) continue;
                var row=p.get();
                var b=builds.byNo(scope,row.buildNo(),false).orElseThrow();
                if (b.status()!=BuildStatus.PUBLISHED || b.attempt()!=row.attempt()) conflict("REPORT_INVALID_PUBLICATION");
                var points=metrics.find(partition,row.buildNo(),row.attempt());
                var receipts=manifests.find(partition,row.buildNo(),row.attempt());
                if (!Objects.equals(b.validationHash(),contentHash(points,receipts))) conflict("REPORT_PUBLICATION_CORRUPT");
                result.add(new PublishedDay(publication(partition,row,b.request().specHash()),b.request().day(),points,receipts));
                if (result.stream().mapToLong(d -> d.facts().size()).sum()>rowLimit)
                    conflict("REPORT_QUERY_ROW_LIMIT");
            }
            return List.copyOf(result);
        });
    }

    @Override public void markDirty(Partition p,String event,String reason,Instant after) {
        identifier(event,128);identifier(reason,48);Objects.requireNonNull(after);
        write.executeWithoutResult(status -> dirty.mark(p,event,reason,after));
    }
    @Override public List<DirtyRow> pendingDirty(Scope scope,int version,int limit) {
        if (limit<1 || limit>366) throw new IllegalArgumentException("REPORT_INVALID_LIMIT");
        return read.execute(status -> dirty.pending(scope,version,limit));
    }
    @Override public void activateVersion(Scope scope,int version,LocalDate from,LocalDate to) {
        range(from,to);
        write.executeWithoutResult(status -> {
            var rows=cursors.find(scope,true);
            if (rows.stream().noneMatch(r -> r.enabled() && r.partition().metricVersion()==version)) conflict("REPORT_VERSION_NOT_ENABLED");
            for (LocalDate date=from;date.isBefore(to);date=date.plusDays(1)) {
                var p=publications.find(new Partition(scope,date,version),false);
                if (p.isEmpty() || p.get().buildNo()==null) conflict("REPORT_VERSION_COVERAGE_MISSING");
            }
            cursors.defaultVersion(scope,version);
        });
    }

    private BuildRow owned(BuildLease lease) {
        var p=publications.find(lease.partition(),true).orElseThrow();
        var b=builds.byNo(lease.partition().scope(),lease.buildNo(),true).orElseThrow();
        if (!b.request().partition().equals(lease.partition()) || p.fence()!=lease.fence() || b.fence()!=lease.fence()
                || b.attempt()!=lease.attemptNo() || !Objects.equals(p.leaseOwner(),lease.leaseOwner())
                || !Objects.equals(b.leaseOwner(),lease.leaseOwner()) || p.leaseUntil()==null
                || !p.leaseUntil().isAfter(p.databaseNow()) || (b.status()!=BuildStatus.RUNNING && b.status()!=BuildStatus.VALIDATING))
            conflict("REPORT_STALE_EXECUTOR");
        return b;
    }
    private void validate(BuildRow build,List<MetricFact> facts,List<SourceSnapshot> sources) {
        var keys=new HashSet<String>();
        var owners=new HashSet<SourceKey>();
        for (SourceSnapshot s:sources) {
            if (!s.partition().equals(build.request().partition()) || !owners.add(s.source())) conflict("REPORT_SOURCE_SCOPE_MISMATCH");
        }
        for (var required:build.request().requiredSources()) {
            var source=sources.stream().filter(s -> s.source().equals(required)).findFirst().orElse(null);
            if (source==null || (source.coverage()!=Coverage.COMPLETE && source.coverage()!=Coverage.EMPTY_VERIFIED)) conflict("REPORT_REQUIRED_SOURCE_UNAVAILABLE");
        }
        if (facts.isEmpty()) conflict("REPORT_EMPTY_WITHOUT_METRICS");
        for (MetricFact fact:facts) {
            var definition=catalog.get(fact.metricCode());
            catalog.validate(fact,definition.source());
            if (!owners.contains(definition.source())) conflict("REPORT_METRIC_SOURCE_MISSING");
            if (!keys.add(fact.metricCode()+":"+ReportFoodMetricDailyDao.dimensionKey(fact))) conflict("REPORT_DUPLICATE_METRIC");
        }
    }
    private Publication published(BuildRow row) {
        if (row.status()!=BuildStatus.PUBLISHED) conflict("REPORT_NOT_PUBLISHED");
        return new Publication(row.request().partition(),row.buildNo(),row.attempt(),Math.addExact(row.expectedGeneration(),1L),
                row.finishedAt(),row.request().specHash());
    }
    private static Publication publication(Partition p,PublicationRow row,String hash) {
        return new Publication(p,row.buildNo(),row.attempt(),row.generation(),row.publishedAt(),hash);
    }
    private static BuildLease lease(BuildRow b,boolean published) {
        return new BuildLease(b.request().partition(),b.buildNo(),b.attempt(),b.fence(),b.expectedGeneration(),b.leaseOwner(),b.leaseUntil(),b.requestHash(),published);
    }
    public static String requestHash(BuildReq req) {
        var required=req.requiredSources().stream().sorted(Comparator.comparing(s -> s.owner().name()+":"+s.family())).toList();
        return ReportEvidenceCodec.hash(Arrays.asList(req.partition(),req.day(),req.actorNo(),req.requestKey(),req.specHash(),required,req.dirtyGeneration(),req.reason()));
    }
    private static SourceSnapshot receipt(SourceSnapshot s) {
        return new SourceSnapshot(s.partition(),s.source(),s.snapshotRef(),s.sourceVersion(),s.asOfAt(),s.rowCount(),s.checksum(),s.coverage(),s.unknownReason(),List.of(),s.sourceRefs());
    }
    public static String contentHash(List<MetricFact> facts,List<SourceSnapshot> sources) {
        return ReportEvidenceCodec.hash(Arrays.asList(
                facts.stream().sorted(Comparator.comparing(f -> f.metricCode()+":"+ReportFoodMetricDailyDao.dimensionKey(f))).toList(),
                sources.stream().map(ReportStorageServiceImpl::receipt).sorted(Comparator.comparing(s -> s.source().owner().name()+":"+s.source().family())).toList()));
    }
    private static void range(LocalDate from,LocalDate to) {
        if (from==null || to==null || !from.isBefore(to) || from.plusDays(366).isBefore(to)) throw new IllegalArgumentException("REPORT_INVALID_RANGE");
    }
    private static void identifier(String value,int max) { ReportContracts.identifier(value,max); }
    private static void conflict(String code) { throw new IllegalStateException(code); }
}
