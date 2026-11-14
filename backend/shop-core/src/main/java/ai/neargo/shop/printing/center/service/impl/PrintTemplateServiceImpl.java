package ai.neargo.shop.printing.center.service.impl;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.operation.PrintOperationService;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintTemplateServiceImpl implements PrintTemplateService {
    private final PrintPreviewFacts previewFacts;
    private final PrnTemplateMapper heads;
    private final PrnTemplateVersionMapper versions;
    private final PrintOperationService operations;
    private final PrintJsonCodec json;
    private final PrintSceneRegistry scenes;
    private final DefaultPrintDocumentCompiler compiler;
    private final PrintTemplateResolver resolver;

    public PrintTemplateServiceImpl(
            PrnTemplateMapper h,
            PrnTemplateVersionMapper v,
            PrintOperationService o,
            PrintJsonCodec j,
            PrintSceneRegistry s,
            DefaultPrintDocumentCompiler c,
            PrintTemplateResolver r,
            PrintPreviewFacts previewFacts) {
        this.previewFacts = previewFacts;
        heads = h;
        versions = v;
        operations = o;
        json = j;
        scenes = s;
        compiler = c;
        resolver = r;
    }

    private PrnTemplate head(String no, PrintActor a) {
        var t =
                heads.selectOne(
                        PrintSupport.<PrnTemplate>owned(scope(a))
                                .eq("template_no", no)
                                .eq("store_no", a.storeNo()));
        require(t != null, "print.notFound");
        return t;
    }

    private PrnTemplateVersion version(String no, PrintActor a) {
        var v =
                versions.selectOne(
                        PrintSupport.<PrnTemplateVersion>owned(scope(a))
                                .eq("template_version_no", no));
        require(v != null, "print.notFound");
        head(v.getTemplateNo(), a);
        return v;
    }

    private TemplateVO vo(PrnTemplate t) {
        return new TemplateVO(
                t.getTemplateNo(),
                t.getScene(),
                t.getVariantCode(),
                t.getName(),
                t.getScopeKey(),
                t.getCurrentVersionNo(),
                t.getStatus(),
                t.getRevision());
    }

    private TemplateVersionVO vo(PrnTemplateVersion v) {
        return new TemplateVersionVO(
                v.getTemplateVersionNo(),
                v.getTemplateNo(),
                v.getStatus(),
                v.getPayloadSchemaVersion(),
                v.getPaperWidthMm(),
                json.read(v.getContent(), Map.class),
                v.getRevision());
    }

    public PageData<TemplateVO> listTemplates(ListTemplatesQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var query =
                new QueryWrapper<PrnTemplate>()
                        .eq("scene", q.scene())
                        .eq(q.variantCode() != null, "variant_code", q.variantCode())
                        .and(
                                n ->
                                        n.eq("scope_key", "PLATFORM")
                                                .or(
                                                        n2 ->
                                                                n2.eq("tenant_no", a.tenantNo())
                                                                        .eq(
                                                                                "entity_no",
                                                                                a.entityNo())
                                                                        .and(
                                                                                n3 ->
                                                                                        n3.eq(
                                                                                                        "scope_key",
                                                                                                        "ENTITY:"
                                                                                                                + a
                                                                                                                        .entityNo())
                                                                                                .or()
                                                                                                .eq(
                                                                                                        "scope_key",
                                                                                                        "STORE:"
                                                                                                                + a
                                                                                                                        .storeNo()))));
        if (q.scene() == null)
            query =
                    new QueryWrapper<PrnTemplate>()
                            .eq("tenant_no", a.tenantNo())
                            .eq("entity_no", a.entityNo())
                            .eq("store_no", a.storeNo());
        var page =
                heads.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        query.orderByDesc("id"));
        return PageData.of(
                page.getRecords().stream().map(this::vo).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    public PrintOperation createTemplate(CreateTemplateReq r, PrintActor a) {
        return operations.execute(
                a,
                "CREATE_TEMPLATE",
                json.freeze(r).sha256(),
                () -> {
                    require(a.storeNo().equals(r.storeNo()), "print.forbidden");
                    require(
                            scenes.require(r.scene()).templates().containsKey(r.variantCode()),
                            "print.variantUnavailable");
                    require(
                            r.name() != null && !r.name().isBlank() && r.name().length() <= 64,
                            "print.invalidName");
                    var t = init(new PrnTemplate(), scope(a));
                    t.setTemplateNo(no("TM"));
                    t.setStoreNo(a.storeNo());
                    t.setScopeKey("STORE:" + a.storeNo());
                    t.setName(r.name());
                    t.setScene(r.scene());
                    t.setVariantCode(r.variantCode());
                    t.setStatus("ACTIVE");
                    heads.insert(t);
                    return succeeded(
                            "CREATE_TEMPLATE",
                            "TEMPLATE",
                            t.getTemplateNo(),
                            json.freeze(Map.of("templateNo", t.getTemplateNo())));
                });
    }

    public PrintOperation createTemplateVersion(
            String no, CreateTemplateVersionReq r, PrintActor a) {
        return operations.execute(
                a,
                "CREATE_TEMPLATE_VERSION",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var t = head(no, a);
                    revision(t, r.expectedRevision());
                    check(t, r.paperWidthMm(), r.payloadSchemaVersion(), json.freeze(r.content()));
                    require(
                            "PRINT_DOC_V1".equals(r.format()) && r.schemaVersion() == 1,
                            "print.templateFormat");
                    var v = init(new PrnTemplateVersion(), scope(a));
                    v.setTemplateVersionNo(no("TV"));
                    v.setTemplateNo(no);
                    v.setVersionNumber(
                            versions.selectCount(
                                                    PrintSupport.<PrnTemplateVersion>owned(scope(a))
                                                            .eq("template_no", no))
                                            .intValue()
                                    + 1);
                    v.setFormat(r.format());
                    v.setSchemaVersion(1);
                    v.setPayloadSchemaVersion(r.payloadSchemaVersion());
                    v.setPaperWidthMm(r.paperWidthMm());
                    v.setContent(json.freeze(r.content()).canonicalJson());
                    v.setContentHash(json.freeze(r.content()).sha256());
                    v.setCompatibleCapabilities(json.write(r.compatibleCapabilities()));
                    v.setStatus("DRAFT");
                    v.setCreatedBy(a.actorNo());
                    versions.insert(v);
                    t.setRevision(t.getRevision() + 1);
                    t.setUpdatedAt(now());
                    heads.updateById(t);
                    return succeeded(
                            "CREATE_TEMPLATE_VERSION",
                            "TEMPLATE_VERSION",
                            v.getTemplateVersionNo(),
                            json.freeze(Map.of("templateVersionNo", v.getTemplateVersionNo())));
                });
    }

    private void check(PrnTemplate t, int width, int schema, FrozenJson content) {
        require(
                (width == 58 || width == 80)
                        && t.getVariantCode().equals("STANDARD_" + width)
                        && schema == 1,
                "print.templateMismatch");
        json.verify(content);
        compiler.validateTemplate(t.getScene(), content.canonicalJson());
    }

    public PrintOperation updateTemplateDraft(String no, UpdateTemplateDraftReq r, PrintActor a) {
        return operations.execute(
                a,
                "UPDATE_TEMPLATE_DRAFT",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var v = version(no, a);
                    revision(v, r.expectedRevision());
                    require("DRAFT".equals(v.getStatus()), "print.publishedImmutable");
                    check(
                            head(v.getTemplateNo(), a),
                            r.paperWidthMm(),
                            r.payloadSchemaVersion(),
                            json.freeze(r.content()));
                    v.setContent(json.freeze(r.content()).canonicalJson());
                    v.setContentHash(json.freeze(r.content()).sha256());
                    v.setCompatibleCapabilities(json.write(r.compatibleCapabilities()));
                    v.setRevision(v.getRevision() + 1);
                    v.setUpdatedAt(now());
                    versions.updateById(v);
                    return succeeded(
                            "UPDATE_TEMPLATE_DRAFT",
                            "TEMPLATE_VERSION",
                            no,
                            json.freeze(Map.of("templateVersionNo", no)));
                });
    }

    public PrintOperation publishTemplate(String no, PublishTemplateReq r, PrintActor a) {
        return operations.execute(
                a,
                "PUBLISH_TEMPLATE",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var v = version(no, a);
                    var t = head(v.getTemplateNo(), a);
                    revision(v, r.expectedRevision());
                    revision(t, r.expectedTemplateRevision());
                    check(
                            t,
                            v.getPaperWidthMm(),
                            v.getPayloadSchemaVersion(),
                            json.freeze(v.getContent()));
                    var preview = preview(v, t, a);
                    require(preview.document() != null, "print.templateInvalid");
                    v.setStatus("PUBLISHED");
                    v.setPublishedAt(now());
                    v.setRevision(v.getRevision() + 1);
                    v.setUpdatedAt(now());
                    versions.updateById(v);
                    t.setCurrentVersionNo(no);
                    t.setRevision(t.getRevision() + 1);
                    t.setUpdatedAt(now());
                    heads.updateById(t);
                    return succeeded(
                            "PUBLISH_TEMPLATE",
                            "TEMPLATE_VERSION",
                            no,
                            json.freeze(Map.of("templateVersionNo", no)));
                });
    }

    public PageData<TemplateVersionVO> listTemplateVersions(
            String no, ListTemplateVersionsQuery q, PrintActor a) {
        head(no, a);
        var page =
                versions.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnTemplateVersion>owned(scope(a))
                                .eq("template_no", no)
                                .orderByDesc("version_number"));
        return PageData.of(
                page.getRecords().stream().map(this::vo).toList(),
                page.getTotal(),
                page.getCurrent(),
                page.getSize());
    }

    public PreviewVO previewTemplate(String no, PreviewTemplateReq r, PrintActor a) {
        var v = version(no, a);
        require(
                r.paperWidthMm() != null && v.getPaperWidthMm().equals(r.paperWidthMm()),
                "print.templateMismatch");
        return preview(v, head(v.getTemplateNo(), a), a, r.sampleSourceRef());
    }

    private PreviewVO preview(PrnTemplateVersion v, PrnTemplate t, PrintActor a) {
        return preview(v, t, a, null);
    }

    private PreviewVO preview(
            PrnTemplateVersion v, PrnTemplate t, PrintActor a, PrintSourceReference reference) {
        var fact = previewFacts.get(t.getScene(), reference, a);
        var resolved =
                new ResolvedTemplate(
                        t.getTemplateNo(),
                        v.getTemplateVersionNo(),
                        null,
                        1,
                        t.getScene(),
                        t.getVariantCode(),
                        json.freeze(v.getContent()),
                        json.freeze(Map.of("scope", "DRAFT_PREVIEW")));
        var doc =
                compiler.compile(
                        fact,
                        resolved,
                        new SystemPrintSlots(
                                "PREVIEW",
                                PrintJobKind.INITIAL,
                                1,
                                Instant.EPOCH,
                                null,
                                null,
                                Set.of("预览，非真实出票"),
                                null));
        return new PreviewVO(
                t.getScene(),
                t.getVariantCode(),
                v.getPaperWidthMm(),
                1,
                v.getTemplateVersionNo(),
                doc,
                json.freeze(doc).sha256(),
                fact.ref().bizRevision(),
                reference == null ? List.of("SAMPLE_ONLY") : List.of("PREVIEW_ONLY"));
    }

    public TemplateResolutionPreviewVO previewResolution(PreviewResolutionReq r, PrintActor a) {
        require(a.storeNo().equals(r.storeNo()), "print.forbidden");
        require(r.sample() == (r.sourceRef() == null), "print.sourceMismatch");
        var selection = r.templateSelection();
        require(selection != null, "print.templateSelectionRequired");
        var fact = previewFacts.get(r.scene(), r.sourceRef(), a);
        var t =
                resolver.resolve(
                        new TemplateResolutionCommand(
                                scope(a),
                                r.scene(),
                                fact.payloadSchemaVersion(),
                                selection.variantCode(),
                                selection,
                                ResolutionPurpose.SAMPLE_PREVIEW,
                                a.dataScopes()));
        var doc =
                compiler.compile(
                        fact,
                        t,
                        new SystemPrintSlots(
                                "PREVIEW",
                                PrintJobKind.INITIAL,
                                1,
                                Instant.EPOCH,
                                null,
                                null,
                                Set.of("预览，非真实出票"),
                                null));
        return new TemplateResolutionPreviewVO(
                r.scene(), selection.variantCode(), t, doc, List.of(), Instant.now());
    }
}
