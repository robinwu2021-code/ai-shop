package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded lease-CAS scanner reads original terminals only; audit usernames never become an authenticated session. */
public final class AppointmentRecovery {
    private static final Logger LOG = LoggerFactory.getLogger(AppointmentRecovery.class);
    private final AppointmentPersistence db;
    private final AppointmentCoordinator coordinator;
    public AppointmentRecovery(AppointmentPersistence db, AppointmentCoordinator coordinator) {
        this.db = db; this.coordinator = coordinator;
    }
    public int scan() {
        var now = AppointmentPersistence.now();
        var rows = db.read(() -> db.operations.selectList(Wrappers.<BtyAppointmentOperation>lambdaQuery()
                .eq(BtyAppointmentOperation::getTenantNo, "MAIN").eq(BtyAppointmentOperation::getState, "PENDING")
                .le(BtyAppointmentOperation::getNextRetryAt, now).orderByAsc(BtyAppointmentOperation::getId)
                .last("LIMIT 50")));
        int recovered = 0;
        for (var row : rows) {
            boolean claimed = db.tx(() -> db.operations.claim(row.getEntityNo(), row.getOperationNo(), row.getVersion(),
                    now, now.plusSeconds(30)) == 1);
            if (!claimed) { continue; }
            boolean failed = false;
            try {
                coordinator.recover(db.operation(row.getOperationNo()));
                recovered++;
            } catch (RuntimeException failure) {
                failed = true;
                // Do not log owner payloads or actor data. One corrupt original must not block later operations.
                LOG.warn("Appointment recovery deferred operation={} failure={}", row.getOperationNo(),
                        failure.getClass().getSimpleName());
            } finally {
                var nextAttempt = now.plusSeconds(failed ? 60 : 30);
                db.tx(() -> {
                    db.operations.update(null, Wrappers.<BtyAppointmentOperation>lambdaUpdate()
                            .eq(BtyAppointmentOperation::getId, row.getId())
                            .eq(BtyAppointmentOperation::getLeaseUntil, now.plusSeconds(30))
                            .set(BtyAppointmentOperation::getNextRetryAt, nextAttempt)
                            .set(BtyAppointmentOperation::getLeaseUntil, null));
                    return null;
                });
            }
        }
        return recovered;
    }
}
