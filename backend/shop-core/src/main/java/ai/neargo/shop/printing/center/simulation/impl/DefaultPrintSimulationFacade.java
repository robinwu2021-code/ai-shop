package ai.neargo.shop.printing.center.simulation.impl;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintSimulationDtos.*;
import ai.neargo.shop.printing.center.dispatch.PrintStateModels.*;
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

import java.time.*;
import java.util.*;

@Service
public class DefaultPrintSimulationFacade implements PrintSimulationFacade {
    @org.springframework.beans.factory.annotation.Autowired
    private ai.neargo.shop.event.OutboxEventBus artifactEvents;

    private final PrintExportFacade exports;
    private final PrnSimulationRunMapper simulations;
    private final JobMapper jobs;
    private final PrintSourceRegistry sources;
    private final PrintSceneRegistry scenes;
    private final PrintTemplateResolver templates;
    private final PrintDocumentCompiler compiler;
    private final PrintLayoutEngine layouts;
    private final LayoutProfileRegistry profiles;
    private final PrintJsonCodec json;
    private final PrintOperationService ops;

    public DefaultPrintSimulationFacade(
            PrnSimulationRunMapper s,
            JobMapper j,
            PrintSourceRegistry r,
            PrintSceneRegistry c,
            PrintTemplateResolver t,
            PrintDocumentCompiler compiler,
            PrintLayoutEngine l,
            LayoutProfileRegistry p,
            PrintJsonCodec codec,
            PrintOperationService o,
            PrintExportFacade exports) {
        this.exports = exports;
        simulations = s;
        jobs = j;
        sources = r;
        scenes = c;
        templates = t;
        this.compiler = compiler;
        layouts = l;
        profiles = p;
        json = codec;
        ops = o;
    }

