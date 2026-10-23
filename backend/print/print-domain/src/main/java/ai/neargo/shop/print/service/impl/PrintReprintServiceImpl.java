package ai.neargo.shop.print.service.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.operation.PrintOperationService;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintReprintServiceImpl implements PrintReprintService {
    private final PrnJobMapper jobs;
    private final PrnBatchMapper batches;
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;
    private final PrintSystemSlotPolicy slots;

    public PrintReprintServiceImpl(
            PrnJobMapper j,
            PrnBatchMapper b,
            PrnPrinterMapper p,
            PrnPrinterRuntimeMapper r,
            PrintOperationService o,
            PrintJsonCodec c,
            PrintSystemSlotPolicy s) {
        jobs = j;
        batches = b;
        printers = p;
        runtimes = r;
        ops = o;
        json = c;
        slots = s;
    }

    public PrintOperation reprint(String no, ReprintReq r, PrintActor a) {
        return ops.execute(
                a,
                "REPRINT",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () ->
                        createCopies(
                                List.of(new JobRevision(no, r.expectedRevision())),
                                r.targetPrinterNo(),
                                1,
                                r.reason(),
                                r.riskAcknowledged(),
                                false,
                                a));
    }

    public PrintOperation batchReprint(BatchReprintReq r, PrintActor a) {
        return ops.execute(
                a,
                "BATCH_REPRINT",
                json.freeze(r).sha256(),
                () -> {
                    require(a.storeNo().equals(r.storeNo()), "print.forbidden");
                    return createCopies(
                            r.jobs(),
                            r.targetPrinterNo(),
                            1,
                            r.reason(),
                            r.riskAcknowledged(),
                            false,
                            a);
                });
    }

    public PrintOperation extraCopies(String no, ExtraCopiesReq r, PrintActor a) {
        return ops.execute(
                a,
                "EXTRA_COPIES",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    require(
                            r.copies() != null && r.copies() >= 1 && r.copies() <= 10,
                            "print.copyLimit");
                    return createCopies(
                            List.of(new JobRevision(no, r.expectedRevision())),
                            r.targetPrinterNo(),
                            r.copies(),
                            r.reason(),
                            false,
                            true,
                            a);
                });
    }

    private PrintOperation createCopies(
            List<JobRevision> refs,
            String target,
            int copies,
            String reason,
            boolean risk,
            boolean extra,
            PrintActor a) {
        require(
                !refs.isEmpty()
                        && refs.size() <= 50
                        && refs.stream().map(JobRevision::jobNo).distinct().count() == refs.size(),
                "print.copyLimit");
        require(
                reason != null && !reason.isBlank() && reason.length() <= 60,
                "print.reasonRequired");
        var scope = scope(a);
        var batchByRequest = new LinkedHashMap<String, PrnBatch>();
        var created = new ArrayList<String>();
        var mappings = new LinkedHashMap<String, List<String>>();
        boolean duplicateRisk = false;
        for (var ref : refs.stream().sorted(Comparator.comparing(JobRevision::jobNo)).toList()) {
            var source =
                    jobs.selectOne(
                            PrintSupport.<PrnJob>scoped(scope)
                                    .eq("job_no", ref.jobNo())
                                    .last("FOR UPDATE"));
            revision(source, ref.expectedRevision());
            require(
                    extra
                            ? "COMPLETED".equals(source.getStatus())
                            : Set.of("COMPLETED", "FAILED", "UNKNOWN").contains(source.getStatus()),
                    "print.reprintNotAllowed");
            if ("UNKNOWN".equals(source.getStatus())) {
                require(
                        risk && a.dataScopes().contains("biz:print:resolve"),
                        "print.duplicateRiskAcknowledgementRequired");
                duplicateRisk = true;
            }
            var active =
                    jobs.selectCount(
                            PrintSupport.<PrnJob>scoped(scope)
                                    .eq("root_job_no", source.getRootJobNo())
                                    .eq("job_kind", "REPRINT")
                                    .in(
                                            "status",
                                            List.of(
                                                    "PENDING",
                                                    "DISPATCHING",
                                                    "ACCEPTED",
                                                    "PRINTING",
                                                    "RETRY_WAIT",
                                                    "UNKNOWN")));
            require(extra || active == 0, "print.reprintInProgress");
            String printerNo = target == null ? source.getPrinterNo() : target;
            var printer =
                    printers.selectOne(
                            PrintSupport.<PrnPrinter>scoped(scope).eq("printer_no", printerNo));
            require(
                    printer != null && !"RETIRED".equals(printer.getStatus()),
                    "print.printerUnavailable");
            var originalLayout = json.read(source.getLayoutSnapshot(), PrintLayout.class);
            require(
                    originalLayout.pageWidthMm().intValueExact() == printer.getPaperWidthMm(),
                    "print.paperMismatch");
            var document = json.read(source.getDocumentSnapshot(), PrintDocument.class);
            access(scope, a, document.requiredDataScopes());
            var batch = batchByRequest.get(source.getRequestNo());
            if (batch == null) {
                batch = init(new PrnBatch(), scope);
                batch.setBatchNo(no("PB"));
                batch.setRequestNo(source.getRequestNo());
                batch.setStoreNo(a.storeNo());
                batch.setBatchKind(extra ? "EXTRA" : "REPRINT");
                batch.setGenerationKey(no("CP"));
                batch.setParentBatchNo(source.getBatchNo());
                batch.setStatus("PENDING");
                batch.setExpectedCount(0);
                batch.setConfirmedCount(0);
                batch.setFailedCount(0);
                batch.setUnknownCount(0);
                batch.setCancelledCount(0);
                batch.setResolvedCount(0);
                batch.setResolvedStatus("OPEN");
                batch.setCreatedBy(a.actorNo());
                batches.insert(batch);
                batchByRequest.put(source.getRequestNo(), batch);
            }
            var resultNos = new ArrayList<String>();
            for (int copy = 1; copy <= copies; copy++) {
                var j = json.read(json.write(source), PrnJob.class);
                j.setId(null);
                j.setJobNo(no("PJ"));
                j.setBatchNo(batch.getBatchNo());
                j.setPrinterNo(printerNo);
                j.setTargetKey(printerNo);
                j.setParentJobNo(source.getJobNo());
                j.setJobKind(extra ? "EXTRA" : "REPRINT");
                j.setCopyIndex(copy);
                j.setStatus("PENDING");
                j.setResolutionStatus("OPEN");
                j.setReplacementJobNo(null);
                j.setCurrentAttemptNo(null);
                j.setResultEvidence(null);
                j.setConfirmedAt(null);
                j.setFinishedAt(null);
                j.setCancelRequestedAt(null);
                j.setLastErrorCode(null);
                j.setLastErrorSummary(null);
                j.setRetryCount(0);
                j.setNextRetryAt(null);
                j.setDeadlineAt(now().plusMinutes(10));
                init(j, scope);
                var warnings = new HashSet<>(document.systemSlots().warnings());
                warnings.add(extra ? "加印副本" : "补打副本");
                if (duplicateRisk) warnings.add("原票结果未明，存在重复出纸风险");
                var marks =
                        new SystemPrintSlots(
                                j.getJobNo(),
                                PrintJobKind.valueOf(j.getJobKind()),
                                copy,
                                Instant.now(),
                                source.getRootJobNo(),
                                reason,
                                warnings,
                                new OriginalTicketState(
                                        source.getJobNo(),
                                        source.getRevision(),
                                        PrintJobStatus.valueOf(source.getStatus()),
                                        Instant.now()));
                var newDoc =
                        new PrintDocument(
                                document.documentKey(),
                                document.scene(),
                                document.payloadSchemaVersion(),
                                document.businessData(),
                                marks,
                                document.requiredDataScopes());
                j.setDocumentSnapshot(json.write(newDoc));
                j.setDocumentHash(json.freeze(newDoc).sha256());
                j.setLayoutSnapshot(json.write(slots.instantiateCopy(originalLayout, marks)));
                var runtime =
                        runtimes.selectOne(
                                PrintSupport.<PrnPrinterRuntime>owned(scope)
                                        .eq("printer_no", printerNo)
                                        .last("FOR UPDATE"));
                require(runtime != null, "print.runtimeMissing");
                runtime.setLastSequence(runtime.getLastSequence() + 1);
                runtime.setRevision(runtime.getRevision() + 1);
                runtime.setUpdatedAt(now());
                runtimes.updateById(runtime);
                j.setPrinterSequence(runtime.getLastSequence());
                j.setRouteSnapshot(
                        json.write(
                                Map.of("printerNo", printerNo, "copiedFrom", source.getJobNo())));
                jobs.insert(j);
                created.add(j.getJobNo());
                resultNos.add(j.getJobNo());
                batch.setExpectedCount(batch.getExpectedCount() + j.getRequired());
            }
            mappings.put(source.getJobNo(), resultNos);
        }
        for (var b : batchByRequest.values()) {
            b.setRevision(b.getRevision() + 1);
            b.setUpdatedAt(now());
            batches.updateById(b);
        }
        var batchNos = batchByRequest.values().stream().map(PrnBatch::getBatchNo).toList();
        return succeeded(
                extra ? "EXTRA_COPIES" : "REPRINT",
                "BATCH",
                batchNos.getFirst(),
                json.freeze(
                        Map.of(
                                "batchNos",
                                batchNos,
                                "jobNos",
                                created,
                                "sourceJobs",
                                mappings,
                                "duplicateRisk",
                                duplicateRisk)));
    }
}
