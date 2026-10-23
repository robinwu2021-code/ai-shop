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

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
public class PrintExportWorker {
    private final PrnExportTaskMapper exports;
    private final PrintPdfPort pdf;
    private final PrintArtifactStorePort store;
    private final PrintJsonCodec json;
    private final TransactionTemplate tx;

    public PrintExportWorker(
            PrnExportTaskMapper e,
            PrintPdfPort p,
            PrintArtifactStorePort s,
            PrintJsonCodec j,
            @Qualifier("transactionManager") PlatformTransactionManager m) {
        exports = e;
        pdf = p;
        store = s;
        json = j;
        tx = new TransactionTemplate(m);
    }

    public PrintWorkReport generateNext(PrintWorkerBudget budget) {
        int done = 0, failed = 0;
        long deadline = System.nanoTime() + budget.timeBudget().toNanos();
        while (done + failed < budget.maxItems() && System.nanoTime() < deadline) {
            String token = no("WK");
            var row =
                    tx.execute(
                            status -> {
                                var e =
                                        exports.selectOne(
                                                new QueryWrapper<PrnExportTask>()
                                                        .in("status", List.of("PENDING", "FAILED"))
                                                        .le("next_retry_at", now())
                                                        .lt("retry_count", 5)
                                                        .orderByAsc("id")
                                                        .last("LIMIT 1 FOR UPDATE SKIP LOCKED"));
                                if (e == null) return null;
                                e.setStatus("GENERATING");
                                e.setClaimTokenHash(PrintHashes.sha256(token));
                                e.setLeaseUntil(now().plusMinutes(2));
                                e.setGenerationEpoch(e.getGenerationEpoch() + 1);
                                e.setRevision(e.getRevision() + 1);
                                e.setUpdatedAt(now());
                                exports.updateById(e);
                                return e;
                            });
            if (row == null) break;
            try {
                var rendered = pdf.render(json.read(row.getSourceSnapshot(), PdfRenderInput.class));
                store.put(
                        new PrivateArtifact(
                                row.getObjectKey(),
                                rendered.bytes(),
                                rendered.contentHash(),
                                "application/pdf"));
                var verified = store.verify(row.getObjectKey());
                require(
                        verified.exists()
                                && verified.size() == rendered.bytes().length
                                && rendered.contentHash().equals(verified.sha256()),
                        "print.artifactIntegrityFailed");
                Integer changed =
                        tx.execute(
                                status ->
                                        exports.update(
                                                null,
                                                new UpdateWrapper<PrnExportTask>()
                                                        .eq("id", row.getId())
                                                        .eq("status", "GENERATING")
                                                        .eq(
                                                                "generation_epoch",
                                                                row.getGenerationEpoch())
                                                        .eq(
                                                                "claim_token_hash",
                                                                PrintHashes.sha256(token))
                                                        .set("status", "READY")
                                                        .set("content_hash", rendered.contentHash())
                                                        .set("file_size", verified.size())
                                                        .set("page_count", rendered.pageCount())
                                                        .set("ready_at", now())
                                                        .set("lease_until", null)
                                                        .set("claim_token_hash", null)
                                                        .set("last_error_code", null)
                                                        .set("updated_at", now())
                                                        .setSql("revision = revision + 1")));
                if (changed != null && changed == 1) done++;
                else failed++;
            } catch (Exception ex) {
                tx.executeWithoutResult(
                        status ->
                                exports.update(
                                        null,
                                        new UpdateWrapper<PrnExportTask>()
                                                .eq("id", row.getId())
                                                .eq("generation_epoch", row.getGenerationEpoch())
                                                .eq("claim_token_hash", PrintHashes.sha256(token))
                                                .set("status", "FAILED")
                                                .set(
                                                        "last_error_code",
                                                        ex instanceof PrintFailure f
                                                                ? f.key()
                                                                : "print.exportFailed")
                                                .set(
                                                        "next_retry_at",
                                                        now().plusSeconds(
                                                                        Math.min(
                                                                                300,
                                                                                10L
                                                                                        << row
                                                                                                .getRetryCount())))
                                                .set("lease_until", null)
                                                .set("updated_at", now())
                                                .setSql(
                                                        "revision = revision + 1, retry_count ="
                                                                + " retry_count + 1")));
                failed++;
            }
        }
        return new PrintWorkReport(done + failed, done, failed, done + failed == budget.maxItems());
    }
}
