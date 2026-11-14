package ai.neargo.shop.printing.center.export;

import ai.neargo.shop.spi.printing.*;

import static ai.neargo.shop.printing.center.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.printing.center.layout.*;
import ai.neargo.shop.printing.center.persistence.entity.*;
import ai.neargo.shop.printing.center.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.printing.center.support.*;
import ai.neargo.shop.printing.center.template.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

@Service
public class PrintExportCleanupJob {
    private final PrnExportTaskMapper exports;
    private final PrintArtifactStorePort store;

    public PrintExportCleanupJob(PrnExportTaskMapper e, PrintArtifactStorePort s) {
        exports = e;
        store = s;
    }

    public PrintWorkReport expire(PrintWorkerBudget budget) {
        var due =
                exports.selectList(
                        new QueryWrapper<PrnExportTask>()
                                .in("status", List.of("READY", "FAILED", "PENDING", "EXPIRED"))
                                .isNull("object_deleted_at")
                                .le("expires_at", now())
                                .orderByAsc("updated_at", "id")
                                .last("LIMIT " + budget.maxItems()));
        int done = 0, failed = 0;
        for (var e : due) {
            try {
                int claimed =
                        exports.update(
                                null,
                                new UpdateWrapper<PrnExportTask>()
                                        .eq("id", e.getId())
                                        .eq("revision", e.getRevision())
                                        .eq("status", e.getStatus())
                                        .set("status", "EXPIRED")
                                        .set("updated_at", now())
                                        .setSql("revision = revision + 1"));
                if (claimed == 1) {
                    store.delete(e.getObjectKey());
                    exports.update(
                            null,
                            new UpdateWrapper<PrnExportTask>()
                                    .eq("id", e.getId())
                                    .eq("status", "EXPIRED")
                                    .eq("revision", e.getRevision() + 1)
                                    .set("object_deleted_at", now())
                                    .set("updated_at", now())
                                    .setSql("revision = revision + 1"));
                    done++;
                }
            } catch (Exception ex) {
                failed++;
            }
        }
        exports.delete(
                new QueryWrapper<PrnExportTask>()
                        .eq("status", "EXPIRED")
                        .isNotNull("object_deleted_at")
                        .lt("created_at", now().minusDays(180)));
        return new PrintWorkReport(due.size(), done, failed, due.size() == budget.maxItems());
    }
}
