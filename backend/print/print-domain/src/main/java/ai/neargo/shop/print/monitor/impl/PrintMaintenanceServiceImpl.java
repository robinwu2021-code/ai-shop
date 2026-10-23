package ai.neargo.shop.print.monitor.impl;

import static ai.neargo.shop.print.support.PrintSupport.*;

import ai.neargo.shop.print.api.*;
import ai.neargo.shop.print.api.dto.PrintConfigDtos.*;
import ai.neargo.shop.print.api.dto.PrintJobDtos.*;
import ai.neargo.shop.print.dispatch.*;
import ai.neargo.shop.print.dispatch.PrintStateModels.*;
import ai.neargo.shop.print.layout.*;
import ai.neargo.shop.print.monitor.*;
import ai.neargo.shop.print.persistence.entity.*;
import ai.neargo.shop.print.persistence.mapper.PrintMappers.*;
import ai.neargo.shop.print.support.*;
import ai.neargo.shop.print.template.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
public class PrintMaintenanceServiceImpl implements PrintMaintenanceService {
    private final PrnAttemptMapper attempts;
    private final PrnJobMapper jobs;
    private final PrnPrinterMapper printers;
    private final PrnPrinterRuntimeMapper runtimes;
    private final PrnRequestMapper requests;
    private final PrnScenePolicyMapper policies;
    private final PrnReconcileCursorMapper cursors;
    private final PrnOperationMapper operations;
    private final PrnIncidentMapper incidentRows;
    private final PrnAgentMapper agents;
    private final PrintDispatchService dispatch;
    private final PrintReceiptService receipts;
    private final PrintIncidentService incidents;
    private final PrintReconcileJob reconcile;
    private final PrintJsonCodec json;
    private final List<PrintDeviceProbePort> probes;
    private final TransactionTemplate tx;

    public PrintMaintenanceServiceImpl(
            PrnAttemptMapper at,
            PrnJobMapper j,
            PrnPrinterMapper p,
            PrnPrinterRuntimeMapper rt,
            PrnRequestMapper req,
            PrnScenePolicyMapper policy,
            PrnReconcileCursorMapper c,
            PrnOperationMapper o,
            PrnIncidentMapper ir,
            PrnAgentMapper a,
            PrintDispatchService d,
            PrintReceiptService r,
            PrintIncidentService i,
            PrintReconcileJob rc,
            PrintJsonCodec codec,
            List<PrintDeviceProbePort> probes,
            @Qualifier("transactionManager") PlatformTransactionManager manager) {
        attempts = at;
        jobs = j;
        printers = p;
        runtimes = rt;
        requests = req;
        policies = policy;
        cursors = c;
        operations = o;
        incidentRows = ir;
        agents = a;
        dispatch = d;
        receipts = r;
        incidents = i;
        reconcile = rc;
        json = codec;
        this.probes = probes;
        tx = new TransactionTemplate(manager);
    }

