package ai.neargo.shop.printing.center.export.impl;

import ai.neargo.shop.spi.printing.*;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.operation.PrintOperationService;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.simulation.SimulationModels.SimulationInput;
import ai.neargo.shop.printing.center.source.PrintSourceRegistry;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;
import ai.neargo.shop.event.AfterCommit;
import ai.neargo.shop.printing.center.export.PrintExportWorker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.*;
import java.util.*;

@Service
public class DefaultPrintExportFacade implements PrintExportFacade {

    @Autowired private ObjectProvider<PrintExportWorker> artifactWorker;
    @Autowired @Qualifier("transactionManager") private PlatformTransactionManager transactionManager;

    private final PrnExportTaskMapper exports;
    private final PrnSimulationRunMapper simulations;
    private final PrnBatchMapper batches;
    private final JobMapper jobs;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;
    private final PrintArtifactStorePort store;
    private final PrintPreviewFacts previewFacts;
    private final PrintSourceRegistry sources;
    private final PrintTemplateResolver templates;
    private final PrintDocumentCompiler compiler;
    private final PrintLayoutEngine layoutEngine;
    private final LayoutProfileRegistry profiles;

    public DefaultPrintExportFacade(
            PrnExportTaskMapper e,
            PrnSimulationRunMapper s,
            PrnBatchMapper b,
            JobMapper j,
            PrintOperationService o,
            PrintJsonCodec c,
            PrintArtifactStorePort store,
            PrintPreviewFacts previewFacts,
            PrintSourceRegistry sources,
            PrintTemplateResolver templates,
            PrintDocumentCompiler compiler,
            PrintLayoutEngine layoutEngine,
            LayoutProfileRegistry profiles) {
        exports = e;
        simulations = s;
        batches = b;
        jobs = j;
        ops = o;
        json = c;
        this.store = store;
        this.previewFacts = previewFacts;
        this.sources = sources;
        this.templates = templates;
        this.compiler = compiler;
        this.layoutEngine = layoutEngine;
        this.profiles = profiles;
    }

