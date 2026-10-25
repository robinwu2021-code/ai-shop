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

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintRouteServiceImpl implements PrintRouteService {
    private final PrintPreviewFacts previewFacts;
    private final PrnRouteMapper heads;
    private final PrnRouteVersionMapper versions;
    private final PrnPrinterMapper printers;
    private final PrintOperationService ops;
    private final PrintJsonCodec json;
    private final PrintSceneRegistry scenes;
    private final PrintRouteMatcher matcher;

    public PrintRouteServiceImpl(
            PrnRouteMapper h,
            PrnRouteVersionMapper v,
            PrnPrinterMapper p,
            PrintOperationService o,
            PrintJsonCodec j,
            PrintSceneRegistry s,
            PrintRouteMatcher m,
            PrintPreviewFacts previewFacts) {
        this.previewFacts = previewFacts;
        heads = h;
        versions = v;
        printers = p;
        ops = o;
        json = j;
        scenes = s;
        matcher = m;
    }

    private PrnRoute head(String no, PrintActor a) {
        var h = heads.selectOne(PrintSupport.<PrnRoute>scoped(scope(a)).eq("route_no", no));
        require(h != null, "print.notFound");
        return h;
    }

    private PrnRouteVersion version(String no, PrintActor a) {
        var v =
                versions.selectOne(
                        PrintSupport.<PrnRouteVersion>owned(scope(a)).eq("route_version_no", no));
        require(v != null, "print.notFound");
        head(v.getRouteNo(), a);
        return v;
    }

    private List<RouteRule> rules(PrnRouteVersion v) {
        return Arrays.asList(json.read(v.getRules(), RouteRule[].class));
    }

    public PageData<RouteVO> listRoutes(ListRoutesQuery q, PrintActor a) {
        require(a.storeNo().equals(q.storeNo()), "print.forbidden");
        var p =
                heads.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnRoute>scoped(scope(a))
                                .eq(q.scene() != null, "scene", q.scene())
                                .orderByDesc("id"));
        return PageData.of(
                p.getRecords().stream()
                        .map(
                                v ->
                                        new RouteVO(
                                                v.getRouteNo(),
                                                v.getScene(),
                                                v.getCurrentVersionNo(),
                                                v.getStatus(),
                                                v.getRevision()))
                        .toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }

    public PrintOperation createRoute(CreateRouteReq r, PrintActor a) {
        return ops.execute(
                a,
                "CREATE_ROUTE",
                json.freeze(r).sha256(),
                () -> {
                    require(a.storeNo().equals(r.storeNo()), "print.forbidden");
                    scenes.require(r.scene());
                    var h = init(new PrnRoute(), scope(a));
                    h.setRouteNo(no("RT"));
                    h.setStoreNo(a.storeNo());
                    h.setScene(r.scene());
                    h.setStatus("ACTIVE");
                    heads.insert(h);
                    return succeeded(
                            "CREATE_ROUTE",
                            "ROUTE",
                            h.getRouteNo(),
                            json.freeze(Map.of("routeNo", h.getRouteNo())));
                });
    }

    public PrintOperation createRouteVersion(String no, CreateRouteVersionReq r, PrintActor a) {
        return ops.execute(
                a,
                "CREATE_ROUTE_VERSION",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var h = head(no, a);
                    revision(h, r.expectedRevision());
                    matcher.validate(r.rules());
                    var v = init(new PrnRouteVersion(), scope(a));
                    v.setRouteVersionNo(no("RV"));
                    v.setRouteNo(no);
                    v.setVersionNumber(
                            versions.selectCount(
                                                    PrintSupport.<PrnRouteVersion>owned(scope(a))
                                                            .eq("route_no", no))
                                            .intValue()
                                    + 1);
                    v.setRules(json.write(r.rules()));
                    v.setContentHash(json.freeze(r.rules()).sha256());
                    v.setStatus("DRAFT");
                    v.setCreatedBy(a.actorNo());
                    versions.insert(v);
                    h.setRevision(h.getRevision() + 1);
                    h.setUpdatedAt(now());
                    heads.updateById(h);
                    return succeeded(
                            "CREATE_ROUTE_VERSION",
                            "ROUTE_VERSION",
                            v.getRouteVersionNo(),
                            json.freeze(Map.of("routeVersionNo", v.getRouteVersionNo())));
                });
    }

    public PrintOperation publishRoute(String no, PublishRouteReq r, PrintActor a) {
        return ops.execute(
                a,
                "PUBLISH_ROUTE",
                json.freeze(Map.of("no", no, "request", r)).sha256(),
                () -> {
                    var v = version(no, a);
                    var h = head(v.getRouteNo(), a);
                    revision(v, r.expectedRevision());
                    revision(h, r.expectedRouteRevision());
                    require("DRAFT".equals(v.getStatus()), "print.publishedImmutable");
                    var rules = rules(v);
                    matcher.validate(rules);
                    boolean fallback = false;
                    for (var rule : rules) {
                        if (rule.match().all().isEmpty()) fallback = true;
                        for (var target : rule.targets()) {
                            var p =
                                    printers.selectOne(
                                            PrintSupport.<PrnPrinter>scoped(scope(a))
                                                    .eq("printer_no", target.printerNo())
                                                    .eq("status", "ACTIVE"));
                            require(
                                    p != null
                                            && target.templateSelection()
                                                    .variantCode()
                                                    .equals("STANDARD_" + p.getPaperWidthMm()),
                                    "print.routePrinterInvalid");
                        }
                    }
                    require(fallback, "print.defaultRouteRequired");
                    v.setStatus("PUBLISHED");
                    v.setPublishedAt(now());
                    v.setRevision(v.getRevision() + 1);
                    v.setUpdatedAt(now());
                    versions.updateById(v);
                    h.setCurrentVersionNo(no);
                    h.setRevision(h.getRevision() + 1);
                    h.setUpdatedAt(now());
                    heads.updateById(h);
                    return succeeded(
                            "PUBLISH_ROUTE",
                            "ROUTE_VERSION",
                            no,
                            json.freeze(Map.of("routeVersionNo", no)));
                });
    }

    public RouteDryRunVO dryRunRoute(String no, DryRunRouteReq r, PrintActor a) {
        var v = version(no, a);
        var h = head(v.getRouteNo(), a);
        return new RouteDryRunVO(
                matcher.match(
                        "PREVIEW",
                        json.tree(
                                previewFacts
                                        .get(h.getScene(), r.sourceRef(), a)
                                        .immutablePayload()),
                        rules(v)),
                r.sourceRef() == null ? List.of("SAMPLE_ONLY") : List.of("PREVIEW_ONLY"));
    }

    public PageData<RouteVersionVO> listRouteVersions(
            String no, ListRouteVersionsQuery q, PrintActor a) {
        head(no, a);
        var p =
                versions.selectPage(
                        new Page<>(
                                pageNumber(q.page()),
                                pageSize(q.size())),
                        PrintSupport.<PrnRouteVersion>owned(scope(a))
                                .eq("route_no", no)
                                .orderByDesc("version_number"));
        return PageData.of(
                p.getRecords().stream()
                        .map(
                                v ->
                                        new RouteVersionVO(
                                                v.getRouteVersionNo(),
                                                v.getRouteNo(),
                                                v.getStatus(),
                                                rules(v),
                                                v.getRevision()))
                        .toList(),
                p.getTotal(),
                p.getCurrent(),
                p.getSize());
    }
}