    public PrintOperation create(SimulationCommand c, PrintActor a) {
        return ops.execute(
                a,
                "CREATE_SIMULATION",
                json.freeze(c).sha256(),
                () -> {
                    access(c.scope(), a, Set.of());
                    require(c.fixtureCode() != null, "print.fixtureRequired");
                    var scene = scenes.require(c.scene());
                    require(
                            scene.templates().containsKey(c.variantCode()),
                            "print.variantUnavailable");
                    var frozen = new ArrayList<PrintLayout>();
                    var fields = new HashSet<String>();
                    String no = no("SM");
                    String operationNo = no("PO");
                    if (c.source().type() == SimulationSourceType.JOB) {
                        var j =
                                jobs.selectOne(
                                        PrintSupport.<PrnJob>scoped(scope(a))
                                                .eq("job_no", c.source().no()));
                        require(j != null && j.getScene().equals(c.scene()), "print.notFound");
                        revision(j, c.source().expectedRevision());
                        require(j.getRichDocumentSnapshot()!=null,"print.legacyDocumentFormat");
                        var doc = json.read(j.getRichDocumentSnapshot(), PrintDocument.class);
                        access(scope(a), a, doc.requiredDataScopes());
                        fields.addAll(doc.requiredDataScopes());
                        frozen.add(json.read(j.getLayoutSnapshot(), PrintLayout.class));
                    } else {
                        PrintFact fact;
                        if (c.source().type() == SimulationSourceType.SAMPLE) {
                            var payload = json.freeze(scene.example());
                            var ref =
                                    new PrintFactRef(
                                            a.tenantNo(),
                                            a.entityNo(),
                                            a.storeNo(),
                                            scene.definition().path("sourceTypes").get(0).asText(),
                                            "SAMPLE",
                                            "1",
                                            c.scene(),
                                            payload.sha256());
                            fact =
                                    new PrintFact(
                                            ref,
                                            null,
                                            null,
                                            "SAMPLE",
                                            "SAMPLE",
                                            Instant.EPOCH,
                                            1,
                                            payload,
                                            Printability.READY,
                                            null,
                                            List.of());
                        } else {
                            var ref = c.source().factRef();
                            require(
                                    ref != null && ref.scene().equals(c.scene()),
                                    "print.sourceMismatch");
                            require(a.dataScopes().containsAll(PrintSourceDataScopes.required(ref.sourceType())),
                                    "print.sourceForbidden");
                            access(
                                    new PrintScope(ref.tenantNo(), ref.entityNo(), ref.storeNo()),
                                    a,
                                    Set.of());
                            fact = sources.require(ref.sourceType()).snapshot(ref);
                        }
                        var resolved =
                                templates.resolve(
                                        new TemplateResolutionCommand(
                                                scope(a),
                                                c.scene(),
                                                fact.payloadSchemaVersion(),
                                                c.variantCode(),
                                                c.templateSelection(),
                                                ResolutionPurpose.PRODUCTION,
                                                a.dataScopes()));
                        var doc =
                                compiler.compile(
                                        fact,
                                        resolved,
                                        new SystemPrintSlots(
                                                no,
                                                PrintJobKind.INITIAL,
                                                1,
                                                Instant.now(),
                                                null,
                                                null,
                                                Set.of("模拟预览，不代表真实出纸"),
                                                null));
                        access(scope(a), a, doc.requiredDataScopes());
                        fields.addAll(doc.requiredDataScopes());
                        frozen.add(
                                layouts.layout(
                                        doc,
                                        profiles.require(
                                                c.variantCode().endsWith("58")
                                                        ? "ROLL58"
                                                        : "ROLL80",
                                                "1")));
                    }
                    require(frozen.size() <= 10, "print.simulationLimit");
                    var input = json.freeze(new SimulationInput(frozen, fields));
                    var row = init(new PrnSimulationRun(), scope(a));
                    row.setSimulationNo(no);
                    row.setOperationNo(operationNo);
                    row.setStoreNo(a.storeNo());
                    row.setScene(c.scene());
                    row.setSourceType(c.source().type().name());
                    row.setSourceRef(json.write(c.source()));
                    row.setFixtureCode(c.fixtureCode().name());
                    row.setFixtureVersion("1");
                    row.setInputSnapshot(input.canonicalJson());
                    row.setInputHash(input.sha256());
                    row.setTemplateResolutionSnapshot("{}");
                    row.setDocumentSnapshots(input.canonicalJson());
                    row.setStatus("PENDING");
                    row.setExpectedResults("{}");
                    row.setActualResults("{}");
                    row.setEventJournal("[]");
                    row.setNextRunAt(now());
                    row.setCreatedBy(a.actorNo());
                    simulations.insert(row);
                    artifactEvents.publish(new PrintSimulationRequested(scope(a), no));
                    var exportNos = new ArrayList<String>();
                    if (c.outputOptions() != null && c.outputOptions().pdfEnabled()) {
                        var exported =
                                exports.create(
                                        new ExportCommand(
                                                scope(a),
                                                new ExportSource(
                                                        ExportSourceType.SIMULATION, no, 0),
                                                c.outputOptions().pdfProfile()),
                                        new PrintActor(
                                                a.tenantNo(),
                                                a.entityNo(),
                                                a.storeNo(),
                                                a.actorType(),
                                                a.actorNo(),
                                                "SIM_PDF:" + no,
                                                a.dataScopes()));
                        exportNos.add(exported.targetNo());
                    }
                    return new PrintOperation(
                            operationNo,
                            "CREATE_SIMULATION",
                            PrintOperationStatus.SUCCEEDED,
                            "SIMULATION",
                            no,
                            json.freeze(Map.of("simulationNo", no, "exportNos", exportNos)),
                            null,
                            Instant.now(),
                            Instant.now());
                });
    }

    public PageData<SimulationView> page(SimulationQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var p =
                simulations.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnSimulationRun>scoped(scope(a))
                                .eq(q.scene() != null, "scene", q.scene())
                                .eq(q.status() != null, "status", q.status())
                                .orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream().map(this::view).toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public SimulationView get(String no, PrintActor a) {
        var r =
                simulations.selectOne(
                        PrintSupport.<PrnSimulationRun>scoped(scope(a)).eq("simulation_no", no));
        require(r != null, "print.notFound");
        var input = json.read(r.getInputSnapshot(), SimulationInput.class);
        access(scope(a), a, input.requiredDataScopes());
        return view(r);
    }

    private SimulationView view(PrnSimulationRun r) {
        return new SimulationView(
                r.getSimulationNo(),
                r.getScene(),
                r.getStatus(),
                r.getFixtureCode(),
                json.freeze(r.getExpectedResults()),
                json.freeze(r.getActualResults()),
                json.freeze(r.getEventJournal()),
                r.getRevision());
    }
}
