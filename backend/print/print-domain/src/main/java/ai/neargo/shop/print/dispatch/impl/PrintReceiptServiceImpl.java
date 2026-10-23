package ai.neargo.shop.print.dispatch.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.audit.PrintActionAppender;
import ai.neargo.shop.print.dispatch.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
public class PrintReceiptServiceImpl implements PrintReceiptService {
    private final PrnRequestMapper requests;
    private final PrnReceiptMapper receipts;
    private final PrnAttemptMapper attempts;
    private final PrnJobMapper jobs;
    private final PrnBatchMapper batches;
    private final PrintStatePolicy policy;
    private final PrinterLeaseService leases;
    private final PrintActionAppender audit;
    private final PrintJsonCodec json;
    private final OutboxEventBus events;
    private final TransactionTemplate tx;

    public PrintReceiptServiceImpl(
            PrnReceiptMapper r,
            PrnAttemptMapper a,
            PrnJobMapper j,
            PrnBatchMapper b,
            PrintStatePolicy p,
            PrinterLeaseService l,
            PrintActionAppender au,
            PrintJsonCodec c,
            OutboxEventBus e,
            @Qualifier("transactionManager") PlatformTransactionManager manager,
            PrnRequestMapper requests) {
        this.requests = requests;
        receipts = r;
        attempts = a;
        jobs = j;
        batches = b;
        policy = p;
        leases = l;
        audit = au;
        json = c;
        events = e;
        tx = new TransactionTemplate(manager);
    }

    @Transactional
    public ReceiptAck ingest(VerifiedPrintReceipt r) {
        audit.lock(r.scope());
        var old =
                receipts.selectOne(
                        PrintSupport.<PrnReceipt>owned(r.scope())
                                .eq("source_scope", r.sourceScope())
                                .eq("source_receipt_key", r.sourceReceiptKey()));
        String hash = json.freeze(r).sha256();
        if (old != null) {
            require(old.getPayloadHash().equals(hash), "print.receiptConflict");
            return new ReceiptAck(old.getReceiptNo(), "DUPLICATE");
        }
        var attempt =
                attempts.selectOne(
                        PrintSupport.<PrnAttempt>owned(r.scope()).eq("attempt_no", r.attemptNo()));
        require(attempt != null, "print.attemptNotFound");
        var job =
                jobs.selectOne(
                        PrintSupport.<PrnJob>scoped(r.scope()).eq("job_no", attempt.getJobNo()));
        require(job != null, "print.notFound");
        var row = init(new PrnReceipt(), r.scope());
        row.setReceiptNo(no("RC"));
        row.setAttemptNo(r.attemptNo());
        row.setSourceType(r.sourceScope().startsWith("AGENT:") ? "AGENT" : "QUERY");
        row.setSourceScope(r.sourceScope());
        row.setSourceReceiptKey(r.sourceReceiptKey());
        row.setReportedStatus(r.status().name());
        row.setEvidenceLevel(r.evidenceLevel().name());
        row.setSourceSequence(r.sourceSequence());
        row.setSourceOccurredAt(LocalDateTime.ofInstant(r.occurredAt(), ZoneOffset.UTC));
        row.setReceivedAt(now());
        row.setPayloadHash(hash);
        row.setSanitizedPayload(r.sanitizedEvidence().canonicalJson());
        row.setStatus("PENDING");
        receipts.insert(row);
        return new ReceiptAck(row.getReceiptNo(), "ACK");
    }

    public PrintWorkReport applyPending(PrintWorkerBudget budget) {
        var ids =
                receipts.selectList(
                        new QueryWrapper<PrnReceipt>()
                                .eq("status", "PENDING")
                                .orderByAsc("id")
                                .last("LIMIT " + budget.maxItems()));
        int done = 0, failed = 0;
        for (var item : ids) {
            try {
                tx.executeWithoutResult(status -> apply(item.getId()));
                done++;
            } catch (Exception e) {
                failed++;
            }
        }
        return new PrintWorkReport(ids.size(), done, failed, ids.size() == budget.maxItems());
    }

    @Transactional
    public void applyNow(PrintScope scope, String no) {
        var r = receipts.selectOne(PrintSupport.<PrnReceipt>owned(scope).eq("receipt_no", no));
        require(r != null, "print.notFound");
        apply(r.getId());
    }

