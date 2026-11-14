package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyReceptionOperation;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.service.impl.ReceptionPreparation.WalkInPayload;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
        this.clock = clock;
    }
    public int scan() {
        var cutoff = now();
        int attempted = 0;
        for (var row : db.read(() -> rows.selectResourceRecoverable(cutoff))) {
            var claimAt = now();
            var lease = claimAt.plusSeconds(30);
            if (!db.tx(() -> rows.claimResource(row.getEntityNo(), row.getStoreNo(), row.getOperationNo(),
                    row.getVersion(), claimAt, lease) == 1)) { continue; }
            attempted++;
            boolean failed = false;
            try {
                var claimed = db.read(() -> rows.selectScoped(row.getEntityNo(), row.getStoreNo(),
                        row.getOperationNo()));
                var linked = preparation.linkedReception(claimed);
                appointments.verifiedReceipt(linked, false);
                appointments.recover(linked);
                project(claimed, lease);
            } catch (RuntimeException failure) {
                failed = true;
                LOG.warn("Reception reconciliation deferred operation={} failure={}", row.getOperationNo(),
                        failure.getClass().getSimpleName());
            } finally {
                int backoffSeconds = failed ? 60 : 30;
                db.tx(() -> {
                    var locked = rows.selectScopedForUpdate(row.getEntityNo(), row.getStoreNo(), row.getOperationNo());
                    if (!Objects.equals(lease, locked.getLeaseUntil())) { return null; }
                    boolean retry = "RESOURCE_PENDING".equals(locked.getPhase()) && locked.getFailureCode() == null;
                    rows.update(null, Wrappers.<BtyReceptionOperation>lambdaUpdate()
                            .eq(BtyReceptionOperation::getId, locked.getId())
                            .eq(BtyReceptionOperation::getVersion, locked.getVersion())
                            .eq(BtyReceptionOperation::getLeaseUntil, lease)
                            .set(BtyReceptionOperation::getNextRetryAt,
                                    retry ? now().plusSeconds(backoffSeconds) : null)
                            .set(BtyReceptionOperation::getLeaseUntil, null));
                    return null;
                });
            }
        }
        return attempted;
    }
    private LocalDateTime now() { return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).withNano(0); }
    private void project(BtyReceptionOperation observed, LocalDateTime lease) {
        db.tx(() -> {
            var root = db.lock(db.root(observed.getAppointmentNo()));
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var current = db.currents.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo());
            var row = rows.selectScopedForUpdate(observed.getEntityNo(), observed.getStoreNo(),
                    observed.getOperationNo());
            if (!"RESOURCE_PENDING".equals(row.getPhase()) || !Objects.equals(lease, row.getLeaseUntil())
                    || !row.getVersion().equals(observed.getVersion())) { return null; }
            AppointmentPersistence.require(row.getRequestHash().equals(observed.getRequestHash())
                    && row.getFrozenPayload().equals(observed.getFrozenPayload())
                    && row.getAppointmentOperationNo().equals(observed.getAppointmentOperationNo()));
            var linked = preparation.linkedReception(row);
            if ("PENDING".equals(linked.getState())) { return null; }
            var receipt = appointments.verifiedReceipt(linked, true);
            AppointmentPersistence.require(fence.getActiveIntentNo() == null && current.getOperationNo() == null);
            if ("SUCCEEDED".equals(linked.getState())) {
                AppointmentPersistence.require("CONFIRM".equals(receipt.binding().action())
                        && "SUCCEEDED".equals(receipt.status()) && "CONFIRMED".equals(root.getStatus())
                        && root.getHoldNo() != null && row.getWorkOrderNo() == null);
                var p = db.json.readValue(row.getFrozenPayload(), WalkInPayload.class);
                row.setFrozenPayload(db.json.writeValueAsString(p.withRevisions(root.getVersion(), null)));
                row.setPhase("ARRIVAL_PENDING");
            } else if ("RELEASE_REQUIRED".equals(linked.getState())) {
                row.setFailureCode("RESOURCE_RELEASE_REQUIRED");
            } else {
                AppointmentPersistence.require("REJECTED".equals(linked.getState())
                        && "RESERVE".equals(receipt.binding().action()) && "REJECTED".equals(receipt.status())
                        && root.getHoldNo() == null);
                row.setPhase("REJECTED"); row.setFailureCode("RESOURCE_REJECTED");
            }
            AppointmentPersistence.require(rows.updateById(row) == 1);
            return null;
        });
    }
}