    public PrintOperation create(ExportCommand c, PrintActor a) {
        return ops.execute(
                a,
                "CREATE_EXPORT",
                json.freeze(c).sha256(),
                () -> {
                    access(c.scope(), a, Set.of());
                    require(c.profile() != null, "print.profileRequired");
                    var layouts = new ArrayList<PrintLayout>();
                    var required = new HashSet<String>();
                    var source = c.source();
                    String no = no("EX");
                    var createdAt = Instant.now();
                    PdfRenderInput.InvoiceDocumentProvenance invoiceDocument = null;
                    if (source.type().isInvoiceDocument()) {
                        require(c.profile() == PdfOutputProfile.ORIGINAL_ROLL, "print.profileRequired");
                        required.add("biz:print:export");
                        required.addAll(PrintSourceDataScopes.required("INVOICE"));
                        access(scope(a), a, required);
                        var reference = invoiceReference(source.type().name(), source.no());
                        var fact = previewFacts.get(reference.scene(), reference, a);
                        require(Long.toString(source.expectedRevision()).equals(fact.ref().bizRevision()),
                                "print.revisionConflict");
                        require(sources.require("INVOICE").executionGuard(fact.ref()).allowed(),
                                "print.sourceBlocked");

                        // The operation holds the store print lock, including for different callers.
                        var existing = exports.selectOne(PrintSupport.<PrnExportTask>scoped(scope(a))
                                .eq("source_type", source.type().name()).eq("source_no", source.no())
                                .eq("source_revision", source.expectedRevision())
                                .in("status", "PENDING", "GENERATING", "READY")
                                .gt("expires_at", now()).isNull("object_deleted_at")
                                .orderByDesc("id").last("LIMIT 1"));
                        if (existing != null) {
                            authorize(existing, a);
                            return exportOperation(no("PO"), existing.getExportNo(), createdAt);
                        }

                        var resolved = templates.resolve(new TemplateResolutionCommand(scope(a), reference.scene(),
                                fact.payloadSchemaVersion(), "STANDARD_58", null, ResolutionPurpose.PRODUCTION,
                                a.dataScopes()));
                        var doc = compiler.compile(fact, resolved, new SystemPrintSlots(no, PrintJobKind.INITIAL,
                                1, createdAt, null, null, Set.of(), null));
                        required.addAll(doc.requiredDataScopes());
                        layouts.add(layoutEngine.layout(doc, profiles.require("ROLL58", "1")));
                        invoiceDocument = new PdfRenderInput.InvoiceDocumentProvenance(fact.ref(),
                                resolved.resolutionSnapshot());
                    } else if (source.type() == ExportSourceType.SIMULATION) {
                        var sim =
                                simulations.selectOne(
                                        PrintSupport.<PrnSimulationRun>scoped(scope(a))
                                                .eq("simulation_no", source.no()));
                        require(sim != null, "print.notFound");
                        revision(sim, source.expectedRevision());
                        var input = json.read(sim.getDocumentSnapshots(), SimulationInput.class);
                        layouts.addAll(input.layouts());
                        required.addAll(input.requiredDataScopes());
                    } else {
                        List<PrnJob> children;
                        if (source.type() == ExportSourceType.JOB) {
                            var j =
                                    jobs.selectOne(
                                            PrintSupport.<PrnJob>scoped(scope(a))
                                                    .eq("job_no", source.no()));
                            require(j != null, "print.notFound");
                            revision(j, source.expectedRevision());
                            children = List.of(j);
                        } else {
                            var b =
                                    batches.selectOne(
                                            PrintSupport.<PrnBatch>scoped(scope(a))
                                                    .eq("batch_no", source.no()));
                            require(b != null, "print.notFound");
                            revision(b, source.expectedRevision());
                            children =
                                    jobs.selectList(
                                            PrintSupport.<PrnJob>scoped(scope(a))
                                                    .eq("batch_no", source.no())
                                                    .orderByAsc(
                                                            "document_key",
                                                            "target_key",
                                                            "copy_index"));
                        }
                        for (var j : children) {
                            require(j.getRichDocumentSnapshot()!=null,"print.legacyDocumentFormat");
                        var doc = json.read(j.getRichDocumentSnapshot(), PrintDocument.class);
                            required.addAll(doc.requiredDataScopes());
                            layouts.add(json.read(j.getLayoutSnapshot(), PrintLayout.class));
                        }
                    }
                    access(scope(a), a, required);
                    require(
                            !layouts.isEmpty()
                                    && layouts.size() <= 50
                                    && layouts.stream().mapToInt(l -> l.pages().size()).sum()
                                            <= 200,
                            "print.exportLimit");
                    String operationNo = no("PO");
                    var kind =
                            source.type() == ExportSourceType.SIMULATION
                                    ? PrintArtifactKind.SIMULATION_EXPORT
                                    : PrintArtifactKind.LIVE_EXPORT;
                    var input =
                            new PdfRenderInput(
                                    no,
                                    kind,
                                    c.profile(),
                                    layouts,
                                    "1",
                                    layouts.getFirst().fontBundleVersion(),
                                    createdAt,
                                    invoiceDocument);
                    var row = init(new PrnExportTask(), scope(a));
                    row.setExportNo(no);
                    row.setOperationNo(operationNo);
                    row.setStoreNo(a.storeNo());
                    row.setExportKey(
                            PrintHashes.sha256(
                                    a.actorType() + ":" + a.actorNo() + ":" + a.idempotencyKey()));
                    row.setSourceType(source.type().name());
                    row.setSourceNo(source.no());
                    row.setSourceRevision(source.expectedRevision());
                    row.setSourceSnapshot(json.write(input));
                    row.setOutputProfile(c.profile().name());
                    row.setArtifactKind(kind.name());
                    row.setDocumentHash(json.freeze(layouts).sha256());
                    row.setRendererVersion("1");
                    row.setFontBundleVersion(input.fontBundleVersion());
                    row.setRequiredDataScopes(json.write(required));
                    row.setStatus("PENDING");
                    row.setObjectKey(
                            a.tenantNo()
                                    + "/"
                                    + a.entityNo()
                                    + "/"
                                    + a.storeNo()
                                    + "/print/"
                                    + java.time.format.DateTimeFormatter.ofPattern("yyyyMM")
                                            .format(now())
                                    + "/"
                                    + no
                                    + ".pdf");
                    row.setContentType("application/pdf");
                    row.setRetryCount(0);
                    row.setGenerationEpoch(0L);
                    row.setNextRetryAt(now());
                    row.setExpiresAt(now().plusDays(7));
                    row.setCreatedBy(a.actorNo());
                    exports.insert(row);
                    AfterCommit.runOutsideTransaction("print-artifact "+no,transactionManager,
                            ()->artifactWorker.getObject().generateNext(new PrintWorkerBudget(1,Duration.ofSeconds(5),"print-direct")));

                    return exportOperation(operationNo, no, createdAt);
                });
    }

    private PrintOperation exportOperation(String operationNo, String exportNo, Instant createdAt) {
        return new PrintOperation(operationNo, "CREATE_EXPORT", PrintOperationStatus.SUCCEEDED, "EXPORT",
                exportNo, json.freeze(Map.of("exportNo", exportNo)), null, createdAt, Instant.now());
    }