    private void apply(Long id) {
        var hint = receipts.selectById(id);
        if (hint == null || !"PENDING".equals(hint.getStatus())) return;
        var attempt =
                attempts.selectOne(
                        new QueryWrapper<PrnAttempt>()
                                .eq("tenant_no", hint.getTenantNo())
                                .eq("entity_no", hint.getEntityNo())
                                .eq("attempt_no", hint.getAttemptNo()));
        require(attempt != null, "print.attemptNotFound");
        var initial =
                jobs.selectOne(
                        new QueryWrapper<PrnJob>()
                                .eq("tenant_no", attempt.getTenantNo())
                                .eq("entity_no", attempt.getEntityNo())
                                .eq("job_no", attempt.getJobNo()));
        require(initial != null, "print.notFound");
        var scope =
                new PrintScope(initial.getTenantNo(), initial.getEntityNo(), initial.getStoreNo());
        audit.lock(scope);
        var receipt =
                receipts.selectOne(
                        PrintSupport.<PrnReceipt>owned(scope).eq("id", id).last("FOR UPDATE"));
        if (!"PENDING".equals(receipt.getStatus())) return;
        var job =
                jobs.selectOne(
                        PrintSupport.<PrnJob>scoped(scope)
                                .eq("job_no", initial.getJobNo())
                                .last("FOR UPDATE"));
        attempt =
                attempts.selectOne(
                        PrintSupport.<PrnAttempt>owned(scope)
                                .eq("attempt_no", attempt.getAttemptNo())
                                .last("FOR UPDATE"));
        var details = json.tree(receipt.getSanitizedPayload());
        if (Set.of("COMPLETED", "FAILED", "CANCELLED", "STALE").contains(job.getStatus())) {
            receipt.setStatus("IGNORED");
            receipt.setProcessError("TERMINAL_UNCHANGED");
        } else if (!Objects.equals(job.getCurrentAttemptNo(), attempt.getAttemptNo())) {
            receipt.setStatus("IGNORED");
            receipt.setProcessError("STALE_ATTEMPT");
        } else {
            boolean partial = details.path("partialOutput").asBoolean(false);
            boolean terminated = details.path("queueTerminated").asBoolean(false);
            var observed = ObservedPrintStatus.valueOf(receipt.getReportedStatus());
            var evidence = PrintEvidenceLevel.valueOf(receipt.getEvidenceLevel());
            if (receipt.getSourceType().equals("AGENT")
                    && evidence == PrintEvidenceLevel.PROVIDER_CONFIRMED)
                throw new PrintFailure("print.invalidEvidenceLevel");
            var before = job.getStatus();
            var transition =
                    policy.decide(
                            new PrintStateSnapshot(
                                    PrintJobStatus.valueOf(before),
                                    job.getCancelRequestedAt() != null,
                                    json.tree(
                                                            attempt.getResultSummary() == null
                                                                    ? "{}"
                                                                    : attempt.getResultSummary())
                                                    .path("partialOutput")
                                                    .asBoolean(false)
                                            && !(receipt.getSourceType().equals("QUERY")
                                                    && receipt.getSourceScope()
                                                            .startsWith("MANUAL:")
                                                    && terminated),
                                    job.getRetryCount(),
                                    job.getMaxRetryCount()),
                            new PrintObservation(observed, evidence, terminated, partial));
            job.setStatus(transition.status().name());
            job.setRevision(job.getRevision() + 1);
            job.setUpdatedAt(now());
            job.setLastErrorCode(transition.reason());
            if (transition.safeToRetry()) {
                job.setRetryCount(job.getRetryCount() + 1);
                job.setNextRetryAt(now().plusSeconds(Math.min(300, 5L << job.getRetryCount())));
            }
            if (transition.status() == PrintJobStatus.COMPLETED) {
                job.setConfirmedAt(now());
                job.setResultEvidence(evidence.name());
                job.setFinishedAt(now());
            } else if (Set.of(PrintJobStatus.FAILED, PrintJobStatus.CANCELLED)
                    .contains(transition.status())) job.setFinishedAt(now());
            jobs.updateById(job);
            attempt.setStatus(observed.name());
            attempt.setResultObservedAt(now());
            var prior =
                    json.tree(
                            attempt.getResultSummary() == null ? "{}" : attempt.getResultSummary());
            if (prior.path("partialOutput").asBoolean(false)
                    && !receipt.getSourceScope().startsWith("MANUAL:")) {
                var sticky = new java.util.TreeMap<String, Object>();
                details.properties().forEach(e -> sticky.put(e.getKey(), e.getValue()));
                sticky.put("partialOutput", true);
                attempt.setResultSummary(json.write(sticky));
            } else attempt.setResultSummary(receipt.getSanitizedPayload());
            attempt.setRevision(attempt.getRevision() + 1);
            attempt.setUpdatedAt(now());
            if (transition.releaseGate()) attempt.setFinishedAt(now());
            attempts.updateById(attempt);
            if (transition.releaseGate())
                leases.releaseIfCurrent(
                        scope, job.getPrinterNo(), attempt.getAttemptNo(), attempt.getFenceEpoch());
            receipt.setStatus("APPLIED");
            audit.appendInCurrentTransaction(
                    scope,
                    new PrintAction(
                            null,
                            "JOB",
                            job.getJobNo(),
                            "RECEIPT_APPLIED",
                            "SYSTEM",
                            "print-receipt",
                            job.getRevision() - 1,
                            job.getRevision(),
                            before,
                            job.getStatus(),
                            transition.reason(),
                            null));
            if (!before.equals("COMPLETED") && job.getStatus().equals("COMPLETED")) {
                events.publish(
                        new PrintTaskCompleted(
                                scope,
                                job.getJobNo(),
                                job.getRootJobNo(),
                                PrintJobKind.valueOf(job.getJobKind()),
                                evidence,
                                instant(job.getConfirmedAt())));
                if ("REPRINT".equals(job.getJobKind())) {
                    var root =
                            jobs.selectOne(
                                    PrintSupport.<PrnJob>scoped(scope)
                                            .eq("job_no", job.getRootJobNo())
                                            .last("FOR UPDATE"));
                    if (root != null && !"RESOLVED".equals(root.getResolutionStatus())) {
                        root.setResolutionStatus("RESOLVED");
                        root.setReplacementJobNo(job.getJobNo());
                        root.setRevision(root.getRevision() + 1);
                        root.setUpdatedAt(now());
                        jobs.updateById(root);
                        refreshBatch(scope, root.getBatchNo());
                    }
                }
            }
            refreshBatch(scope, job.getBatchNo());
        }
        receipt.setProcessedAt(now());
        receipt.setRevision(receipt.getRevision() + 1);
        receipt.setUpdatedAt(now());
        receipts.updateById(receipt);
    }

