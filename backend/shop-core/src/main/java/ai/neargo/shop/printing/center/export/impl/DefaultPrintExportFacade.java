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
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class DefaultPrintExportFacade implements PrintExportFacade {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.event.OutboxEventBus artifactEvents;

    private final PrnExportTaskMapper exports;
    private final PrnSimulationRunMapper simulations;
    private final PrnBatchMapper batches;
    private final JobMapper jobs;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;
    private final PrintArtifactStorePort store;

    public DefaultPrintExportFacade(
            PrnExportTaskMapper e,
            PrnSimulationRunMapper s,
            PrnBatchMapper b,
            JobMapper j,
            PrintOperationService o,
            PrintJsonCodec c,
            PrintArtifactStorePort store) {
        exports = e;
        simulations = s;
        batches = b;
        jobs = j;
        ops = o;
        json = c;
        this.store = store;
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
                    if (source.type() == ExportSourceType.SIMULATION) {
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
                    String no = no("EX");
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
                                    Instant.now());
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
                    artifactEvents.publish(new PrintExportRequested(scope(a), no));
                    return new PrintOperation(
                            operationNo,
                            "CREATE_EXPORT",
                            PrintOperationStatus.SUCCEEDED,
                            "EXPORT",
                            no,
                            json.freeze(Map.of("exportNo", no)),
                            null,
                            Instant.now(),
                            Instant.now());
                });
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
        else
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
                e.getStatus(),
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
        var e = exports.selectOne(PrintSupport.<PrnExportTask>scoped(scope(a)).eq("export_no", no));
        require(e != null, "print.notFound");
        authorize(e, a);
        require(
                "READY".equals(e.getStatus()) && e.getExpiresAt().isAfter(now()),
                "print.exportNotReady");
        var verified = store.verify(e.getObjectKey());
        require(
                verified.exists()
                        && verified.sha256().equals(e.getContentHash())
                        && verified.size() == e.getFileSize(),
                "print.artifactIntegrityFailed");
        return store.signedDownload(e.getObjectKey(), Duration.ofSeconds(60));
    }
}
