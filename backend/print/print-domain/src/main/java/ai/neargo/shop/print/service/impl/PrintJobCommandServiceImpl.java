package ai.neargo.shop.print.service.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.dispatch.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.operation.PrintOperationService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.source.PrintSourceRegistry;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class PrintJobCommandServiceImpl implements PrintJobCommandService {
    private final ai.neargo.shop.print.audit.PrintActionAppender audit;
    private final PrnJobMapper jobs;
    private final PrnAttemptMapper attempts;
    private final PrnRequestMapper requests;
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final PrintOperationService ops;
    private final PrintReceiptService receipts;
    private final PrinterLeaseService leases;
    private final PrintSourceRegistry sources;
    private final PrintJsonCodec json;

    public PrintJobCommandServiceImpl(
            PrnJobMapper j,
            PrnAttemptMapper a,
            PrnRequestMapper r,
            PrnPrinterMapper p,
            PrnPrinterRuntimeMapper rt,
            PrintOperationService o,
            PrintReceiptService rc,
            PrinterLeaseService l,
            PrintSourceRegistry s,
            PrintJsonCodec c,
            ai.neargo.shop.print.audit.PrintActionAppender audit) {
        this.audit = audit;
        jobs = j;
        attempts = a;
        requests = r;
        printers = p;
        runtimes = rt;
        ops = o;
        receipts = rc;
        leases = l;
        sources = s;
        json = c;
    }

    private PrnJob job(String no, Long revision, PrintActor a) {
        var j =
                jobs.selectOne(
                        PrintSupport.<PrnJob>scoped(scope(a)).eq("job_no", no).last("FOR UPDATE"));
        revision(j, revision);
        return j;
    }

    private PrnAttempt attempt(PrnJob j, PrintActor a) {
        var at =
                attempts.selectOne(
                        PrintSupport.<PrnAttempt>owned(scope(a))
                                .eq("attempt_no", j.getCurrentAttemptNo()));
        require(at != null, "print.attemptNotFound");
        return at;
    }

    private void save(PrnJob j) {
        j.setRevision(j.getRevision() + 1);
        j.setUpdatedAt(now());
        jobs.updateById(j);
    }

    private void reason(String r) {
        require(r != null && !r.isBlank() && r.length() <= 200, "print.reasonRequired");
    }

    private void evidence(String r) {
        require(r != null && r.matches("[A-Za-z0-9_:-]{4,128}"), "print.evidenceReferenceRequired");
    }

    public PrintOperation retryJob(String no, RetryJobReq r, PrintActor a) {
        return ops.execute(
                a,
                "RETRY_JOB",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    reason(r.reason());
                    var j = job(no, r.expectedRevision(), a);
                    require(
                            "RETRY_WAIT".equals(j.getStatus())
                                    && j.getRetryCount() <= j.getMaxRetryCount(),
                            "print.jobNotRetryable");
                    var at = attempt(j, a);
                    var details =
                            json.tree(at.getResultSummary() == null ? "{}" : at.getResultSummary());
                    require(
                            "NOT_ACCEPTED".equals(at.getStatus())
                                    && details.path("queueTerminated").asBoolean()
                                    && !details.path("partialOutput").asBoolean(),
                            "print.notAcceptedEvidenceRequired");
                    j.setNextRetryAt(now());
                    save(j);
                    return succeeded("RETRY_JOB", "JOB", no, json.freeze(Map.of("jobNo", no)));
                });
    }

    public PrintOperation cancelJob(String no, CancelJobReq r, PrintActor a) {
        return ops.execute(
                a,
                "CANCEL_JOB",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    reason(r.reason());
                    var j = job(no, r.expectedRevision(), a);
                    require(
                            !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(j.getStatus()),
                            "print.jobTerminal");
                    if (Set.of("PENDING", "RETRY_WAIT", "STALE").contains(j.getStatus())) {
                        j.setStatus("CANCELLED");
                        j.setCancelRequestedAt(now());
                        j.setFinishedAt(now());
                        save(j);
                        receipts.refreshBatchProjection(scope(a), j.getBatchNo());
                        return succeeded("CANCEL_JOB", "JOB", no, json.freeze(Map.of("jobNo", no)));
                    }
                    require(a.dataScopes().contains("biz:print:resolve"), "print.forbidden");
                    var at = attempt(j, a);
                    j.setCancelRequestedAt(now());
                    save(j);
                    var result = new LinkedHashMap<String, Object>();
                    result.put("jobNo", no);
                    result.put("printerNo", j.getPrinterNo());
                    result.put("commandType", "CANCEL");
                    result.put("dispatchKey", at.getDispatchKey());
                    result.put("attemptNo", at.getAttemptNo());
                    result.put("connectionRevision", at.getConnectionRevision());
                    result.put("sessionEpoch", at.getAgentSessionEpoch());
                    result.put("fenceEpoch", at.getFenceEpoch());
                    result.put("expiresAt", Instant.now().plusSeconds(60).toString());
                    return new PrintOperation(
                            no("PO"),
                            "CANCEL_JOB",
                            PrintOperationStatus.PENDING,
                            "JOB",
                            no,
                            json.freeze(result),
                            null,
                            Instant.now(),
                            null);
                });
    }

    public PrintOperation queryResult(String no, QueryResultReq r, PrintActor a) {
        return ops.execute(
                a,
                "QUERY_PRINT_RESULT",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var j = job(no, r.expectedRevision(), a);
                    var at = attempt(j, a);
                    at.setNextQueryAt(now());
                    at.setRevision(at.getRevision() + 1);
                    at.setUpdatedAt(now());
                    attempts.updateById(at);
                    return succeeded(
                            "QUERY_PRINT_RESULT",
                            "JOB",
                            no,
                            json.freeze(Map.of("scheduled", true, "jobNo", no)));
                });
    }

    public PrintOperation confirmManually(String no, ConfirmManuallyReq r, PrintActor a) {
        return ops.execute(
                a,
                "CONFIRM_PRINT_MANUALLY",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    reason(r.reason());
                    evidence(r.evidenceRef());
                    var j = job(no, r.expectedRevision(), a);
                    require(
                            !Set.of("COMPLETED", "FAILED", "CANCELLED").contains(j.getStatus()),
                            "print.jobTerminal");
                    var at = attempt(j, a);
                    require(
                            Set.of("PRINTED", "NOT_ACCEPTED").contains(r.outcome()),
                            "print.invalidOutcome");
                    require(
                            r.outcome().equals("PRINTED") || r.queueTerminatedConfirmed(),
                            "print.queueTerminationRequired");
                    var details =
                            json.freeze(
                                    Map.of(
                                            "queueTerminated",
                                            true,
                                            "partialOutput",
                                            false,
                                            "evidenceRef",
                                            r.evidenceRef(),
                                            "operator",
                                            a.actorNo()));
                    var ack =
                            receipts.ingest(
                                    new VerifiedPrintReceipt(
                                            scope(a),
                                            "MANUAL:" + a.actorNo(),
                                            a.idempotencyKey(),
                                            at.getAttemptNo(),
                                            r.outcome().equals("PRINTED")
                                                    ? ObservedPrintStatus.CONFIRMED
                                                    : ObservedPrintStatus.NOT_ACCEPTED,
                                            r.outcome().equals("PRINTED")
                                                    ? PrintEvidenceLevel.MANUAL_CONFIRMED
                                                    : PrintEvidenceLevel.NONE,
                                            null,
                                            Instant.now(),
                                            details));
                    receipts.applyNow(scope(a), ack.receiptNo());
                    return succeeded(
                            "CONFIRM_PRINT_MANUALLY",
                            "JOB",
                            no,
                            json.freeze(Map.of("jobNo", no, "evidenceRef", r.evidenceRef())));
                });
    }

    public PrintOperation releaseStale(String no, ReleaseStaleReq r, PrintActor a) {
        return ops.execute(
                a,
                "RELEASE_STALE",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    reason(r.reason());
                    var j = job(no, r.expectedRevision(), a);
                    require("STALE".equals(j.getStatus()), "print.jobNotStale");
                    var req =
                            requests.selectOne(
                                    PrintSupport.<PrnRequest>scoped(scope(a))
                                            .eq("request_no", j.getRequestNo()));
                    require(req != null, "print.requestMissing");
                    var ref = json.read(req.getSourceRef(), PrintFactRef.class);
                    var guard = sources.require(ref.sourceType()).executionGuard(ref);
                    require(
                            guard.allowed()
                                    && guard.currentBizRevision()
                                            .equals(r.expectedSourceRevision()),
                            "print.sourceRevisionConflict");
                    j.setStatus("PENDING");
                    j.setDeadlineAt(now().plusMinutes(10));
                    j.setFinishedAt(null);
                    save(j);
                    return succeeded("RELEASE_STALE", "JOB", no, json.freeze(Map.of("jobNo", no)));
                });
    }

    public PrintOperation resolveDeviceUncertainty(
            String no, ResolveDeviceUncertaintyReq r, PrintActor a) {
        return ops.execute(
                a,
                "RESOLVE_DEVICE_UNCERTAINTY",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    reason(r.reason());
                    evidence(r.evidenceRef());
                    require(
                            r.oldExecutorStopped() && "CONFIRM_QUEUE_TERMINATED".equals(r.action()),
                            "print.queueTerminationRequired");
                    var p =
                            printers.selectOne(
                                    PrintSupport.<PrnPrinter>scoped(scope(a)).eq("printer_no", no));
                    require(p != null, "print.notFound");
                    var runtime =
                            runtimes.selectOne(
                                    PrintSupport.<PrnPrinterRuntime>owned(scope(a))
                                            .eq("printer_no", no)
                                            .last("FOR UPDATE"));
                    revision(runtime, r.expectedRevision());
                    require(
                            r.activeAttemptNo() != null
                                    && r.activeAttemptNo().equals(runtime.getActiveAttemptNo()),
                            "print.attemptMismatch");
                    var j =
                            jobs.selectOne(
                                    PrintSupport.<PrnJob>scoped(scope(a))
                                            .eq("job_no", runtime.getActiveJobNo())
                                            .last("FOR UPDATE"));
                    require(
                            j != null
                                    && !Set.of("COMPLETED", "FAILED", "CANCELLED")
                                            .contains(j.getStatus()),
                            "print.jobTerminal");
                    j.setStatus("UNKNOWN");
                    j.setLastErrorCode("print.queueManuallyTerminated");
                    save(j);
                    leases.releaseIfCurrent(
                            scope(a), no, r.activeAttemptNo(), runtime.getFenceEpoch());
                    return succeeded(
                            "RESOLVE_DEVICE_UNCERTAINTY",
                            "PRINTER",
                            no,
                            json.freeze(
                                    Map.of(
                                            "printerNo",
                                            no,
                                            "originalJobStatus",
                                            "UNKNOWN",
                                            "evidenceRef",
                                            r.evidenceRef())));
                });
    }

    @Transactional
    public PrintMaintenanceReport applyControl(PrintControlFact fact) {
        audit.lock(fact.scope());
        int changed = 0, held = 0;
        for (var ref : fact.affectedFacts()) {
            require(
                    new PrintScope(ref.tenantNo(), ref.entityNo(), ref.storeNo())
                            .equals(fact.scope()),
                    "print.sourceMismatch");
            var guard = sources.require(ref.sourceType()).executionGuard(ref);
            if (guard.decision() == GuardDecision.ALLOW_HISTORICAL_RECEIPT) continue;
            var req =
                    requests.selectList(
                            PrintSupport.<PrnRequest>scoped(fact.scope())
                                    .eq("biz_no", ref.factNo()));
            for (var r : req) {
                for (var j :
                        jobs.selectList(
                                PrintSupport.<PrnJob>scoped(fact.scope())
                                        .eq("request_no", r.getRequestNo())
                                        .last("FOR UPDATE"))) {
                    if (guard.decision() == GuardDecision.CANCEL_UNSUBMITTED_EXECUTION
                            && Set.of("PENDING", "RETRY_WAIT", "STALE").contains(j.getStatus())) {
                        j.setStatus("CANCELLED");
                        j.setCancelRequestedAt(now());
                        save(j);
                        receipts.refreshBatchProjection(fact.scope(), j.getBatchNo());
                        changed++;
                    } else if (!Set.of("COMPLETED", "FAILED", "CANCELLED")
                            .contains(j.getStatus())) {
                        j.setStatus("UNKNOWN");
                        j.setLastErrorCode("print.sourceControlRequiresReview");
                        save(j);
                        held++;
                    }
                }
            }
        }
        return new PrintMaintenanceReport(
                fact.affectedFacts().size(), changed, held, 0, false, "Source control applied");
    }
}