    private FrozenJson notificationPolicy(PrintScope scope, String requestNo) {
        var r =
                requests.selectOne(
                        PrintSupport.<PrnRequest>scoped(scope).eq("request_no", requestNo));
        if (r == null) return json.freeze(Map.of());
        var p = json.read(r.getPolicySnapshot(), PrnScenePolicy.class);
        return json.freeze(p.getNotificationPolicy() == null ? "{}" : p.getNotificationPolicy());
    }

    @Transactional
    public void refreshBatchProjection(PrintScope scope, String batchNo) {
        audit.lock(scope);
        refreshBatch(scope, batchNo);
    }

    private void refreshBatch(PrintScope scope, String no) {
        var b =
                batches.selectOne(
                        PrintSupport.<PrnBatch>scoped(scope).eq("batch_no", no).last("FOR UPDATE"));
        if (b == null) return;
        var required =
                jobs.selectList(
                        PrintSupport.<PrnJob>scoped(scope).eq("batch_no", no).eq("required", 1));
        int
                completed =
                        (int)
                                required.stream()
                                        .filter(j -> "COMPLETED".equals(j.getStatus()))
                                        .count(),
                failed =
                        (int) required.stream().filter(j -> "FAILED".equals(j.getStatus())).count(),
                unknown =
                        (int)
                                required.stream()
                                        .filter(j -> "UNKNOWN".equals(j.getStatus()))
                                        .count(),
                cancelled =
                        (int)
                                required.stream()
                                        .filter(j -> "CANCELLED".equals(j.getStatus()))
                                        .count(),
                resolved =
                        (int)
                                required.stream()
                                        .filter(
                                                j ->
                                                        !"COMPLETED".equals(j.getStatus())
                                                                && "RESOLVED"
                                                                        .equals(
                                                                                j
                                                                                        .getResolutionStatus()))
                                        .count();
        boolean nowResolved = !required.isEmpty() && completed + resolved == required.size();
        boolean wasResolved = "RESOLVED".equals(b.getResolvedStatus());
        b.setConfirmedCount(completed);
        b.setFailedCount(failed);
        b.setUnknownCount(unknown);
        b.setCancelledCount(cancelled);
        b.setResolvedCount(resolved);
        b.setStatus(
                completed == required.size() && !required.isEmpty()
                        ? "COMPLETED"
                        : unknown + failed > 0
                                ? "ATTENTION"
                                : completed > 0 ? "PARTIAL" : "PROCESSING");
        if (nowResolved) {
            b.setResolvedStatus("RESOLVED");
            b.setResolvedAt(now());
        }
        b.setRevision(b.getRevision() + 1);
        b.setUpdatedAt(now());
        batches.updateById(b);
        if (nowResolved && !wasResolved)
            events.publish(
                    new PrintBatchResolved(
                            scope,
                            no,
                            resolved > 0 ? "REPLACEMENT_RESOLVED" : "RESOLVED",
                            Instant.now(),
                            b.getBatchKind(),
                            notificationPolicy(scope, b.getRequestNo())));
    }
}
