package ai.neargo.shop.print.dispatch.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.audit.PrintActionAppender;
import ai.neargo.shop.print.dispatch.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;
import ai.neargo.shop.print.layout.*;
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
public class PrinterLeaseServiceImpl implements PrinterLeaseService {
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final PrnJobMapper jobs;
    private final PrnAttemptMapper attempts;
    private final PrnRequestMapper requests;
    private final PrintSourceRegistry sources;
    private final PrintJsonCodec json;
    private final PrintActionAppender audit;

    public PrinterLeaseServiceImpl(
            PrnPrinterMapper p,
            PrnPrinterRuntimeMapper r,
            PrnJobMapper j,
            PrnAttemptMapper a,
            PrnRequestMapper req,
            PrintSourceRegistry s,
            PrintJsonCodec c,
            PrintActionAppender audit) {
        printers = p;
        runtimes = r;
        jobs = j;
        attempts = a;
        requests = req;
        sources = s;
        json = c;
        this.audit = audit;
    }

    @Transactional
    public PreparedPrintAttempt prepare(PrintScope scope, String printerNo, String worker) {
        audit.lock(scope);
        var p =
                printers.selectOne(
                        PrintSupport.<PrnPrinter>scoped(scope).eq("printer_no", printerNo));
        if (p == null || !"ACTIVE".equals(p.getStatus())) return null;
        var runtime =
                runtimes.selectOne(
                        PrintSupport.<PrnPrinterRuntime>owned(scope)
                                .eq("printer_no", printerNo)
                                .last("FOR UPDATE"));
        require(runtime != null, "print.runtimeMissing");
        if (runtime.getActiveAttemptNo() != null) return null;
        var job =
                jobs.selectOne(
                        PrintSupport.<PrnJob>scoped(scope)
                                .eq("printer_no", printerNo)
                                .isNull("cancel_requested_at")
                                .in("status", List.of("PENDING", "RETRY_WAIT"))
                                .and(q -> q.isNull("next_retry_at").or().le("next_retry_at", now()))
                                .orderByDesc("priority")
                                .orderByAsc("printer_sequence")
                                .last("LIMIT 1 FOR UPDATE"));
        if (job == null) return null;
        if (job.getDeadlineAt() != null && job.getDeadlineAt().isBefore(now())) {
            job.setStatus("STALE");
            job.setFinishedAt(now());
            job.setRevision(job.getRevision() + 1);
            job.setUpdatedAt(now());
            jobs.updateById(job);
            return null;
        }
        var request =
                requests.selectOne(
                        PrintSupport.<PrnRequest>scoped(scope)
                                .eq("request_no", job.getRequestNo()));
        require(request != null, "print.requestMissing");
        if (!"PRINTER_TEST".equals(job.getScene())) {
            var ref = json.read(request.getSourceRef(), PrintFactRef.class);
            var guard = sources.require(ref.sourceType()).executionGuard(ref);
            if (!guard.allowed()) {
                job.setStatus(
                        guard.decision() == GuardDecision.CANCEL_UNSUBMITTED_EXECUTION
                                ? "CANCELLED"
                                : "STALE");
                job.setLastErrorCode("print.sourceExecutionHeld");
                job.setRevision(job.getRevision() + 1);
                job.setUpdatedAt(now());
                jobs.updateById(job);
                return null;
            }
        }
        var attempt = init(new PrnAttempt(), scope);
        attempt.setAttemptNo(no("AT"));
        attempt.setJobNo(job.getJobNo());
        attempt.setPrinterNo(printerNo);
        attempt.setAttemptIndex(
                attempts.selectCount(
                                        PrintSupport.<PrnAttempt>owned(scope)
                                                .eq("job_no", job.getJobNo()))
                                .intValue()
                        + 1);
        attempt.setDispatchKey(no("DP"));
        attempt.setAgentNo(p.getAgentNo());
        attempt.setFenceEpoch(runtime.getFenceEpoch() + 1);
        attempt.setConnectionRevision(p.getConnectionRevision());
        attempt.setCredentialRef(p.getCredentialRef());
        attempt.setAdapterVersion(p.getAdapterCode() + ":1");
        attempt.setProviderScope(
                PrintHashes.sha256(p.getPhysicalKey() + ":" + p.getVendorAccountRef()));
        var connection = new LinkedHashMap<String, Object>();
        connection.put("adapterCode", p.getAdapterCode());
        connection.put("deviceSn", p.getDeviceSn());
        connection.put("vendorAccountRef", p.getVendorAccountRef());
        connection.put("agentNo", p.getAgentNo());
        connection.put("config", json.tree(p.getConnectionConfig()));
        attempt.setConnectionSnapshot(json.write(connection));
        attempt.setStatus("PREPARED");
        attempt.setRequestHash(job.getDocumentHash());
        attempt.setNextQueryAt(now().plusSeconds(30));
        attempts.insert(attempt);
        String token = no("LS");
        runtime.setActiveJobNo(job.getJobNo());
        runtime.setActiveAttemptNo(attempt.getAttemptNo());
        runtime.setFenceEpoch(attempt.getFenceEpoch());
        runtime.setLeaseOwner(worker);
        runtime.setLeaseTokenHash(PrintHashes.sha256(token));
        runtime.setLeaseUntil(now().plusSeconds(60));
        runtime.setBlockedReason("IN_FLIGHT");
        runtime.setLastDispatchAt(now());
        runtime.setRevision(runtime.getRevision() + 1);
        runtime.setUpdatedAt(now());
        runtimes.updateById(runtime);
        job.setStatus("DISPATCHING");
        job.setCurrentAttemptNo(attempt.getAttemptNo());
        job.setRevision(job.getRevision() + 1);
        job.setUpdatedAt(now());
        jobs.updateById(job);
        var dispatch =
                new PrintDispatch(
                        scope,
                        job.getJobNo(),
                        attempt.getAttemptNo(),
                        attempt.getDispatchKey(),
                        printerNo,
                        attempt.getFenceEpoch(),
                        p.getConnectionRevision(),
                        p.getCredentialRef(),
                        p.getAdapterCode(),
                        attempt.getProviderScope(),
                        json.freeze(connection),
                        json.read(job.getLayoutSnapshot(), PrintLayout.class));
        return new PreparedPrintAttempt(
                dispatch, p.getConnectionType(), token, instant(runtime.getLeaseUntil()));
    }

    @Transactional
    public void releaseIfCurrent(PrintScope scope, String printerNo, String attemptNo, long epoch) {
        runtimes.update(
                null,
                PrintSupport.<PrnPrinterRuntime>ownedUpdate(scope)
                        .eq("printer_no", printerNo)
                        .eq("active_attempt_no", attemptNo)
                        .eq("fence_epoch", epoch)
                        .set("active_job_no", null)
                        .set("active_attempt_no", null)
                        .set("lease_owner", null)
                        .set("lease_token_hash", null)
                        .set("lease_until", null)
                        .set("blocked_reason", null)
                        .set("updated_at", now())
                        .setSql("revision = revision + 1"));
    }
}
