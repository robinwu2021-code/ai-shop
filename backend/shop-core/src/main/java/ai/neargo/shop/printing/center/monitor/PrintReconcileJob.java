package ai.neargo.shop.printing.center.monitor;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.api.dto.PrintMonitorDtos.*;
import ai.neargo.shop.printing.center.audit.PrintActionAppender;
import ai.neargo.shop.printing.center.dispatch.PrintStateModels.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.source.PrintSourceRegistry;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class PrintReconcileJob {
    private final PrnReconcileCursorMapper cursors;
    private final PrnRequestMapper requests;
    private final PrintSourceRegistry sources;
    private final PrintFacade facade;
    private final PrintActionAppender audit;

    public PrintReconcileJob(
            PrnReconcileCursorMapper c,
            PrnRequestMapper r,
            PrintSourceRegistry s,
            PrintFacade f,
            PrintActionAppender a) {
        cursors = c;
        requests = r;
        sources = s;
        facade = f;
        audit = a;
    }

    @Transactional
    public PrintMaintenanceReport reconcile(PrintMaintenanceCommand command) {
        require(
                command.limit() >= 1
                        && command.limit() <= 500
                        && command.from() != null
                        && command.to() != null
                        && !command.from().isAfter(command.to())
                        && command.from().isAfter(Instant.now().minus(Duration.ofDays(180))),
                "print.invalidReconcileRange");
        audit.lock(command.scope());
        var cursor =
                cursors.selectOne(
                        PrintSupport.<PrnReconcileCursor>scoped(command.scope())
                                .eq("source_kind", command.sourceKind())
                                .last("FOR UPDATE"));
        var from = command.from();
        Instant watermark = null;
        String watermarkNo = null;
        if (cursor != null && cursor.getWatermarkAt() != null) {
            var saved = cursor.getWatermarkAt().toInstant(ZoneOffset.UTC);
            if (saved.isAfter(from)) {
                watermark = saved;
                watermarkNo = cursor.getWatermarkNo();
            }
        }
        var page =
                sources.require(command.sourceKind())
                        .scan(
                                new PrintFactCursor(
                                        command.scope(),
                                        command.sourceKind(),
                                        from,
                                        command.to(),
                                        watermark,
                                        watermarkNo),
                                command.limit());
        int changed = 0, blocked = 0;
        for (var ref : page.facts()) {
            if (requests.selectCount(
                            PrintSupport.<PrnRequest>scoped(command.scope())
                                    .eq("biz_no", ref.factNo())
                                    .eq("scene", ref.scene()))
                    == 0) {
                if (!command.dryRun()) {
                    var result = facade.onFact(ref);
                    var row =
                            requests.selectOne(
                                    PrintSupport.<PrnRequest>scoped(command.scope())
                                            .eq("request_no", result.requestNo()));
                    if (row != null && "BLOCKED".equals(row.getStatus())) blocked++;
                }
                changed++;
            }
        }
        if (!command.dryRun()) {
            if (cursor == null) {
                cursor = init(new PrnReconcileCursor(), command.scope());
                cursor.setCursorNo(no("CR"));
                cursor.setStoreNo(command.scope().storeNo());
                cursor.setSourceKind(command.sourceKind());
                cursor.setOverlapSeconds(60);
            }
            var next = page.nextCursor();
            cursor.setWatermarkAt(
                    page.hasMore()
                            ? (next.watermarkAt() == null
                                    ? null
                                    : LocalDateTime.ofInstant(next.watermarkAt(), ZoneOffset.UTC))
                            : LocalDateTime.ofInstant(
                                    command.to().minusSeconds(60), ZoneOffset.UTC));
            cursor.setWatermarkNo(page.hasMore() ? next.watermarkNo() : "");
            cursor.setLastSuccessAt(now());
            cursor.setRevision(cursor.getRevision() + 1);
            cursor.setUpdatedAt(now());
            if (cursor.getId() == null) cursors.insert(cursor);
            else cursors.updateById(cursor);
        }
        return new PrintMaintenanceReport(
                page.facts().size(),
                changed,
                blocked,
                0,
                page.hasMore(),
                command.dryRun() ? "Dry run; no writes" : "Source facts reconciled");
    }
}
