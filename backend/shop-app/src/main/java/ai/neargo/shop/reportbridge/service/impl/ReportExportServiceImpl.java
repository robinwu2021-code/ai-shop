package ai.neargo.shop.reportbridge.service.impl;
import ai.neargo.shop.spi.report.ReportExportPort.Create;
import ai.neargo.shop.spi.report.ReportExportPort.Reference;
import ai.neargo.shop.spi.report.ReportExportPort.Task;
import ai.neargo.shop.spi.report.ReportExportPort.Download;
import ai.neargo.shop.spi.report.ReportExportPort.Status;
import ai.neargo.shop.spi.report.ReportExportPort.Snapshot;


import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import ai.neargo.shop.auth.BizIdentityResolver;
import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.report.dao.ReportExportTaskDao;
import ai.neargo.shop.report.dto.ReportExportTaskRow;
import ai.neargo.shop.report.service.ReportQueryService;
import ai.neargo.shop.report.support.ReportCsvWriter;
import ai.neargo.shop.report.support.ReportEvidenceCodec;
import ai.neargo.shop.reportbridge.service.ReportArtifactStore;
import ai.neargo.shop.reportbridge.service.ReportExportService;
import ai.neargo.shop.spi.report.ReportQueryPort.Query;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Report-only state transitions; owner queries, asynchronous execution and file IO are outside report transactions. */
public final class ReportExportServiceImpl implements ReportExportService {
    private final ReportExportTaskDao tasks;
    private final ReportQueryService query;
    private final ReportArtifactStore files;
    private final BizIdentityResolver identities;
    private final Executor executor;
    private final TransactionTemplate write;
    public ReportExportServiceImpl(ReportExportTaskDao tasks,ReportQueryService query,ReportArtifactStore files,
            BizIdentityResolver identities,Executor executor,PlatformTransactionManager manager) {
        this.tasks=tasks; this.query=query; this.files=files; this.identities=identities; this.executor=executor;
        write=new TransactionTemplate(manager);
    }
    @Override public Task create(Create command) {
        authorize(command.query());
        var first=command.query().scopes().getFirst();
        var owner=new Reference(first.tenantNo(),first.entityNo(),command.query().actorNo(),"LOOKUP");
        String hash=ReportEvidenceCodec.hash(command.query());
        var existing=tasks.byRequest(owner,command.requestKey(),false);
        if (existing.isPresent()) return replay(existing.get(),hash);
        // Freeze the already-authorized result, including exact build/attempt/generation and incompleteness metadata.
        var snapshot=new Snapshot(command.query(),query.query(command.query()));
        ReportCsvWriter.write(snapshot); // Fail oversized exports before persisting an unusable task.
        String snapshotHash=ReportEvidenceCodec.hash(snapshot);
        Reference reference=new Reference(first.tenantNo(),first.entityNo(),command.query().actorNo(),BizKey.next("REXP"));
        ReportExportTaskRow row=write.execute(status-> {
            var raced=tasks.byRequest(owner,command.requestKey(),true);
            if (raced.isPresent()) {
                if (!raced.get().requestHash().equals(hash)) conflict("REPORT_EXPORT_IDEMPOTENCY_CONFLICT");
                return raced.get();
            }
            tasks.insert(reference,command.requestKey(),hash,snapshot,snapshotHash);
            return tasks.find(reference,false).orElseThrow();
        });
        dispatch(row.reference());return view(row);
    }
    private Task replay(ReportExportTaskRow row,String hash) {
        if (!row.requestHash().equals(hash)) conflict("REPORT_EXPORT_IDEMPOTENCY_CONFLICT");
        if (row.status()!=Status.SUCCEEDED && row.expiresAt().isAfter(row.databaseNow())) dispatch(row.reference());
        return view(row);
    }
    private void dispatch(Reference reference) {
        try { executor.execute(()-> {
            try { execute(reference); } catch (RuntimeException failure) { /* durable state remains recoverable */ }
        }); } catch (RuntimeException unavailable) { /* QUEUED is persisted; replay/daily maintenance can recover it. */ }
    }
    @Override public Task get(Reference ref) {
        var row=find(ref);authorize(snapshot(row).query());return view(row);
    }
    @Override public Download download(Reference ref) {
        var row=find(ref);authorize(snapshot(row).query());
        if (view(row).status()!=Status.SUCCEEDED || row.objectRef()==null) conflict("REPORT_EXPORT_NOT_DOWNLOADABLE");
        key(row,row.objectRef());
        byte[] bytes=files.read(row.objectRef());
        if (!ReportCsvWriter.checksum(bytes).equals(row.checksum())) conflict("REPORT_EXPORT_CHECKSUM_MISMATCH");
        return new Download(ref.exportNo()+".csv","text/csv; charset=UTF-8",row.checksum(),bytes);
    }
    @Override public void execute(Reference reference) {
        var original=find(reference);var fixed=snapshot(original);authorize(fixed.query());
        ReportExportTaskRow owned=write.execute(status-> {
            var row=tasks.find(reference,true).orElseThrow();
            if (row.expiresAt().compareTo(row.databaseNow())<=0 || row.status()==Status.SUCCEEDED || row.status()==Status.EXPIRED
                    || row.attempt()>=3 || row.status()==Status.RUNNING && row.leaseUntil()!=null && row.leaseUntil().isAfter(row.databaseNow())) return null;
            List<String> retired=new ArrayList<>(row.retiredObjects());
            if (row.objectRef()!=null && !retired.contains(row.objectRef())) retired.add(row.objectRef());
            String object=objectKey(row.reference(),row.attempt()+1);
            tasks.claim(row,UUID.randomUUID().toString(),object,UUID.randomUUID().toString(),retired);
            return tasks.find(reference,false).orElseThrow();
        });
        if (owned==null) return;
        try {
            byte[] bytes=ReportCsvWriter.write(snapshot(owned));
            key(owned,owned.objectRef());files.put(owned.objectRef(),bytes);
            write.executeWithoutResult(status->tasks.succeeded(owned,ReportCsvWriter.checksum(bytes),fixed.result().rows().size()));
        } catch (RuntimeException failure) {
            try { write.executeWithoutResult(status->tasks.failed(owned)); } catch (RuntimeException stale) { failure.addSuppressed(stale); }
            throw failure;
        }
    }
    @Override public void maintain(String tenant,String entity,int limit) {
        for (var row:tasks.maintenance(tenant,entity,limit)) {
            if (!row.expiresAt().isAfter(row.databaseNow())) {
                List<String> keys=new ArrayList<>(row.retiredObjects());if(row.objectRef()!=null) keys.add(row.objectRef());
                keys.forEach(k->key(row,k));files.delete(keys);
                write.executeWithoutResult(status->tasks.expired(row.reference()));
            } else dispatch(row.reference());
        }
    }
    private ReportExportTaskRow find(Reference ref) {
        return tasks.find(ref,false).orElseThrow(()->BizException.of(ErrorCode.NOT_FOUND));
    }
    private Snapshot snapshot(ReportExportTaskRow row) {
        var snapshot=ReportEvidenceCodec.read(row.snapshot(),Snapshot.class);
        if (!ReportEvidenceCodec.hash(snapshot).equals(row.snapshotHash()) || !ReportEvidenceCodec.hash(snapshot.query()).equals(row.requestHash()))
            conflict("REPORT_EXPORT_SNAPSHOT_CORRUPT");
        return snapshot;
    }
    private void authorize(Query q) {
        for (var scope:q.scopes()) {
            var ctx=identities.resolve(q.actorNo(),scope.storeNo());
            boolean inventory=q.aggregationSet().startsWith("INVENTORY_") || q.aggregationSet().equals("MATERIAL_USAGE_DAY");
            if (!"MAIN".equals(scope.tenantNo()) || !scope.entityNo().equals(ctx.merchantNo())
                    || !scope.storeNo().equals(ctx.currentStoreNo()) || !ctx.storeNos().contains(scope.storeNo())
                    || !ctx.can(BizPerms.FINANCE) || !ctx.can(BizPerms.REPORT_EXPORT) || inventory && !ctx.can(BizPerms.STOCK))
                throw BizException.of(ErrorCode.FORBIDDEN);
        }
    }
    private static Task view(ReportExportTaskRow row) {
        Status status=row.expiresAt().isAfter(row.databaseNow())?row.status():Status.EXPIRED;
        return new Task(row.reference().exportNo(),status,row.expiresAt(),row.rowCount(),row.checksum(),status==Status.FAILED?"REPORT_EXPORT_FAILED":null);
    }
    private static String objectKey(Reference ref,long attempt) {
        if (!ref.entityNo().matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("REPORT_INVALID_ARTIFACT_SCOPE");
        return ref.entityNo()+"/_REPORT/reports/"+ref.exportNo()+"/"+attempt+"_"+UUID.randomUUID()+".csv";
    }
    private static void key(ReportExportTaskRow row,String key) {
        String prefix=row.reference().entityNo()+"/_REPORT/reports/"+row.reference().exportNo()+"/";
        if (!key.startsWith(prefix) || !key.substring(prefix.length()).matches("[A-Za-z0-9_-]{1,80}\\.csv"))
            conflict("REPORT_INVALID_ARTIFACT_KEY");
    }
    private static void conflict(String code) { throw new IllegalStateException(code); }
}