    private static PrintSourceReference invoiceReference(String scene, String invoiceNo) {
        return new PrintSourceReference("INVOICE", invoiceNo, invoiceNo, null, scene);
    }

    private void authorize(PrnExportTask e, PrintActor a) {
        var required =
                new HashSet<>(Arrays.asList(json.read(e.getRequiredDataScopes(), String[].class)));
        access(new PrintScope(e.getTenantNo(), e.getEntityNo(), e.getStoreNo()), a, required);
        if ("SIMULATION".equals(e.getSourceType()))
            require(
                    simulations.selectCount(
                                    PrintSupport.<PrnSimulationRun>scoped(scope(a))
                                            .eq("simulation_no", e.getSourceNo()))
                            == 1,
                    "print.sourceUnavailable");
        else if ("JOB".equals(e.getSourceType()))
            require(
                    jobs.selectCount(
                                    PrintSupport.<PrnJob>scoped(scope(a))
                                            .eq("job_no", e.getSourceNo()))
                            == 1,
                    "print.sourceUnavailable");
        else if (ExportSourceType.valueOf(e.getSourceType()).isInvoiceDocument()) {
            var source = json.read(e.getSourceSnapshot(), PdfRenderInput.class).invoiceDocument();
            require(source != null, "print.sourceUnavailable");
            try {
                var actual = sources.require("INVOICE").resolveReference(
                        invoiceReference(e.getSourceType(), e.getSourceNo()), scope(a));
                require(actual.equals(source.fact()), "print.sourceUnavailable");
            } catch (PrintFailure failure) {
                throw new PrintFailure("print.sourceUnavailable");
            }
        } else
            require(
                    batches.selectCount(
                                    PrintSupport.<PrnBatch>scoped(scope(a))
                                            .eq("batch_no", e.getSourceNo()))
                            == 1,
                    "print.sourceUnavailable");
    }

    private ExportView view(PrnExportTask e) {
        return new ExportView(
                e.getExportNo(),
                e.getSourceType(),
                e.getSourceNo(),
                e.getExpiresAt().isAfter(now()) ? e.getStatus() : "EXPIRED",
                e.getFileSize() == null ? 0 : e.getFileSize(),
                e.getPageCount() == null ? 0 : e.getPageCount(),
                instant(e.getExpiresAt()),
                e.getRevision());
    }

    public PageData<ExportView> page(ExportQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var p =
                exports.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnExportTask>scoped(scope(a))
                                .eq(q.sourceType() != null, "source_type", q.sourceType())
                                .eq(q.sourceNo() != null, "source_no", q.sourceNo())
                                .eq(q.status() != null, "status", q.status())
                                .orderByDesc("id"));
        var visible = new ArrayList<ExportView>();
        for (var e : p.getRecords()) {
            try {
                authorize(e, a);
                visible.add(view(e));
            } catch (PrintFailure f) {
                if (!Set.of("print.forbidden", "print.fieldForbidden", "print.sourceUnavailable")
                        .contains(f.key())) throw f;
            }
        }
        return PageData.of(visible, p.getTotal(), p.getCurrent(), p.getSize());
    }

    public ExportView get(String no, PrintActor a) {
        var e = exports.selectOne(PrintSupport.<PrnExportTask>scoped(scope(a)).eq("export_no", no));
        require(e != null, "print.notFound");
        authorize(e, a);
        return view(e);
    }

    public DownloadGrant download(String no, PrintActor a) {
        var e = readyExport(no, a);
        var verified = store.verify(e.getObjectKey());
        require(
                verified.exists()
                        && verified.sha256().equals(e.getContentHash())
                        && verified.size() == e.getFileSize(),
                "print.artifactIntegrityFailed");
        return store.signedDownload(e.getObjectKey(), Duration.ofSeconds(60));
    }

    public byte[] readVerified(String no, PrintActor a) {
        var e = readyExport(no, a);
        var bytes = store.readBytes(e.getObjectKey());
        require(e.getFileSize() != null && bytes.length == e.getFileSize()
                        && PrintHashes.sha256(bytes).equals(e.getContentHash()),
                "print.artifactIntegrityFailed");
        return bytes;
    }

    private PrnExportTask readyExport(String no, PrintActor a) {
        var e = exports.selectOne(PrintSupport.<PrnExportTask>scoped(scope(a)).eq("export_no", no));
        require(e != null, "print.notFound");
        authorize(e, a);
        require(
                "READY".equals(e.getStatus()) && e.getExpiresAt().isAfter(now()),
                "print.exportNotReady");
        return e;
    }
}
