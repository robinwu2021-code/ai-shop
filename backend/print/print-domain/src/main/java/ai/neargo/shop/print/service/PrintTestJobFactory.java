package ai.neargo.shop.print.service;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class PrintTestJobFactory {
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final PrnRequestMapper requests;
    private final PrnBatchMapper batches;
    private final PrnJobMapper jobs;
    private final PrintSceneRegistry scenes;
    private final PrintTemplateResolver templates;
    private final PrintDocumentCompiler compiler;
    private final PrintLayoutEngine layout;
    private final LayoutProfileRegistry profiles;
    private final PrintJsonCodec json;

    public PrintTestJobFactory(
            PrnPrinterMapper p,
            PrnPrinterRuntimeMapper rt,
            PrnRequestMapper r,
            PrnBatchMapper b,
            PrnJobMapper j,
            PrintSceneRegistry s,
            PrintTemplateResolver t,
            PrintDocumentCompiler c,
            PrintLayoutEngine l,
            LayoutProfileRegistry profiles,
            PrintJsonCodec json) {
        printers = p;
        runtimes = rt;
        requests = r;
        batches = b;
        jobs = j;
        scenes = s;
        templates = t;
        compiler = c;
        layout = l;
        this.profiles = profiles;
        this.json = json;
    }

    @Transactional
    public PrintSubmission create(String printerNo, Long expected, String pattern, PrintActor a) {
        require(Set.of("STANDARD", "ALIGNMENT").contains(pattern), "print.testPatternRejected");
        var scope = scope(a);
        var printer =
                printers.selectOne(
                        PrintSupport.<PrnPrinter>scoped(scope).eq("printer_no", printerNo));
        revision(printer, expected);
        require("ACTIVE".equals(printer.getStatus()), "print.printerUnavailable");
        var scene = scenes.require("PRINTER_TEST");
        var sample = (tools.jackson.databind.node.ObjectNode) scene.example().deepCopy();
        ((tools.jackson.databind.node.ObjectNode) sample.path("fields"))
                .put("paperWidthMm", printer.getPaperWidthMm());
        var payload = json.freeze(sample);
        String jobNo = no("PJ");
        var ref =
                new PrintFactRef(
                        a.tenantNo(),
                        a.entityNo(),
                        a.storeNo(),
                        scene.definition().path("sourceTypes").get(0).asText(),
                        jobNo,
                        "1",
                        "PRINTER_TEST",
                        payload.sha256());
        var fact =
                new PrintFact(
                        ref,
                        null,
                        null,
                        "DEVICE_TEST",
                        a.idempotencyKey(),
                        Instant.now(),
                        1,
                        payload,
                        Printability.READY,
                        null,
                        List.of());
        var selection =
                new TemplateSelection(
                        TemplateSelectionMode.SCENE_DEFAULT,
                        "STANDARD_" + printer.getPaperWidthMm(),
                        null);
        var template =
                templates.resolve(
                        new TemplateResolutionCommand(
                                scope,
                                "PRINTER_TEST",
                                1,
                                selection.variantCode(),
                                selection,
                                ResolutionPurpose.PRODUCTION,
                                a.dataScopes()));
        var doc =
                compiler.compile(
                        fact,
                        template,
                        new SystemPrintSlots(
                                jobNo,
                                PrintJobKind.TEST,
                                1,
                                Instant.now(),
                                null,
                                null,
                                Set.of("打印机测试，不代表业务出票"),
                                null));
        var profile = profiles.require("ROLL" + printer.getPaperWidthMm(), "1");
        var frozen = layout.layout(doc, profile);
        var r = init(new PrnRequest(), scope);
        r.setRequestNo(no("PR"));
        r.setStoreNo(a.storeNo());
        r.setBizType("PRINTER_TEST");
        r.setBizNo(printerNo);
        r.setBizRevision("1");
        r.setScene("PRINTER_TEST");
        r.setTriggerType("DEVICE_TEST");
        r.setSourceRef(json.write(ref));
        r.setTriggerKey(PrintHashes.sha256("TEST:" + a.actorNo() + ":" + a.idempotencyKey()));
        r.setSourceOccurredAt(now());
        r.setPolicyVersionNo("TEST");
        r.setPolicySnapshot("{}");
        r.setOutputOptions(
                json.write(new PrintOutputOptions(false, PdfOutputProfile.ORIGINAL_ROLL)));
        r.setSourceSnapshot(payload.canonicalJson());
        r.setSourceSnapshotHash(payload.sha256());
        r.setStatus("GENERATED");
        requests.insert(r);
        var b = init(new PrnBatch(), scope);
        b.setBatchNo(no("PB"));
        b.setRequestNo(r.getRequestNo());
        b.setStoreNo(a.storeNo());
        b.setBatchKind("INITIAL");
        b.setGenerationKey("INITIAL");
        b.setStatus("PENDING");
        b.setExpectedCount(1);
        b.setConfirmedCount(0);
        b.setFailedCount(0);
        b.setUnknownCount(0);
        b.setCancelledCount(0);
        b.setResolvedCount(0);
        b.setResolvedStatus("OPEN");
        b.setCreatedBy(a.actorNo());
        batches.insert(b);
        var rt =
                runtimes.selectOne(
                        PrintSupport.<PrnPrinterRuntime>owned(scope)
                                .eq("printer_no", printerNo)
                                .last("FOR UPDATE"));
        require(rt != null, "print.runtimeMissing");
        rt.setLastSequence(rt.getLastSequence() + 1);
        rt.setRevision(rt.getRevision() + 1);
        rt.setUpdatedAt(now());
        runtimes.updateById(rt);
        var job = init(new PrnJob(), scope);
        job.setJobNo(jobNo);
        job.setRequestNo(r.getRequestNo());
        job.setBatchNo(b.getBatchNo());
        job.setStoreNo(a.storeNo());
        job.setScene("PRINTER_TEST");
        job.setDocumentKey(jobNo);
        job.setRouteRuleKey("DEVICE_TEST");
        job.setTargetKey(printerNo);
        job.setCopyIndex(1);
        job.setRequired(1);
        job.setPrinterNo(printerNo);
        job.setRendererVersion("PRINT_DOC_V1");
        job.setLayoutProfileSnapshot(json.write(profile));
        job.setLayoutSnapshot(json.write(frozen));
        job.setTemplateResolutionSnapshot(json.write(template));
        job.setPayloadSnapshot(payload.canonicalJson());
        job.setDocumentSnapshot(json.write(doc));
        job.setDocumentHash(json.freeze(doc).sha256());
        job.setRouteSnapshot(json.write(Map.of("printerNo", printerNo)));
        job.setStatus("PENDING");
        job.setPriority(0);
        job.setPrinterSequence(rt.getLastSequence());
        job.setRetryCount(0);
        job.setMaxRetryCount(0);
        job.setDeadlineAt(now().plusMinutes(10));
        job.setRootJobNo(jobNo);
        job.setJobKind("TEST");
        job.setResolutionStatus("OPEN");
        jobs.insert(job);
        return new PrintSubmission(
                r.getRequestNo(), List.of(b.getBatchNo()), List.of(jobNo), null, List.of());
    }
}
