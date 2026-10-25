package ai.neargo.shop.printing.center.dispatch.impl;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.dispatch.*;
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

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
public class PrintDispatchServiceImpl implements PrintDispatchService {
    private final PrnPrinterMapper printers;
    private final AttemptMapper attempts;
    private final JobMapper jobs;
    private final PrnRequestMapper requests;
    private final PrinterLeaseService leases;
    private final PrintAdapterRegistry adapters;
    private final PrintReceiptService receipts;
    private final PrintJsonCodec json;
    private final PrintSourceRegistry sources;
    private final TransactionTemplate tx;

    public PrintDispatchServiceImpl(
            PrnPrinterMapper p,
            AttemptMapper a,
            JobMapper j,
            PrnRequestMapper r,
            PrinterLeaseService l,
            PrintAdapterRegistry ar,
            PrintReceiptService rc,
            PrintJsonCodec c,
            PrintSourceRegistry s,
            @Qualifier("transactionManager") PlatformTransactionManager m) {
        printers = p;
        attempts = a;
        jobs = j;
        requests = r;
        leases = l;
        adapters = ar;
        receipts = rc;
        json = c;
        sources = s;
        tx = new TransactionTemplate(m);
    }

    public PrintWorkReport dispatchCloud(PrintWorkerBudget budget) {
        var list =
                printers.selectList(
                        new QueryWrapper<PrnPrinter>()
                                .eq("connection_type", "CLOUD")
                                .eq("status", "ACTIVE")
                                .exists(
                                        "SELECT 1 FROM prn_job j WHERE"
                                            + " j.tenant_no=prn_printer.tenant_no AND"
                                            + " j.entity_no=prn_printer.entity_no AND"
                                            + " j.store_no=prn_printer.store_no AND"
                                            + " j.printer_no=prn_printer.printer_no AND j.delivery_stage IN"
                                            + " ('PENDING','RETRY_WAIT') AND j.cancel_requested_at"
                                            + " IS NULL AND (j.next_retry_at IS NULL OR"
                                            + " j.next_retry_at<={0}) AND (j.deadline_at IS NULL OR"
                                            + " j.deadline_at>={0})",
                                        now())
                                .exists(
                                        "SELECT 1 FROM prn_printer_runtime rt WHERE"
                                                + " rt.tenant_no=prn_printer.tenant_no AND"
                                                + " rt.entity_no=prn_printer.entity_no AND"
                                                + " rt.printer_no=prn_printer.printer_no AND"
                                                + " rt.active_attempt_no IS NULL")
                                .last(
                                        "ORDER BY (SELECT rt.last_dispatch_at FROM"
                                            + " prn_printer_runtime rt WHERE"
                                            + " rt.tenant_no=prn_printer.tenant_no AND"
                                            + " rt.entity_no=prn_printer.entity_no AND"
                                            + " rt.printer_no=prn_printer.printer_no), id LIMIT "
                                                + budget.maxItems()));
        int claimed = 0, done = 0, failed = 0;
        long deadline = System.nanoTime() + budget.timeBudget().toNanos();
        for (var p : list) {
            if (System.nanoTime() >= deadline) break;
            var scope = new PrintScope(p.getTenantNo(), p.getEntityNo(), p.getStoreNo());
            var prepared = leases.prepare(scope, p.getPrinterNo(), budget.workerNo());
            if (prepared == null) continue;
            claimed++;
            var d = prepared.dispatch();
            var adapter = adapters.requireCloud(d.adapterCode());
            boolean canSend =
                    Boolean.TRUE.equals(
                            tx.execute(
                                    status -> {
                                        var j =
                                                jobs.selectOne(
                                                        PrintSupport.<PrnJob>scoped(scope)
                                                                .eq("job_no", d.jobNo())
                                                                .last("FOR UPDATE"));
                                        if (j == null
                                                || j.getCancelRequestedAt() != null
                                                || !"DISPATCHING".equals(j.getStatus())
                                                || !d.attemptNo().equals(j.getCurrentAttemptNo()))
                                            return false;
                                        var r =
                                                requests.selectOne(
                                                        PrintSupport.<PrnRequest>scoped(scope)
                                                                .eq(
                                                                        "request_no",
                                                                        j.getRequestNo()));
                                        if (!"PRINTER_TEST".equals(j.getScene())) {
                                            var ref =
                                                    json.read(r.getSourceRef(), PrintFactRef.class);
                                            if (!sources.require(ref.sourceType())
                                                    .executionGuard(ref)
                                                    .allowed()) return false;
                                        }
                                        attempts.update(
                                                null,
                                                PrintSupport.<PrnAttempt>ownedUpdate(scope)
                                                        .eq("attempt_no", d.attemptNo())
                                                        .eq("status", "PREPARED")
                                                        .set("status", "SUBMITTING")
                                                        .set("submitted_at", now())
                                                        .set("updated_at", now())
                                                        .setSql("version = version + 1"));
                                        return true;
                                    }));
            DispatchResult result;
            if (!canSend)
                result =
                        new DispatchResult(
                                DispatchOutcome.NOT_ACCEPTED,
                                null,
                                json.freeze(
                                        Map.of("queueTerminated", true, "partialOutput", false)),
                                "print.guardRejectedBeforeSubmit");
            else
                try {
                    result = adapter.submit(d);
                } catch (Exception ex) {
                    result =
                            new DispatchResult(
                                    DispatchOutcome.UNKNOWN,
                                    null,
                                    json.freeze(
                                            Map.of(
                                                    "queueTerminated",
                                                    false,
                                                    "partialOutput",
                                                    false)),
                                    "print.submitUnknown");
                }
            var outcome = result;
            tx.executeWithoutResult(
                    status -> {
                        if (outcome.providerJobId() != null)
                            attempts.update(
                                    null,
                                    PrintSupport.<PrnAttempt>ownedUpdate(scope)
                                            .eq("attempt_no", d.attemptNo())
                                            .set("provider_job_id", outcome.providerJobId())
                                            .set(
                                                    "provider_reference_hash",
                                                    PrintHashes.sha256(
                                                            d.providerScope()
                                                                    + ":"
                                                                    + outcome.providerJobId()))
                                            .set("next_query_at", now().plusSeconds(15))
                                            .set("updated_at", now())
                                            .setSql("version = version + 1"));
                        var observed =
                                !canSend
                                        ? ObservedPrintStatus.CANCELLED
                                        : outcome.outcome() == DispatchOutcome.ACCEPTED
                                                ? ObservedPrintStatus.ACCEPTED
                                                : outcome.outcome() == DispatchOutcome.NOT_ACCEPTED
                                                        ? ObservedPrintStatus.NOT_ACCEPTED
                                                        : ObservedPrintStatus.UNKNOWN;
                        receipts.ingest(
                                new VerifiedPrintReceipt(
                                        scope,
                                        PrintHashes.sha256("SUBMIT:" + d.providerScope()),
                                        d.dispatchKey(),
                                        d.attemptNo(),
                                        observed,
                                        PrintEvidenceLevel.NONE,
                                        null,
                                        Instant.now(),
                                        outcome.evidence()));
                    });
            if (result.outcome() == DispatchOutcome.UNKNOWN) failed++;
            else done++;
        }
        return new PrintWorkReport(claimed, done, failed, list.size() == budget.maxItems());
    }

