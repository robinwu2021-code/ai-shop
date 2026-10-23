package ai.neargo.shop.print.service.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintQueryServiceImpl implements PrintQueryService {
    private final ai.neargo.shop.print.source.PrintSourceRegistry sources;
    private final PrnJobMapper jobs;
    private final PrnRequestMapper requests;
    private final PrnBatchMapper batches;
    private final PrnAttemptMapper attempts;
    private final PrnReceiptMapper receipts;
    private final PrnOperationMapper operations;
    private final PrnExportTaskMapper exports;
    private final PrintJsonCodec json;

    public PrintQueryServiceImpl(
            PrnJobMapper j,
            PrnRequestMapper r,
            PrnBatchMapper b,
            PrnAttemptMapper a,
            PrnReceiptMapper rc,
            PrnOperationMapper o,
            PrnExportTaskMapper e,
            PrintJsonCodec c,
            ai.neargo.shop.print.source.PrintSourceRegistry sources) {
        this.sources = sources;
        jobs = j;
        requests = r;
        batches = b;
        attempts = a;
        receipts = rc;
        operations = o;
        exports = e;
        json = c;
    }

    private PrnJob job(String no, PrintActor a) {
        var j = jobs.selectOne(PrintSupport.<PrnJob>scoped(scope(a)).eq("job_no", no));
        require(j != null, "print.notFound");
        return j;
    }

    public PrintBatchVO getBatch(String no, PrintActor a) {
        var b = batches.selectOne(PrintSupport.<PrnBatch>scoped(scope(a)).eq("batch_no", no));
        require(b != null, "print.notFound");
        return new PrintBatchVO(
                b.getBatchNo(),
                b.getRequestNo(),
                b.getStatus(),
                b.getExpectedCount(),
                b.getConfirmedCount(),
                b.getUnknownCount(),
                b.getFailedCount(),
                b.getResolvedCount(),
                b.getRevision());
    }

    public PageData<PrintJobVO> listJobs(ListJobsQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var wrapper =
                PrintSupport.<PrnJob>scoped(scope(a))
                        .eq(q.requestNo() != null, "request_no", q.requestNo())
                        .eq(q.batchNo() != null, "batch_no", q.batchNo())
                        .eq(q.scene() != null, "scene", q.scene())
                        .eq(q.status() != null, "status", q.status())
                        .eq(q.jobKind() != null, "job_kind", q.jobKind())
                        .eq(q.printerNo() != null, "printer_no", q.printerNo())
                        .ge(q.from() != null, "created_at", q.from())
                        .le(q.to() != null, "created_at", q.to());
        if (q.orderNo() != null) {
            var refs =
                    requests.selectList(
                            PrintSupport.<PrnRequest>scoped(scope(a))
                                    .and(
                                            w ->
                                                    w.eq("order_no", q.orderNo())
                                                            .or()
                                                            .eq("sub_order_no", q.orderNo()))
                                    .last("LIMIT 1001"));
            require(refs.size() <= 1000, "print.queryRangeTooLarge");
            if (refs.isEmpty())
                return PageData.empty(
                        pageNumber(q.page()), pageSize(q.size()));
            wrapper.in("request_no", refs.stream().map(PrnRequest::getRequestNo).toList());
        }
        var p =
                jobs.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        wrapper.orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream()
                        .map(
                                j ->
                                        PrintViews.job(
                                                j,
                                                a,
                                                requests.selectOne(
                                                        PrintSupport.<PrnRequest>scoped(scope(a))
                                                                .eq(
                                                                        "request_no",
                                                                        j.getRequestNo()))))
                        .toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public PrintJobView getJob(PrintJobRef ref, PrintActor a) {
        access(new PrintScope(ref.tenantNo(), ref.entityNo(), ref.storeNo()), a, Set.of());
        var j = job(ref.jobNo(), a);
        return new PrintJobView(
                PrintViews.job(
                        j,
                        a,
                        requests.selectOne(
                                PrintSupport.<PrnRequest>scoped(scope(a))
                                        .eq("request_no", j.getRequestNo()))),
                j.getCurrentAttemptNo(),
                j.getResultEvidence(),
                j.getLastErrorCode(),
                instant(j.getConfirmedAt()));
    }

    public PageData<PrintAttemptVO> listAttempts(String no, ListAttemptsQuery q, PrintActor a) {
        job(no, a);
        var p =
                attempts.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnAttempt>owned(scope(a))
                                .eq("job_no", no)
                                .orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream()
                        .map(
                                v ->
                                        new PrintAttemptVO(
                                                v.getAttemptNo(),
                                                v.getJobNo(),
                                                v.getStatus(),
                                                v.getProviderJobId(),
                                                v.getFenceEpoch(),
                                                instant(v.getSubmittedAt()),
                                                instant(v.getFinishedAt())))
                        .toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public PageData<PrintReceiptVO> listReceipts(String no, ListReceiptsQuery q, PrintActor a) {
        job(no, a);
        var ids =
                attempts
                        .selectList(PrintSupport.<PrnAttempt>owned(scope(a)).eq("job_no", no))
                        .stream()
                        .map(PrnAttempt::getAttemptNo)
                        .toList();
        if (ids.isEmpty()) return PageData.empty(1, 20);
        var p =
                receipts.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnReceipt>owned(scope(a))
                                .in("attempt_no", ids)
                                .orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream()
                        .map(
                                v ->
                                        new PrintReceiptVO(
                                                v.getReceiptNo(),
                                                v.getAttemptNo(),
                                                v.getReportedStatus(),
                                                v.getEvidenceLevel(),
                                                v.getStatus(),
                                                instant(v.getReceivedAt())))
                        .toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public DocumentPreviewVO getDocument(String no, PrintActor a) {
        var j = job(no, a);
        var doc = json.read(j.getDocumentSnapshot(), PrintDocument.class);
        access(scope(a), a, doc.requiredDataScopes());
        return new DocumentPreviewVO(
                no, doc, json.read(j.getLayoutSnapshot(), PrintLayout.class), j.getDocumentHash());
    }

    public OrderPrintSummaryVO getOrderSummary(String no, GetOrderSummaryQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var req =
                requests.selectList(
                        PrintSupport.<PrnRequest>scoped(scope(a))
                                .and(w -> w.eq("order_no", no).or().eq("sub_order_no", no))
                                .last("LIMIT 1001"));
        require(req.size() <= 1000, "print.queryRangeTooLarge");
        var list =
                req.isEmpty()
                        ? List.<PrnJob>of()
                        : jobs.selectList(
                                PrintSupport.<PrnJob>scoped(scope(a))
                                        .in(
                                                "request_no",
                                                req.stream().map(PrnRequest::getRequestNo).toList())
                                        .last("LIMIT 10001"));
        require(list.size() <= 10000, "print.queryRangeTooLarge");
        var originals =
                list.stream()
                        .filter(j -> "INITIAL".equals(j.getJobKind()) && j.getRequired() == 1)
                        .toList();
        String state =
                req.stream().anyMatch(r -> "BLOCKED".equals(r.getStatus()))
                        ? "BLOCKED"
                        : req.stream().anyMatch(r -> "PENDING".equals(r.getStatus()))
                                ? "PENDING"
                                : originals.isEmpty() ? "NO_PRINT_REQUIRED" : "GENERATED";
        var batchNos = list.stream().map(PrnJob::getBatchNo).distinct().toList();
        var jobNos = list.stream().map(PrnJob::getJobNo).toList();
        var ex =
                jobNos.isEmpty()
                        ? List.<PrnExportTask>of()
                        : exports.selectList(
                                PrintSupport.<PrnExportTask>scoped(scope(a))
                                        .and(
                                                w ->
                                                        w.in("source_no", jobNos)
                                                                .or()
                                                                .in("source_no", batchNos))
                                        .orderByDesc("id"));
        var latest = ex.isEmpty() ? null : ex.getFirst();
        return new OrderPrintSummaryVO(
                state,
                originals.size(),
                originals.stream().filter(j -> "COMPLETED".equals(j.getStatus())).count(),
                originals.stream().filter(j -> "RESOLVED".equals(j.getResolutionStatus())).count(),
                originals.stream().filter(j -> "UNKNOWN".equals(j.getStatus())).count(),
                originals.stream().filter(j -> "FAILED".equals(j.getStatus())).count(),
                ex.size(),
                latest == null ? null : latest.getExportNo(),
                latest == null ? null : latest.getStatus(),
                Instant.now(),
                sources.require("SUB_ORDER").referencesByOrder(scope(a), no));
    }

    public PrintOperation getOperation(String no, PrintActor a) {
        var o =
                operations.selectOne(
                        PrintSupport.<PrnOperation>scoped(scope(a)).eq("operation_no", no));
        require(o != null, "print.notFound");
        require(
                (a.actorType().equals(o.getActorType()) && a.actorNo().equals(o.getActorNo()))
                        || a.dataScopes().contains("biz:print:resolve")
                        || (a.actorType().equals("OPS")
                                && a.dataScopes().contains("ops:print:read")),
                "print.forbidden");
        return json.read(o.getResultRef(), PrintOperation.class);
    }
}
