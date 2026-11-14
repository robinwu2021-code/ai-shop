package ai.neargo.shop.printing.center.dispatch.impl;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.audit.PrintActionAppender;
import ai.neargo.shop.printing.center.dispatch.*;
import ai.neargo.shop.printing.center.dispatch.PrintStateModels.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.entity.PrnAttempt;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.mapper.PrintMappers.AttemptMapper;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.source.PrintSourceRegistry;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class PrinterLeaseServiceImpl implements PrinterLeaseService {
    private ai.neargo.shop.printing.service.PrintDeviceGate deviceGate;
    @org.springframework.beans.factory.annotation.Autowired
    public void setDeviceGate(ai.neargo.shop.printing.service.PrintDeviceGate gate) { this.deviceGate=gate; }
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final JobMapper jobs;
    private final AttemptMapper attempts;
    private final PrnRequestMapper requests;
    private final PrintSourceRegistry sources;
    private final PrintJsonCodec json;
    private final PrintActionAppender audit;

    public PrinterLeaseServiceImpl(
            PrnPrinterMapper p,
            PrnPrinterRuntimeMapper r,
            JobMapper j,
            AttemptMapper a,
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
        var canonicalDevice = deviceGate.lock(scope, printerNo);
        if (!deviceGate.available(canonicalDevice)) return null;
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
                                .in("delivery_stage", List.of("PENDING", "RETRY_WAIT"))
                                .and(q -> q.isNull("next_retry_at").or().le("next_retry_at", now()))
                                .orderByDesc("priority")
                                .orderByAsc("printer_sequence")
                                .last("LIMIT 1 FOR UPDATE"));
        if (job == null) return null;
        if (job.getDeadlineAt() != null && job.getDeadlineAt().isBefore(now())) {
            job.setStatus("STALE");
            job.setFinishedAt(now());

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
        attempt.setFenceEpoch(Math.max(runtime.getFenceEpoch(),canonicalDevice.getActiveFenceEpoch()==null?0:canonicalDevice.getActiveFenceEpoch()) + 1);
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
        attempt.setStoreNo(scope.storeNo());
        attempt.setSequenceNo(attempt.getAttemptIndex());
        attempt.setStartedAt(now());
        attempt.setLeaseToken(no("LT"));
        attempt.setBridgeActorNo(worker);
        attempt.setAuthorizationEpoch(canonicalDevice.getAuthorizationVersion());
        attempts.insert(attempt);
        deviceGate.occupy(canonicalDevice, job.getJobNo(), attempt.getAttemptNo(), attempt.getFenceEpoch());
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
        job.setExecutionReleasedAt(null);
        job.setStatus("DISPATCHING");
        job.setCurrentAttemptNo(attempt.getAttemptNo());

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
        deviceGate.releaseIfCurrent(scope,printerNo,attemptNo,epoch);
    }
}
