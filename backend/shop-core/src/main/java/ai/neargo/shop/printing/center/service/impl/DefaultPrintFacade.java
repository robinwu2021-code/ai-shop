package ai.neargo.shop.printing.center.service.impl;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.audit.PrintActionAppender;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.operation.PrintOperationService;
import ai.neargo.shop.printing.entity.PrnJob;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.mapper.PrintMappers.JobMapper;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.source.PrintSourceRegistry;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class DefaultPrintFacade
        implements PrintFacade, ai.neargo.shop.printing.center.service.PrintGenerationRecovery {
    private final PrintSourceRegistry sources;
    private final PrintSceneRegistry scenes;
    private final PrintJsonCodec json;
    private final PrintOperationService ops;
    private final PrintActionAppender audit;
    private final PrnRequestMapper requests;
    private final PrnBatchMapper batches;
    private final JobMapper jobs;
    private final PrnPolicyVersionMapper policyVersions;
    private final PrnScenePolicyMapper policies;
    private final PrnRouteMapper routes;
    private final PrnRouteVersionMapper routeVersions;
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final PrintRouteMatcher matcher;
    private final PrintTemplateResolver templates;
    private final PrintDocumentCompiler compiler;
    private final PrintLayoutEngine layout;
    private final LayoutProfileRegistry profiles;
    private final PrintQueryService query;
    private final PrintExportFacade exports;

    public DefaultPrintFacade(
            PrintSourceRegistry s,
            PrintSceneRegistry c,
            PrintJsonCodec j,
            PrintOperationService o,
            PrintActionAppender a,
            PrnRequestMapper r,
            PrnBatchMapper b,
            JobMapper jobs,
            PrnScenePolicyMapper p,
            PrnRouteMapper rt,
            PrnRouteVersionMapper rv,
            PrnPrinterMapper pr,
            PrnPrinterRuntimeMapper runtime,
            PrintRouteMatcher m,
            PrintTemplateResolver t,
            PrintDocumentCompiler compiler,
            PrintLayoutEngine l,
            LayoutProfileRegistry profiles,
            PrintQueryService q,
            PrintExportFacade exports,
            PrnPolicyVersionMapper policyVersions) {
        this.policyVersions = policyVersions;
        sources = s;
        scenes = c;
        json = j;
        ops = o;
        audit = a;
        requests = r;
        batches = b;
        this.jobs = jobs;
        policies = p;
        routes = rt;
        routeVersions = rv;
        printers = pr;
        runtimes = runtime;
        matcher = m;
        templates = t;
        this.compiler = compiler;
        layout = l;
        this.profiles = profiles;
        query = q;
        this.exports = exports;
    }

    public PrintOperation submitBySource(SubmitPrintRequestReq r, PrintActor a) {
        return ops.execute(
                a,
                "SUBMIT_PRINT",
                json.freeze(r).sha256(),
                () -> {
                    require(
                            a.storeNo().equals(r.storeNo()) && !"PRINTER_TEST".equals(r.scene()),
                            "print.forbidden");
                    require(a.dataScopes().contains("SOURCE_ORDER_READ"), "print.sourceForbidden");
                    require(
                            r.printMode() == PrintMode.EXISTING_FACT,
                            "print.sourceModeUnavailable");
                    var reference =
                            new PrintSourceReference(
                                    r.sourceRef().type(),
                                    r.sourceRef().no(),
                                    r.sourceRef().factNo(),
                                    r.sourceRef().revision(),
                                    r.scene());
                    var ref =
                            sources.require(reference.type()).resolveReference(reference, scope(a));
                    require(
                            ref.scene().equals(r.scene())
                                    && ref.bizRevision().equals(r.expectedSourceRevision()),
                            "print.sourceRevisionConflict");
                    var submission =
                            generate(
                                    new PrintIntent(ref, r.printMode(), r.outputOptions()),
                                    a,
                                    false);
                    return succeeded(
                            "SUBMIT_PRINT",
                            "REQUEST",
                            submission.requestNo(),
                            json.freeze(submission));
                });
    }

    @Transactional
    public PrintSubmission submit(PrintIntent intent, PrintActor actor) {
        var ref = intent.factRef();
        access(new PrintScope(ref.tenantNo(), ref.entityNo(), ref.storeNo()), actor, Set.of());
        audit.lock(scope(actor));
        return generate(intent, actor, false);
    }

    @Transactional
    public PrintSubmission onFact(PrintFactRef ref) {
        var actor =
                new PrintActor(
                        ref.tenantNo(),
                        ref.entityNo(),
                        ref.storeNo(),
                        "SYSTEM",
                        "print-fact-ready",
                        ref.factNo(),
                        Set.of("SOURCE_ORDER_READ"));
        audit.lock(scope(actor));
        return generate(new PrintIntent(ref, PrintMode.EXISTING_FACT, null), actor, true);
    }

    private PrintSubmission generate(PrintIntent intent, PrintActor actor, boolean automatic) {
        return generate(intent, actor, automatic, false);
    }

    private PrintSubmission generate(
            PrintIntent intent, PrintActor actor, boolean automatic, boolean recovery) {
        var ref = intent.factRef();
        var scope = scope(actor);
        access(new PrintScope(ref.tenantNo(), ref.entityNo(), ref.storeNo()), actor, Set.of());
        var source = sources.require(ref.sourceType());
        var fact = source.snapshot(ref);
        require(fact.ref().equals(ref), "print.snapshotMismatch");
        scenes.validateSource(fact);
        json.verify(fact.immutablePayload());
        String key =
                json.freeze(
                                List.of(
                                        ref.sourceType(),
                                        ref.factNo(),
                                        ref.bizRevision(),
                                        ref.scene(),
                                        fact.triggerType(),
                                        fact.triggerInstance()))
                        .sha256();
        var existing =
                requests.selectOne(PrintSupport.<PrnRequest>scoped(scope).eq("trigger_key", key));
        boolean reopenSkipped =
                existing != null && !automatic && "SKIPPED".equals(existing.getStatus());
        if (existing != null
                && ((!recovery && !reopenSkipped) || "GENERATED".equals(existing.getStatus())))
            return submission(existing, actor);
        var policy =
                policies.selectOne(
                        PrintSupport.<PrnScenePolicy>scoped(scope).eq("scene", ref.scene()));
        if (existing != null && !reopenSkipped)
            policy = json.read(existing.getPolicySnapshot(), PrnScenePolicy.class);
        if (automatic && existing == null && policy != null) {
            var historical =
                    policyVersions.selectOne(
                            PrintSupport.<PrnPolicyVersion>owned(scope)
                                    .eq("policy_no", policy.getPolicyNo())
                                    .le(
                                            "effective_from",
                                            LocalDateTime.ofInstant(
                                                    fact.occurredAt(), ZoneOffset.UTC))
                                    .orderByDesc("effective_from")
                                    .last("LIMIT 1"));
            policy =
                    historical == null
                            ? null
                            : json.read(historical.getPolicySnapshot(), PrnScenePolicy.class);
        }
        var request = existing == null ? init(new PrnRequest(), scope) : existing;
        if (existing == null) request.setRequestNo(no("PR"));
        request.setStoreNo(scope.storeNo());
        request.setOrderNo(fact.orderNo());
        request.setSubOrderNo(fact.subOrderNo());
        request.setBizType(ref.sourceType());
        request.setBizNo(ref.factNo());
        request.setBizRevision(ref.bizRevision());
        request.setScene(ref.scene());
        request.setTriggerType(fact.triggerType());
        request.setSourceRef(json.write(ref));
        request.setTriggerKey(key);
        request.setSourceOccurredAt(LocalDateTime.ofInstant(fact.occurredAt(), ZoneOffset.UTC));
        request.setSourceSnapshot(fact.immutablePayload().canonicalJson());
        request.setSourceSnapshotHash(ref.snapshotHash());
        request.setPolicyVersionNo(policy == null ? "DISABLED" : policy.getCurrentVersionNo());
        request.setPolicySnapshot(policy == null ? "{}" : json.write(policy));
        var output =
                existing != null && !reopenSkipped
                        ? json.read(existing.getOutputOptions(), PrintOutputOptions.class)
                        : intent.outputOptions() != null
                                ? intent.outputOptions()
                                : policy == null
                                        ? new PrintOutputOptions(
                                                false, PdfOutputProfile.ORIGINAL_ROLL)
                                        : json.read(
                                                policy.getOutputOptions(),
                                                PrintOutputOptions.class);
        request.setOutputOptions(json.write(output));
        request.setStatus("PENDING");
        if (policy == null || policy.getEnabled() != 1) {
            require(automatic, "print.sceneDisabled");
            request.setStatus("SKIPPED");
            saveRequest(request);
            return submission(request, actor);
        }
        if (automatic
                && !java.util.Arrays.asList(json.read(policy.getTriggerTypes(), String[].class))
                        .contains(fact.triggerType())) {
            request.setStatus("SKIPPED");
            request.setBlockCode("print.triggerNotEnabled");
            saveRequest(request);
            return submission(request, actor);
        }
        request.setDeadlineAt(now().plusSeconds(policy.getStaleAfterSeconds()));
        var generated = new ArrayList<PrnJob>();
        PrnRoute route = null;
        try {
            if (fact.printability() != Printability.READY)
                throw new PrintFailure(
                        fact.blockCode() == null ? "print.sourceBlocked" : fact.blockCode());
            var guard = source.executionGuard(ref);
            require(guard.allowed(), "print.sourceExecutionHeld");
            route =
                    routes.selectOne(
                            PrintSupport.<PrnRoute>scoped(scope)
                                    .eq("scene", ref.scene())
                                    .eq("status", "ACTIVE"));
            require(route != null && route.getCurrentVersionNo() != null, "print.routeMissing");
            var version =
                    routeVersions.selectOne(
                            PrintSupport.<PrnRouteVersion>owned(scope)
                                    .eq("route_version_no", route.getCurrentVersionNo())
                                    .eq("status", "PUBLISHED"));
            require(version != null, "print.routeMissing");
            var rules = Arrays.asList(json.read(version.getRules(), RouteRule[].class));
            var matches = matcher.match(ref.factNo(), json.tree(fact.immutablePayload()), rules);
            for (var match : matches) {
                var printer =
                        printers.selectOne(
                                PrintSupport.<PrnPrinter>scoped(scope)
                                        .eq("printer_no", match.printerNo()));
                require(
                        printer != null && !"RETIRED".equals(printer.getStatus()),
                        "print.printerUnavailable");
                var selection = match.templateSelection();
                require(
                        selection.variantCode().equals("STANDARD_" + printer.getPaperWidthMm()),
                        "print.paperMismatch");
                var template =
                        templates.resolve(
                                new TemplateResolutionCommand(
                                        scope,
                                        ref.scene(),
                                        fact.payloadSchemaVersion(),
                                        selection.variantCode(),
                                        selection,
                                        ResolutionPurpose.PRODUCTION,
                                        actor.dataScopes()));
                var profile = profiles.require("ROLL" + printer.getPaperWidthMm(), "1");
                for (int copy = 1; copy <= match.copies(); copy++) {
                    var job = init(new PrnJob(), scope);
                    job.setJobNo(no("PJ"));
                    job.setStoreNo(scope.storeNo());
                    job.setRequestNo(request.getRequestNo());
                    job.setScene(ref.scene());
                    job.setDocumentKey(ref.factNo() + ":" + ref.scene());
                    job.setRouteRuleKey(match.ruleKey());
                    job.setTargetKey(printer.getPrinterNo());
                    job.setCopyIndex(copy);
                    job.setRequired(match.required() ? 1 : 0);
                    job.setPrinterNo(printer.getPrinterNo());
                    job.setTemplateVersionNo(template.templateVersionNo());
                    var slots =
                            new SystemPrintSlots(
                                    job.getJobNo(),
                                    PrintJobKind.INITIAL,
                                    copy,
                                    Instant.now(),
                                    null,
                                    null,
                                    Set.of(),
                                    null);
                    var document = compiler.compile(fact, template, slots);
                    access(scope, actor, automatic ? Set.of() : document.requiredDataScopes());
                    var frozenLayout = layout.layout(document, profile);
                    job.setRendererVersion(
                            frozenLayout.profileVersion().endsWith("_COMPACT_V2")
                                    ? "PRINT_DOC_V2"
                                    : "PRINT_DOC_V1");
                    job.setLayoutProfileSnapshot(json.write(profile));
                    job.setLayoutSnapshot(json.write(frozenLayout));
                    job.setTemplateResolutionSnapshot(json.write(template));
                    job.setPayloadSnapshot(fact.immutablePayload().canonicalJson());
                    job.setRichDocumentSnapshot(json.write(document));
                    job.setDocumentHash(json.freeze(document).sha256());
                    job.setRouteSnapshot(json.write(match));
                    job.setStatus("PENDING");
                    job.setPriority(
                            scenes.require(ref.scene()).definition().path("priority").asInt(0));
                    job.setRetryCount(0);
                    job.setMaxRetryCount(policy.getMaxRetryCount());
                    job.setDeadlineAt(request.getDeadlineAt());
                    job.setRootJobNo(job.getJobNo());
                    job.setJobKind("INITIAL");
                    job.setResolutionStatus("OPEN");
                    generated.add(job);
                }
            }
        } catch (PrintFailure ex) {
            request.setStatus("BLOCKED");
            request.setBlockCode(ex.key());
            request.setNextCheckAt(now().plusMinutes(1));
            saveRequest(request);
            return submission(request, actor);
        }
        saveRequest(request);
        var batch = init(new PrnBatch(), scope);
        batch.setBatchNo(no("PB"));
        batch.setRequestNo(request.getRequestNo());
        batch.setStoreNo(scope.storeNo());
        batch.setBatchKind("INITIAL");
        batch.setGenerationKey("INITIAL");
        batch.setRouteVersionNo(route.getCurrentVersionNo());
        batch.setStatus("PENDING");
        batch.setExpectedCount((int) generated.stream().filter(g -> g.getRequired() == 1).count());
        batch.setConfirmedCount(0);
        batch.setFailedCount(0);
        batch.setUnknownCount(0);
        batch.setCancelledCount(0);
        batch.setResolvedCount(0);
        batch.setResolvedStatus("OPEN");
        batch.setCreatedBy(actor.actorNo());
        batches.insert(batch);
        generated.sort(Comparator.comparing(PrnJob::getPrinterNo));
        for (var job : generated) {
            var runtime =
                    runtimes.selectOne(
                            PrintSupport.<PrnPrinterRuntime>owned(scope)
                                    .eq("printer_no", job.getPrinterNo())
                                    .last("FOR UPDATE"));
            require(runtime != null, "print.runtimeMissing");
            runtime.setLastSequence(runtime.getLastSequence() + 1);
            runtime.setUpdatedAt(now());
            runtime.setRevision(runtime.getRevision() + 1);
            runtimes.updateById(runtime);
            job.setPrinterSequence(runtime.getLastSequence());
            job.setBatchNo(batch.getBatchNo());
            ai.neargo.shop.printing.service.PreparedPrintJobs.initialize(job);
            jobs.insert(job);
        }
        request.setStatus("GENERATED");
        request.setBlockCode(null);
        request.setNextCheckAt(null);
        request.setRevision(request.getRevision() + 1);
        request.setUpdatedAt(now());
        requests.updateById(request);
        var submission = submission(request, actor);
        if (output.pdfEnabled()) {
            var exported =
                    exports.create(
                            new ExportCommand(
                                    scope,
                                    new ExportSource(
                                            ExportSourceType.BATCH,
                                            batch.getBatchNo(),
                                            batch.getRevision()),
                                    output.pdfProfile()),
                            new PrintActor(
                                    actor.tenantNo(),
                                    actor.entityNo(),
                                    actor.storeNo(),
                                    actor.actorType(),
                                    actor.actorNo(),
                                    "AUTO_PDF:" + batch.getBatchNo(),
                                    actor.dataScopes()));
            submission =
                    new PrintSubmission(
                            submission.requestNo(),
                            submission.batchNos(),
                            submission.jobNos(),
                            null,
                            List.of(exported.targetNo()));
        }
        return submission;
    }

    private PrintSubmission submission(PrnRequest r, PrintActor a) {
        var batch =
                batches.selectList(
                        PrintSupport.<PrnBatch>scoped(scope(a))
                                .eq("request_no", r.getRequestNo())
                                .orderByAsc("id"));
        var children =
                jobs.selectList(
                        PrintSupport.<PrnJob>scoped(scope(a))
                                .eq("request_no", r.getRequestNo())
                                .orderByAsc("id"));
        return new PrintSubmission(
                r.getRequestNo(),
                batch.stream().map(PrnBatch::getBatchNo).toList(),
                children.stream().map(PrnJob::getJobNo).toList(),
                null,
                List.of());
    }

    private void saveRequest(PrnRequest r) {
        if (r.getId() == null) requests.insert(r);
        else {
            r.setRevision(r.getRevision() + 1);
            r.setUpdatedAt(now());
            requests.updateById(r);
        }
    }

    public PrintSubmission recoverGeneration(String no, Long expected, PrintActor a) {
        var r =
                requests.selectOne(
                        PrintSupport.<PrnRequest>scoped(scope(a))
                                .eq("request_no", no)
                                .last("FOR UPDATE"));
        revision(r, expected);
        require(
                Set.of("PENDING", "BLOCKED", "GENERATED").contains(r.getStatus()),
                "print.generationNotRetryable");
        return generate(
                new PrintIntent(
                        json.read(r.getSourceRef(), PrintFactRef.class),
                        PrintMode.EXISTING_FACT,
                        json.read(r.getOutputOptions(), PrintOutputOptions.class)),
                a,
                false,
                true);
    }

    public PrintJobView getJob(PrintJobRef ref, PrintActor actor) {
        return query.getJob(ref, actor);
    }
}