    public PrintWorkReport maintain(PrintWorkerBudget budget) {
        int done = 0, failed = 0;
        long end = System.nanoTime() + budget.timeBudget().toNanos();
        for (var at :
                attempts.selectList(
                        new QueryWrapper<PrnAttempt>()
                                .in(
                                        "status",
                                        List.of(
                                                "PREPARED",
                                                "SUBMITTING",
                                                "ACCEPTED",
                                                "PRINTING",
                                                "UNKNOWN"))
                                .le("next_query_at", now())
                                .orderByAsc("next_query_at", "id")
                                .last("LIMIT " + budget.maxItems()))) {
            if (System.nanoTime() >= end) break;
            try {
                var j =
                        jobs.selectOne(
                                new QueryWrapper<PrnJob>()
                                        .eq("tenant_no", at.getTenantNo())
                                        .eq("entity_no", at.getEntityNo())
                                        .eq("job_no", at.getJobNo()));
                if (j == null) continue;
                var scope = new PrintScope(j.getTenantNo(), j.getEntityNo(), j.getStoreNo());
                var p =
                        printers.selectOne(
                                PrintSupport.<PrnPrinter>scoped(scope)
                                        .eq("printer_no", at.getPrinterNo()));
                if (p != null
                        && "CLOUD".equals(p.getConnectionType())
                        && at.getProviderJobId() != null)
                    dispatch.reconcileAttempt(
                            new PrintAttemptRef(
                                    scope,
                                    at.getAttemptNo(),
                                    at.getProviderScope(),
                                    at.getProviderJobId(),
                                    at.getDispatchKey(),
                                    at.getCredentialRef(),
                                    json.freeze(at.getConnectionSnapshot())));
                else if (at.getCreatedAt().isBefore(now().minusSeconds(120))) {
                    receipts.ingest(
                            new VerifiedPrintReceipt(
                                    scope,
                                    "TIMEOUT",
                                    at.getAttemptNo(),
                                    at.getAttemptNo(),
                                    ObservedPrintStatus.UNKNOWN,
                                    PrintEvidenceLevel.NONE,
                                    null,
                                    instant(at.getCreatedAt().plusSeconds(120)),
                                    json.freeze(
                                            Map.of(
                                                    "queueTerminated",
                                                    false,
                                                    "partialOutput",
                                                    false))));
                    attempts.update(
                            null,
                            PrintSupport.<PrnAttempt>ownedUpdate(scope)
                                    .eq("attempt_no", at.getAttemptNo())
                                    .set("next_query_at", now().plusSeconds(60)));
                }
                done++;
            } catch (Exception ex) {
                failed++;
            }
        }
        for (var j :
                jobs.selectList(
                        new QueryWrapper<PrnJob>()
                                .in(
                                        "status",
                                        List.of(
                                                "UNKNOWN",
                                                "FAILED",
                                                "STALE",
                                                "PENDING",
                                                "ACCEPTED",
                                                "DISPATCHING",
                                                "PRINTING"))
                                .orderByAsc("updated_at")
                                .last("LIMIT " + budget.maxItems()))) {
            if (System.nanoTime() >= end) break;
            var scope = new PrintScope(j.getTenantNo(), j.getEntityNo(), j.getStoreNo());
            if (Set.of("PENDING", "DISPATCHING", "ACCEPTED", "PRINTING").contains(j.getStatus())
                    && j.getUpdatedAt().isAfter(now().minusSeconds(120))) continue;
            incidents.open(
                    new PrintIncidentCommand(
                            scope,
                            "JOB_" + j.getStatus(),
                            "JOB",
                            j.getJobNo(),
                            "WARN",
                            json.freeze(Map.of("status", j.getStatus()))));
        }
        for (var r :
                requests.selectList(
                        new QueryWrapper<PrnRequest>()
                                .eq("status", "BLOCKED")
                                .orderByAsc("updated_at")
                                .last("LIMIT " + budget.maxItems()))) {
            if (System.nanoTime() >= end) break;
            incidents.open(
                    new PrintIncidentCommand(
                            new PrintScope(r.getTenantNo(), r.getEntityNo(), r.getStoreNo()),
                            "REQUEST_BLOCKED",
                            "REQUEST",
                            r.getRequestNo(),
                            "WARN",
                            json.freeze(
                                    Map.of(
                                            "blockCode",
                                            r.getBlockCode() == null
                                                    ? "UNKNOWN"
                                                    : r.getBlockCode()))));
        }
        for (var g :
                agents.selectList(
                        new QueryWrapper<PrnAgent>()
                                .eq("status", "ACTIVE")
                                .lt("last_seen_at", now().minusSeconds(45))
                                .exists(
                                        "SELECT 1 FROM prn_printer p WHERE"
                                                + " p.tenant_no=prn_agent.tenant_no AND"
                                                + " p.entity_no=prn_agent.entity_no AND"
                                                + " p.agent_no=prn_agent.agent_no AND"
                                                + " p.health_status<>'OFFLINE'")
                                .last("LIMIT " + budget.maxItems()))) {
            printers.update(
                    null,
                    PrintSupport.<PrnPrinter>ownedUpdate(
                                    new PrintScope(
                                            g.getTenantNo(), g.getEntityNo(), g.getStoreNo()))
                            .eq("agent_no", g.getAgentNo())
                            .set("health_status", "OFFLINE")
                            .set("health_observed_at", now())
                            .set("health_reason", "AGENT_HEARTBEAT_TIMEOUT")
                            .set("updated_at", now())
                            .setSql("revision = revision + 1"));
        }
        for (var o :
                operations.selectList(
                        new QueryWrapper<PrnOperation>()
                                .eq("status", "PENDING")
                                .eq("operation_type", "PROBE_PRINTER")
                                .exists(
                                        "SELECT 1 FROM prn_printer p WHERE"
                                                + " p.tenant_no=prn_operation.tenant_no AND"
                                                + " p.entity_no=prn_operation.entity_no AND"
                                                + " p.printer_no=prn_operation.target_no AND"
                                                + " p.connection_type='CLOUD'")
                                .orderByAsc("id")
                                .last("LIMIT " + budget.maxItems()))) {
            if (System.nanoTime() >= end) break;
            try {
                var scope = new PrintScope(o.getTenantNo(), o.getEntityNo(), o.getStoreNo());
                var p =
                        printers.selectOne(
                                PrintSupport.<PrnPrinter>scoped(scope)
                                        .eq("printer_no", o.getTargetNo()));
                if (p == null || !"CLOUD".equals(p.getConnectionType())) continue;
                var probe =
                        probes.stream()
                                .filter(pr -> pr.adapterCode().equals(p.getAdapterCode()))
                                .findFirst()
                                .orElseThrow();
                var result =
                        probe.probe(
                                scope,
                                p.getCredentialRef(),
                                json.freeze(Map.of("deviceSn", p.getDeviceSn())));
                tx.executeWithoutResult(
                        status -> {
                            printers.update(
                                    null,
                                    PrintSupport.<PrnPrinter>ownedUpdate(scope)
                                            .eq("printer_no", p.getPrinterNo())
                                            .eq("connection_revision", p.getConnectionRevision())
                                            .set("health_status", result.health().name())
                                            .set(
                                                    "health_observed_at",
                                                    LocalDateTime.ofInstant(
                                                            result.observedAt(), ZoneOffset.UTC))
                                            .set("health_reason", result.reason())
                                            .set("updated_at", now())
                                            .setSql("revision = revision + 1"));
                            var operation =
                                    new PrintOperation(
                                            o.getOperationNo(),
                                            o.getOperationType(),
                                            PrintOperationStatus.SUCCEEDED,
                                            o.getTargetType(),
                                            o.getTargetNo(),
                                            json.freeze(result),
                                            null,
                                            instant(o.getCreatedAt()),
                                            Instant.now());
                            operations.update(
                                    null,
                                    PrintSupport.<PrnOperation>ownedUpdate(scope)
                                            .eq("operation_no", o.getOperationNo())
                                            .eq("status", "PENDING")
                                            .set("status", "SUCCEEDED")
                                            .set("result_ref", json.write(operation))
                                            .set("finished_at", now())
                                            .set("updated_at", now())
                                            .setSql("revision = revision + 1"));
                        });
            } catch (Exception e) {
                failed++;
            }
        }
        for (var i :
                incidentRows.selectList(
                        new QueryWrapper<PrnIncident>()
                                .in("status", List.of("OPEN", "ACKNOWLEDGED"))
                                .eq("target_type", "JOB")
                                .exists(
                                        "SELECT 1 FROM prn_job j WHERE"
                                                + " j.tenant_no=prn_incident.tenant_no AND"
                                                + " j.entity_no=prn_incident.entity_no AND"
                                                + " j.job_no=prn_incident.target_no AND"
                                                + " (j.status='COMPLETED' OR"
                                                + " j.resolution_status='RESOLVED')")
                                .orderByAsc("id")
                                .last("LIMIT " + budget.maxItems()))) {
            if (System.nanoTime() >= end) break;
            var scope = new PrintScope(i.getTenantNo(), i.getEntityNo(), i.getStoreNo());
            var j =
                    jobs.selectOne(
                            PrintSupport.<PrnJob>scoped(scope).eq("job_no", i.getTargetNo()));
            if (j != null
                    && ("COMPLETED".equals(j.getStatus())
                            || "RESOLVED".equals(j.getResolutionStatus())))
                incidents.resolveByEvidence(
                        scope,
                        i.getIncidentNo(),
                        json.freeze(
                                Map.of(
                                        "jobNo",
                                        j.getJobNo(),
                                        "status",
                                        j.getStatus(),
                                        "resolutionStatus",
                                        j.getResolutionStatus())));
        }
        for (var policy :
                policies.selectList(
                        new QueryWrapper<PrnScenePolicy>()
                                .eq("enabled", 1)
                                .eq("scene", "ORDER_PAID")
                                .last(
                                        "ORDER BY (SELECT c.last_success_at FROM"
                                                + " prn_reconcile_cursor c WHERE"
                                                + " c.tenant_no=prn_scene_policy.tenant_no AND"
                                                + " c.entity_no=prn_scene_policy.entity_no AND"
                                                + " c.store_no=prn_scene_policy.store_no AND"
                                                + " c.source_kind='SUB_ORDER'), id LIMIT "
                                                + budget.maxItems()))) {
            if (System.nanoTime() >= end) break;
            try {
                var scope =
                        new PrintScope(
                                policy.getTenantNo(), policy.getEntityNo(), policy.getStoreNo());
                var cursor =
                        cursors.selectOne(
                                PrintSupport.<PrnReconcileCursor>scoped(scope)
                                        .eq("source_kind", "SUB_ORDER"));
                Instant from =
                        cursor != null && cursor.getWatermarkAt() != null
                                ? instant(cursor.getWatermarkAt()).minusSeconds(60)
                                : Instant.now().minus(Duration.ofDays(1));
                reconcile.reconcile(
                        new PrintMaintenanceCommand(
                                scope, "SUB_ORDER", from, Instant.now(), 100, false));
            } catch (Exception e) {
                failed++;
            }
        }
        for (var o :
                operations.selectList(
                        new QueryWrapper<PrnOperation>()
                                .eq("status", "PENDING")
                                .in("operation_type", List.of("CANCEL_JOB", "PROBE_PRINTER"))
                                .orderByAsc("created_at")
                                .last("LIMIT " + budget.maxItems()))) {
            var scope = new PrintScope(o.getTenantNo(), o.getEntityNo(), o.getStoreNo());
            var j =
                    "CANCEL_JOB".equals(o.getOperationType())
                            ? jobs.selectOne(
                                    PrintSupport.<PrnJob>scoped(scope)
                                            .eq("job_no", o.getTargetNo()))
                            : null;
            String finalStatus =
                    j != null && "CANCELLED".equals(j.getStatus())
                            ? "SUCCEEDED"
                            : j != null && Set.of("COMPLETED", "FAILED").contains(j.getStatus())
                                    ? "FAILED"
                                    : o.getCreatedAt().isBefore(now().minusSeconds(65))
                                            ? "UNKNOWN"
                                            : null;
            if (finalStatus != null) {
                var result =
                        new PrintOperation(
                                o.getOperationNo(),
                                o.getOperationType(),
                                PrintOperationStatus.valueOf(finalStatus),
                                o.getTargetType(),
                                o.getTargetNo(),
                                json.freeze(
                                        Map.of(
                                                "reason",
                                                "COMMAND_RESULT_PROJECTED",
                                                "jobStatus",
                                                j == null ? "UNKNOWN" : j.getStatus())),
                                null,
                                instant(o.getCreatedAt()),
                                Instant.now());
                operations.update(
                        null,
                        PrintSupport.<PrnOperation>ownedUpdate(scope)
                                .eq("operation_no", o.getOperationNo())
                                .eq("status", "PENDING")
                                .set("status", finalStatus)
                                .set("result_ref", json.write(result))
                                .set("finished_at", now())
                                .set("updated_at", now())
                                .setSql("revision = revision + 1"));
            }
        }
        return new PrintWorkReport(done + failed, done, failed, done + failed >= budget.maxItems());
    }
}
