package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.impl.ReceptionPreparation.WalkInPayload;
import ai.neargo.shop.event.AfterCommit;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/** Bounded original resource reconciliation; no actor authorization is minted by a scheduled scan. */
public final class ReceptionRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(ReceptionRecovery.class);
    private final AppointmentPersistence db;
    private final AppointmentCoordinator appointments;
    private final AppointmentPreparation preparation;
    private final BeautyMappers.ReceptionOperationMapper rows;
    private final Clock clock;
    public ReceptionRecovery(AppointmentPersistence db, AppointmentCoordinator appointments,
            AppointmentPreparation preparation, BeautyMappers.ReceptionOperationMapper rows, Clock clock) {
        this.db = db; this.appointments = appointments; this.preparation = preparation; this.rows = rows;
        this.clock = clock; this.steps = new ReceptionSteps(db, rows);
    }
    private final ReceptionSteps steps;
    public int scan() { return scanReport().attempted(); }

    public RecoveryScanResult scanReport() {
        var cutoff = now();
        int attempted = 0, completed = 0, pending = 0, failedCount = 0;
        for (var row : recoverable(cutoff)) {
            var claimAt = now();
            var lease = claimAt.plusSeconds(30);
            if (!steps.claimResource(row, claimAt, lease)) { continue; }
            attempted++;
            boolean failed = false;
            try {
                var claimed = db.read(() -> rows.selectScoped(row.getEntityNo(), row.getStoreNo(),
                        row.getOperationNo()));
                var linked = preparation.linkedReception(claimed);
                appointments.verifiedReceipt(linked, false);
                appointments.recover(linked);
                var projected = project(claimed, lease);
                if ("REJECTED".equals(projected.getPhase()) || "SUCCEEDED".equals(projected.getPhase())) {
                    completed++;
                } else {
                    pending++;
                    if (BtyReceptionOperation.ARRIVAL_PENDING.equals(projected.getPhase())
                            || "RESOURCE_RELEASE_REQUIRED".equals(projected.getFailureCode())) {
                        String nextAction = BtyReceptionOperation.ARRIVAL_PENDING.equals(projected.getPhase())
                                ? "AUTHENTICATED_OPERATION_RECOVERY" : "AUTHENTICATED_CANCEL";
                        LOG.info("beauty.reception.recovery_handoff operationNo={} nextAction={}",
                                projected.getOperationNo(), nextAction);
                    }
                }
            } catch (RuntimeException failure) {
                failed = true;
                failedCount++;
                LOG.warn("Reception reconciliation deferred operation={} failure={}", row.getOperationNo(),
                        failure.getClass().getSimpleName());
            } finally {
                int backoffSeconds = failed ? 60 : 30;
                steps.releaseResource(row, lease, locked ->
                        BtyReceptionOperation.RESOURCE_PENDING.equals(locked.getPhase()) && locked.getFailureCode() == null
                                ? now().plusSeconds(backoffSeconds) : null);
            }
        }
        return new RecoveryScanResult(attempted, completed, pending, failedCount);
    }
    /**
     * 本轮能补的接待（至多 50 个）：行业库按接待操作自己的条件翻页取候选，再逐个到预约侧核对能否恢复
     * （TDD-美业域迁移新库 B-5d：原来是一条跨接待操作与预约侧四张表的查询）。被预约侧挡住的候选跳过，接着往后翻。
     */
    List<BtyReceptionOperation> recoverable(LocalDateTime cutoff) {
        List<BtyReceptionOperation> result = new ArrayList<>();
        LocalDateTime afterRetryAt = null;
        Long afterId = null;
        while (result.size() < 50) {
            final LocalDateTime retryCursor = afterRetryAt;
            final Long idCursor = afterId;
            var page = db.read(() -> rows.selectResourceCandidates(cutoff, retryCursor, idCursor, 100));
            for (var row : page) {
                if (result.size() < 50 && db.read(() -> db.operations.receptionRecoverable(row.getTenantNo(),
                        row.getEntityNo(), row.getStoreNo(), row.getAppointmentNo(),
                        row.getAppointmentOperationNo())) == 1) {
                    result.add(row);
                }
            }
            if (page.size() < 100) break;
            afterRetryAt = page.get(page.size() - 1).getNextRetryAt();
            afterId = page.get(page.size() - 1).getId();
        }
        return result;
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0); }
    /** 预约侧的资源结果：已确认（带预约版本）、要释放、或被拒。 */
    private record Outcome(String state, Long rootVersion) { }

    private BtyReceptionOperation project(BtyReceptionOperation observed, LocalDateTime lease) {
        return steps.advance(observed, java.util.Set.of(BtyReceptionOperation.RESOURCE_PENDING), () -> {
            var root = db.lock(db.root(observed.getAppointmentNo()));
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo());
            var linked = preparation.linkedReception(observed);
            if ("PENDING".equals(linked.getState())) { return new Outcome("PENDING", null); }
            var receipt = appointments.verifiedReceipt(linked, true);
            AppointmentPersistence.require(fence.getActiveIntentNo() == null && current.getOperationNo() == null);
            if ("SUCCEEDED".equals(linked.getState())) {
                AppointmentPersistence.require("CONFIRM".equals(receipt.binding().action())
                        && "SUCCEEDED".equals(receipt.status()) && "CONFIRMED".equals(root.getStatus())
                        && root.getHoldNo() != null);
            } else if (!"RELEASE_REQUIRED".equals(linked.getState())) {
                AppointmentPersistence.require("REJECTED".equals(linked.getState())
                        && "RESERVE".equals(receipt.binding().action()) && "REJECTED".equals(receipt.status())
                        && root.getHoldNo() == null);
            }
            return new Outcome(linked.getState(), root.getVersion());
        }, (row, outcome) -> {
            if (!Objects.equals(lease, row.getLeaseUntil()) || !row.getVersion().equals(observed.getVersion())
                    || "PENDING".equals(outcome.state())) { return row; }
            AppointmentPersistence.require(row.getRequestHash().equals(observed.getRequestHash())
                    && row.getFrozenPayload().equals(observed.getFrozenPayload())
                    && row.getAppointmentOperationNo().equals(observed.getAppointmentOperationNo()));
            if ("SUCCEEDED".equals(outcome.state())) {
                AppointmentPersistence.require(row.getWorkOrderNo() == null);
                var p = db.json.readValue(row.getFrozenPayload(), WalkInPayload.class);
                row.setFrozenPayload(db.json.writeValueAsString(p.withRevisions(outcome.rootVersion(), null)));
                row.setPhase(BtyReceptionOperation.ARRIVAL_PENDING);
            } else if ("RELEASE_REQUIRED".equals(outcome.state())) {
                row.setFailureCode("RESOURCE_RELEASE_REQUIRED");
            } else {
                row.setPhase("REJECTED"); row.setFailureCode("RESOURCE_REJECTED");
            }
            steps.save(row);
            String requestId = MDC.get("requestId");
            String receptionState = row.getPhase();
            String appointmentState = outcome.state();
            AfterCommit.run("beauty.reception.recovery_projected", () -> LOG.info(
                    "beauty.reception.recovery_projected requestId={} operationNo={} appointmentNo={}"
                            + " appointmentOperationNo={} receptionState={} appointmentOperationState={}",
                    requestId, observed.getOperationNo(), observed.getAppointmentNo(),
                    observed.getAppointmentOperationNo(), receptionState, appointmentState));
            return row;
        }, row -> row);
    }
}
