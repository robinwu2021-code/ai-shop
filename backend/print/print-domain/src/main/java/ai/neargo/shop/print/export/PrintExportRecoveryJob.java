package ai.neargo.shop.print.export;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintExportDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
public class PrintExportRecoveryJob {
    private final PrnExportTaskMapper exports;

    public PrintExportRecoveryJob(PrnExportTaskMapper e) {
        exports = e;
    }

    @Transactional
    public PrintWorkReport recover(PrintWorkerBudget budget) {
        var due =
                exports.selectList(
                        new QueryWrapper<PrnExportTask>()
                                .eq("status", "GENERATING")
                                .lt("lease_until", now())
                                .orderByAsc("id")
                                .last("LIMIT " + budget.maxItems() + " FOR UPDATE SKIP LOCKED"));
        for (var e : due) {
            e.setStatus("PENDING");
            e.setNextRetryAt(now());
            e.setLeaseUntil(null);
            e.setClaimTokenHash(null);
            e.setGenerationEpoch(e.getGenerationEpoch() + 1);
            e.setRevision(e.getRevision() + 1);
            e.setUpdatedAt(now());
            exports.update(
                    null,
                    new UpdateWrapper<PrnExportTask>()
                            .eq("id", e.getId())
                            .eq("status", "GENERATING")
                            .lt("lease_until", now())
                            .set("status", "PENDING")
                            .set("next_retry_at", now())
                            .set("lease_until", null)
                            .set("claim_token_hash", null)
                            .setSql(
                                    "revision = revision + 1, generation_epoch = generation_epoch +"
                                            + " 1"));
        }
        return new PrintWorkReport(due.size(), due.size(), 0, due.size() == budget.maxItems());
    }
}