    public void reconcileAttempt(PrintAttemptRef ref) {
        var a =
                attempts.selectOne(
                        PrintSupport.<PrnAttempt>owned(ref.scope())
                                .eq("attempt_no", ref.attemptNo()));
        require(
                a != null
                        && Objects.equals(ref.providerScope(), a.getProviderScope())
                        && Objects.equals(ref.dispatchKey(), a.getDispatchKey())
                        && Objects.equals(ref.credentialRef(), a.getCredentialRef()),
                "print.attemptMismatch");
        var job =
                jobs.selectOne(PrintSupport.<PrnJob>scoped(ref.scope()).eq("job_no", a.getJobNo()));
        require(job != null, "print.notFound");
        var adapterCode = json.tree(a.getConnectionSnapshot()).path("adapterCode").asText();
        var result = adapters.requireCloud(adapterCode).query(ref);
        var details = json.tree(result.evidence()).deepCopy();
        var map = new LinkedHashMap<String, Object>();
        map.put("evidence", details);
        map.put("queueTerminated", result.queueTerminated());
        map.put("partialOutput", result.partialOutput());
        receipts.ingest(
                new VerifiedPrintReceipt(
                        ref.scope(),
                        PrintHashes.sha256("QUERY:" + a.getProviderScope()),
                        no("QE"),
                        a.getAttemptNo(),
                        result.observedStatus(),
                        result.evidenceLevel(),
                        null,
                        Instant.now(),
                        json.freeze(map)));
        attempts.update(
                null,
                PrintSupport.<PrnAttempt>ownedUpdate(ref.scope())
                        .eq("attempt_no", ref.attemptNo())
                        .set("next_query_at", now().plusSeconds(30)));
    }
}
