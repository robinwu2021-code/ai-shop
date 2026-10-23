package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.dto.AppointmentAssignmentReq;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;

/** Shared reception replacement owns one real implementation without a coordinator dependency cycle. */
public final class ReceptionReassignment {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ReceptionAccess receptionAccess;
    private final AppointmentCoordinator appointments;
    private final AppointmentMatching matching;
    private final AppointmentPreparationService appointmentPreparations;
    private final WorkOrderPreparation workOrders;
    public ReceptionReassignment(AppointmentPersistence db, AppointmentAccess access, ReceptionAccess receptionAccess,
            AppointmentCoordinator appointments, AppointmentMatching matching,
            AppointmentPreparationService appointmentPreparations, WorkOrderPreparation workOrders) {
        this.db = db; this.access = access; this.receptionAccess = receptionAccess; this.appointments = appointments;
        this.matching = matching; this.appointmentPreparations = appointmentPreparations; this.workOrders = workOrders;
    }
    public BtyAppointmentOperation reassign(String entity, String store, String actor, String appointment,
            long revision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt, String key) {
        return reassign(entity, store, actor, appointment, revision, assignments, startAt, key, null);
    }
    /** Internal preparation for later work-order opening; no order or task is created here. */
    public BtyAppointmentOperation reassign(String entity, String store, String actor, String appointment,
            long revision, List<AppointmentAssignmentReq> assignments, OffsetDateTime startAt, String key,
                    String receptionOperationNo) {
        access.merchant(entity, store, actor, true); receptionAccess.customer(entity, store, actor);
        var root = scoped(entity, store, appointment);
        String hash = IdempotencyScope.requestHash("RECEPTION_CHANGE_V1", appointment, Long.toString(revision),
                db.json.writeValueAsString(assignments), db.json.writeValueAsString(startAt));
        var prior = appointmentPreparations.find(entity, store, key, hash);
        if (prior != null) { return appointments.advance(prior.getOperationNo()); }
        AppointmentPersistence.require(root.getVersion() == revision && "CHECKED_IN".equals(root.getStatus()));
        var replacement = appointments.replacementForReception(root);
        var prepared = workOrders.fromAppointment(root);
        List<AppointmentAssignmentReq> existing = new ArrayList<>();
        for (int i = 0; i < prepared.frozen().items().size(); i++) {
            String item = prepared.frozen().items().get(i).appointmentItemNo();
            for (var a : prepared.assignments()) {
                if (item.equals(a.appointmentItemNo())) {
                    existing.add(new AppointmentAssignmentReq(i, a.requirementNo(), a.resourceNo(), a.personnelNo()));
                }
            }
        }
        boolean retained = assignments == null || assignments.isEmpty();
        var requested = retained ? List.copyOf(existing) : List.copyOf(assignments);
        if (!retained && new HashSet<>(requested).size() != requested.size()) {
            throw BizException.of(ErrorCode.BAD_REQUEST);
        }
        boolean same = retained || matchesExistingAssignments(existing, requested);
        var chosenStart = startAt == null ? root.getStartAt().atOffset(ZoneOffset.UTC) : startAt;
        if (same && chosenStart.toInstant().equals(root.getStartAt().toInstant(ZoneOffset.UTC))) { return null; }
        var match = matching.matchFrozen(matching.scope(store), prepared.frozen(), requested, chosenStart, replacement,
                retained);
        var preview = appointmentPreparations.save(match, null, root, revision);
        List<ItemMapping> mappings = new ArrayList<>();
        for (int i = 0; i < match.snapshot().items().size(); i++) {
            mappings.add(new ItemMapping(prepared.frozen().items().get(i).appointmentItemNo(),
                    match.snapshot().items().get(i).appointmentItemNo()));
        }
        var op = appointmentPreparations.prepareReception(db.preview(preview.previewNo()), root, revision, key, hash,
                prepared, workOrders, db.json.writeValueAsString(new ReplacementSnapshot(mappings)),
                        receptionOperationNo);
        return appointments.advance(op.getOperationNo());
    }
    /** Consume each persisted unit once; a broad personnel request cannot consume a fixed resource first. */
    private boolean matchesExistingAssignments(List<AppointmentAssignmentReq> existing,
            List<AppointmentAssignmentReq> requested) {
        if (existing.size() != requested.size()) { return false; }
        var remaining = new ArrayList<>(existing);
        var ordered = new ArrayList<>(requested);
        ordered.sort(Comparator.comparing(value -> value.resourceNo() == null));
        for (var value : ordered) {
            int found = -1;
            for (int i = 0; i < remaining.size(); i++) {
                var old = remaining.get(i);
                if (value.itemIndex() == old.itemIndex() && value.requirementNo().equals(old.requirementNo())
                        && (value.resourceNo() == null || value.resourceNo().equals(old.resourceNo()))
                        && (value.personnelNo() == null || value.personnelNo().equals(old.personnelNo()))) {
                    found = i;
                    break;
                }
            }
            if (found < 0) { return false; }
            remaining.remove(found);
        }
        return remaining.isEmpty();
    }

    private BtyAppointment scoped(String entity, String store, String appointment) {
        var root = db.root(appointment);
        if (root == null || !entity.equals(root.getEntityNo()) || !store.equals(root.getStoreNo())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return root;
    }
    private record ItemMapping(String oldItemNo, String newItemNo) { }
    private record ReplacementSnapshot(List<ItemMapping> items) { }
}
