package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.beauty.entity.BtyAppointmentArrangement;
import ai.neargo.shop.beauty.entity.BtyAppointmentAssignment;
import ai.neargo.shop.beauty.entity.BtyAppointmentItem;
import ai.neargo.shop.beauty.entity.BtyAppointmentOperation;
import ai.neargo.shop.beauty.entity.BtyAppointmentResourceIntent;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.event.outbox.OutboxPublisher;
import ai.neargo.shop.event.outbox.PublishOptions;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.beauty.BeautyAppointmentOperationCompleted;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceCandidatesPort;
import ai.neargo.shop.spi.resource.ResourceCommandFactsPort;
import ai.neargo.shop.spi.resource.ResourcePlanContract;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.ObjectProvider;

/** Durable saga with short source transactions. Every external IO occurs before or after source locks. */
public final class AppointmentCoordinator {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ObjectProvider<ResourceBookingPort> owner;
    private final OutboxPublisher publisher;
    public AppointmentCoordinator(AppointmentPersistence db, AppointmentAccess access,
            ObjectProvider<ResourceBookingPort> owner, OutboxPublisher publisher) {
        this.db = db;
        this.access = access;
        this.owner = owner;
        this.publisher = publisher;
    }

    public BtyAppointmentOperation advance(String operationNo) {
        var op = db.operation(operationNo);
        var root = db.root(op.getAppointmentNo());
        access.root(root, true);
        if ("PENDING".equals(op.getState())) {
            AppointmentPersistence.require(op.getOperationNo().equals(db.current(root).getOperationNo()));
        }
        for (int step = 0; step < 2 && "PENDING".equals(op.getState()); step++) {
            var request = db.json.readValue(op.getAcceptPayload(), ResourceBookingPort.Accept.class);
            ResourceBookingPort.Receipt receipt;
            if (op.getReceipt() == null) {
                try { receipt = owner.getObject().accept(access.actor(), request); }
                catch (RuntimeException uncertain) { return pending(op); }
                verify(request, receipt);
                bind(op, receipt);
            } else { receipt = db.json.readValue(op.getReceipt(), ResourceBookingPort.Receipt.class); }
            try {
                owner.getObject().execute(access.actor(), request.scope(), receipt.binding().commandNo());
            } catch (RuntimeException uncertain) {
                // A response failure is not an owner rejection. Query only the original accepted command below.
            }
            ResourceBookingPort.Receipt original;
            try {
                original = owner.getObject().query(access.actor(), request.scope(), receipt.binding().commandNo())
                        .orElse(null);
            } catch (RuntimeException uncertain) { return pending(db.operation(operationNo)); }
            if (original == null || "ACCEPTED".equals(original.status())) { return pending(db.operation(operationNo)); }
            verify(request, original);
            AppointmentPersistence.require(original.binding().equals(receipt.binding()));
            ResourceBookingPort.Hold hold;
            try { hold = terminalHold(original, false); }
            catch (RuntimeException uncertain) { return pending(db.operation(operationNo)); }
            finish(op, original, hold);
            op = db.operation(operationNo);
        }
        return op;
    }
    /** Startup recovery only reads committed original outcomes; never reconstitutes actor credentials. */
    public void recover(BtyAppointmentOperation op) {
        if (op.getReceipt() == null) { return; }
        var bound = db.json.readValue(op.getReceipt(), ResourceBookingPort.Receipt.class);
        var result = owner.getObject().reconcile(bound.binding()).orElse(null);
        if (result == null || "ACCEPTED".equals(result.status())) { return; }
        verify(db.json.readValue(op.getAcceptPayload(), ResourceBookingPort.Accept.class), result);
        AppointmentPersistence.require(bound.binding().equals(result.binding()));
        finish(op, result, terminalHold(result, true));
    }
    /** Ownership and the immutable successful arrangement establish the only hold capacity may exclude. */
    public ResourceCandidatesPort.Replacement replacement(BtyAppointment root) {
        access.root(root, true);
        var current = db.current(root);
        AppointmentPersistence.require(current != null && root.getHoldNo() != null
                && current.getOperationNo() == null && "CONFIRMED".equals(root.getStatus()));
        var history = db.read(() -> db.arrangements.selectOne(Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                .eq(BtyAppointmentArrangement::getTenantNo, "MAIN")
                .eq(BtyAppointmentArrangement::getEntityNo, root.getEntityNo())
                .eq(BtyAppointmentArrangement::getAppointmentNo, root.getAppointmentNo())
                .eq(BtyAppointmentArrangement::getArrangementRevision, current.getArrangementRevision())));
        var op = db.read(() -> db.operations.selectOne(Wrappers.<BtyAppointmentOperation>lambdaQuery()
                .eq(BtyAppointmentOperation::getTenantNo, "MAIN")
                .eq(BtyAppointmentOperation::getEntityNo, root.getEntityNo())
                .eq(BtyAppointmentOperation::getAppointmentNo, root.getAppointmentNo())
                .eq(BtyAppointmentOperation::getState, "SUCCEEDED")
                .in(BtyAppointmentOperation::getAction, "CREATE", "CHANGE")
                .orderByDesc(BtyAppointmentOperation::getId).last("LIMIT 1")));
        AppointmentPersistence.require(history != null && root.getHoldNo().equals(history.getHoldNo())
                && op != null && op.getReceipt() != null);
        var receipt = db.json.readValue(op.getReceipt(), ResourceBookingPort.Receipt.class);
        var b = receipt.binding();
        AppointmentPersistence.require("MAIN".equals(b.tenantNo()) && root.getEntityNo().equals(b.entityNo())
                && root.getStoreNo().equals(b.storeNo()) && root.getAppointmentNo().equals(b.sourceNo())
                && "BEAUTY_APPOINTMENT".equals(b.sourceType()) && "SUCCEEDED".equals(receipt.status())
                && List.of("CONFIRM", "REPLACE").contains(b.action()));
        var hold = terminalHold(receipt, false);
        AppointmentPersistence.require(root.getHoldNo().equals(hold.holdNo()));
        return new ResourceCandidatesPort.Replacement(b, new ResourceBookingPort.Target(hold.holdNo(),
                hold.version(), hold.leaseRevision()));
    }
    public ResourceBookingPort.Hold hold(BtyAppointment root) {
        access.root(root, true); // Source-owned proof precedes every owner query, including C requests.
        var current = db.current(root);
        var history = db.read(() -> db.arrangements.selectOne(Wrappers.<BtyAppointmentArrangement>lambdaQuery()
                .eq(BtyAppointmentArrangement::getTenantNo, "MAIN")
                .eq(BtyAppointmentArrangement::getEntityNo, root.getEntityNo())
                .eq(BtyAppointmentArrangement::getAppointmentNo, root.getAppointmentNo())
                .eq(BtyAppointmentArrangement::getArrangementRevision, current.getArrangementRevision())));
        var op = db.read(() -> db.operations.selectOne(Wrappers.<BtyAppointmentOperation>lambdaQuery()
                .eq(BtyAppointmentOperation::getTenantNo, "MAIN").eq(BtyAppointmentOperation::getEntityNo,
                        root.getEntityNo())
                .eq(BtyAppointmentOperation::getAppointmentNo, root.getAppointmentNo())
                .isNotNull(BtyAppointmentOperation::getReceipt).orderByDesc(BtyAppointmentOperation::getId)
                .last("LIMIT 1")));
        AppointmentPersistence.require(op != null && (history != null || "HELD".equals(root.getStatus())));
        var binding = db.json.readValue(op.getReceipt(), ResourceBookingPort.Receipt.class).binding();
        return owner.getObject().queryHold(access.actor(), binding, root.getHoldNo());
    }
    private BtyAppointmentOperation pending(BtyAppointmentOperation op) {
        db.tx(() -> {
            db.operations.update(null, Wrappers.<BtyAppointmentOperation>lambdaUpdate()
                    .eq(BtyAppointmentOperation::getId, op.getId()).eq(BtyAppointmentOperation::getState, "PENDING")
                    .set(BtyAppointmentOperation::getNextRetryAt, AppointmentPersistence.now().plusSeconds(30)));
            return null;
        });
        return db.operation(op.getOperationNo());
    }
    private void verify(ResourceBookingPort.Accept request, ResourceBookingPort.Receipt receipt) {
        var b = receipt.binding();
        AppointmentPersistence.require(request.scope().tenantNo().equals(b.tenantNo())
                && request.scope().entityNo().equals(b.entityNo()) && request.scope().storeNo().equals(b.storeNo())
                && request.sourceType().equals(b.sourceType()) && request.sourceNo().equals(b.sourceNo())
                && request.sourceRevision() == b.sourceRevision() && request.action().equals(b.action())
                && request.expectedOwnerVersion() == receipt.expectedOwnerVersion()
                && Objects.equals(request.target(), receipt.target())
                && request.frozenPayload().equals(receipt.frozenPayload())
                && ResourcePlanContract.hash(request.frozenPayload()).equals(b.planHash()));
    }
    private void bind(BtyAppointmentOperation operation, ResourceBookingPort.Receipt receipt) {
        db.tx(() -> {
            var root = db.lock(db.root(operation.getAppointmentNo()));
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var op = db.operation(operation.getOperationNo());
            AppointmentPersistence.require(op.getAcceptPayload().equals(operation.getAcceptPayload()));
            if (op.getReceipt() != null) {
                AppointmentPersistence.require(db.json.readValue(op.getReceipt(), ResourceBookingPort.Receipt.class)
                        .binding().equals(receipt.binding()));
                return null;
            }
            AppointmentPersistence.require(op.getOperationNo().equals(db.current(root).getOperationNo())
                    && root.getVersion() == receipt.binding().sourceRevision());
            var intent = new BtyAppointmentResourceIntent();
            var b = receipt.binding();
            intent.setEntityNo(root.getEntityNo()); intent.setStoreNo(root.getStoreNo());
            intent.setAppointmentNo(root.getAppointmentNo()); intent.setIntentNo(BizKey.next("BTI"));
            intent.setCommandNo(b.commandNo()); intent.setAction(b.action());
                    intent.setSourceRevision(b.sourceRevision());
            intent.setCommandHash(b.commandHash()); intent.setPlanHash(b.planHash());
            intent.setFrozenPlan(receipt.frozenPayload()); intent.setPhaseCode(0);
                    intent.setInitiatedBy(op.getActorNo());
            db.intents.insert(intent);
            AppointmentPersistence.require(db.fences.acquire(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo(), intent.getIntentNo(), fence.getVersion(), op.getActorNo(),
                    AppointmentPersistence.now()) == 1);
            op.setReceipt(db.json.writeValueAsString(receipt));
                    AppointmentPersistence.require(db.operations.updateById(op) == 1);
            return null;
        });
    }
    private ResourceBookingPort.Hold terminalHold(ResourceBookingPort.Receipt receipt, boolean recovery) {
        if (!"SUCCEEDED".equals(receipt.status())) { return null; }
        var result = db.json.readTree(receipt.resultPayload());
        String number = result.path("holdNo").asString();
        var hold = recovery ? owner.getObject().reconcileHold(receipt.binding(), number)
                : owner.getObject().queryHold(access.actor(), receipt.binding(), number);
        String expected = switch (receipt.binding().action()) {
            case "RESERVE" -> "HELD"; case "CONFIRM", "REPLACE" -> "CONFIRMED"; default -> "RELEASED";
        };
        AppointmentPersistence.require(number.equals(hold.holdNo()) && expected.equals(hold.status())
                && result.path("status").asString().equals(expected)
                && result.path("ownerRevision").asLong() == hold.ownerVersion()
                && ("RELEASE".equals(receipt.binding().action())
                    || receipt.binding().sourceRevision() == hold.sourceRevision())
                && receipt.binding().planHash().equals(hold.planHash()));
        return hold;
    }
    private void finish(BtyAppointmentOperation operation, ResourceBookingPort.Receipt receipt,
            ResourceBookingPort.Hold hold) {
        db.tx(() -> {
            var root = db.lock(db.root(operation.getAppointmentNo()));
            var fence = db.fences.selectScopedForUpdate(root.getEntityNo(), root.getStoreNo(), root.getAppointmentNo());
            var intent = db.intents.selectByCommandForUpdate(root.getEntityNo(), root.getStoreNo(),
                    receipt.binding().commandNo());
            var op = db.operation(operation.getOperationNo());
            if (intent.getPhaseCode() == 3) { return null; }
            AppointmentPersistence.require(op.getReceipt() != null
                    && db.json.readValue(op.getReceipt(), ResourceBookingPort.Receipt.class).binding()
                            .equals(receipt.binding()));
            boolean success = "SUCCEEDED".equals(receipt.status());
            if (intent.getPhaseCode() == 0) {
                AppointmentPersistence.require(!success); // No source permission means no successful owner effect.
                intent.setPhaseCode(2); intent.setDecisionNo(BizKey.next("BTD"));
                intent.setReasonCode("OWNER_REJECTED_BEFORE_PERMISSION");
                        AppointmentPersistence.require(db.intents.updateById(intent) == 1);
                intent = db.intents.selectByCommandForUpdate(root.getEntityNo(), root.getStoreNo(),
                        receipt.binding().commandNo());
            }
            var status = ResourceCommandFactsPort.Status.valueOf(receipt.status());
            String hash = ResourceCommandFactsPort.fingerprint(receipt.binding(), status, receipt.revision(),
                    receipt.resultPayload(), receipt.rejectionCode());
            AppointmentPersistence.require(db.intents.reconcile(root.getEntityNo(), root.getStoreNo(),
                    intent.getIntentNo(), intent.getVersion(), success ? 1 : 2, hash, op.getActorNo(),
                    AppointmentPersistence.now()) == 1);
            AppointmentPersistence.require(db.fences.releaseReconciled(root.getEntityNo(), root.getStoreNo(),
                    root.getAppointmentNo(), intent.getIntentNo(), fence.getVersion(), op.getActorNo(),
                    AppointmentPersistence.now()) == 1);
            op.setResult(receipt.resultPayload());
            op.setReceipt(db.json.writeValueAsString(receipt));
            if (success && "RESERVE".equals(receipt.binding().action())) {
                root.setHoldNo(hold.holdNo()); AppointmentPersistence.require(db.roots.updateById(root) == 1);
                var next = new ResourceBookingPort.Accept(new ResourceBookingPort.Scope("MAIN", root.getEntityNo(),
                        root.getStoreNo()), "CONFIRM", op.getOperationNo() + ":CONFIRM", "BEAUTY_APPOINTMENT",
                        root.getAppointmentNo(), hold.ownerVersion(), root.getVersion(),
                        new ResourceBookingPort.Target(hold.holdNo(), hold.version(), hold.leaseRevision()),
                        receipt.frozenPayload());
                op.setAcceptPayload(db.json.writeValueAsString(next));
                db.operations.update(null, Wrappers.<BtyAppointmentOperation>lambdaUpdate()
                        .eq(BtyAppointmentOperation::getId, op.getId()).set(BtyAppointmentOperation::getReceipt, null)
                        .set(BtyAppointmentOperation::getResult, null)
                        .set(BtyAppointmentOperation::getAcceptPayload, op.getAcceptPayload()));
                return null;
            }
            if (success) {
                if ("RELEASE".equals(receipt.binding().action())) {
                    root.setStatus("NO_SHOW".equals(op.getAction()) ? "NO_SHOW" : "CANCELLED");
                } else {
                    root.setStatus("CONFIRMED"); root.setHoldNo(hold.holdNo()); arrange(root, op, hold.holdNo());
                }
                AppointmentPersistence.require(db.roots.updateById(root) == 1);
                op.setState("SUCCEEDED");
            } else {
                op.setState("CONFIRM".equals(receipt.binding().action()) ? "RELEASE_REQUIRED" : "REJECTED");
                if ("RESERVE".equals(receipt.binding().action())) { root.setStatus("CANCELLED");
                        AppointmentPersistence.require(db.roots.updateById(root) == 1); }
            }
            AppointmentPersistence.require(db.operations.updateById(op) == 1);
            db.clearCurrent(root);
            publisher.publish(new BeautyAppointmentOperationCompleted(root.getAppointmentNo(), root.getEntityNo(),
                    root.getStoreNo(), op.getOperationNo(), op.getAction(), op.getState(), root.getStatus(),
                    root.getVersion(), db.current(root).getArrangementRevision()),
                    new PublishOptions("BEAUTY:APPOINTMENT:" + IdempotencyScope.requestHash(root.getEntityNo(),
                            root.getStoreNo(), root.getAppointmentNo()), 1));
            return null;
        });
    }
    private void arrange(BtyAppointment root, BtyAppointmentOperation op, String hold) {
        var p = db.preview(op.getPreviewNo());
        var snapshot = db.json.readValue(p.getSnapshot(), AppointmentMatching.Snapshot.class);
        var current = db.current(root);
        long revision = current.getArrangementRevision() + 1;
        var arrangement = new BtyAppointmentArrangement(); arrangement.setEntityNo(root.getEntityNo());
        arrangement.setAppointmentNo(root.getAppointmentNo()); arrangement.setArrangementRevision(revision);
        arrangement.setPreviewNo(p.getPreviewNo()); arrangement.setHoldNo(hold);
                arrangement.setSnapshot(p.getSnapshot());
        db.arrangements.insert(arrangement);
        int index = 0;
        for (var item : snapshot.items()) {
            var row = new BtyAppointmentItem(); row.setEntityNo(root.getEntityNo());
            row.setAppointmentNo(root.getAppointmentNo()); row.setAppointmentItemNo(item.appointmentItemNo());
            row.setSkuNo(item.skuNo()); row.setDefinitionVersionNo(item.definitionVersionNo());
            row.setQty(BigDecimal.valueOf(item.qty())); row.setUom("COUNT"); row.setSequenceNo(index++);
            row.setExecutionSnapshot(db.json.writeValueAsString(item)); db.items.insert(row);
        }
        for (var assignment : snapshot.assignments()) {
            var row = new BtyAppointmentAssignment(); row.setEntityNo(root.getEntityNo());
            row.setPlannedAssignmentNo(BizKey.next("BTAS")); row.setAppointmentItemNo(assignment.appointmentItemNo());
            row.setAppointmentRevision(revision); row.setRequirementNo(assignment.requirementNo());
            row.setPersonnelNo(assignment.personnelNo()); row.setResourceNo(assignment.resourceNo());
                    row.setHoldNo(hold);
            row.setStartAt(assignment.startAt().toLocalDateTime()); row.setEndAt(assignment.endAt().toLocalDateTime());
            row.setSkillSnapshot(db.json.writeValueAsString(assignment)); db.assignments.insert(row);
        }
        current.setArrangementRevision(revision); AppointmentPersistence.require(db.currents.updateById(current) == 1);
        root.setStartAt(p.getStartAt()); root.setEndAt(p.getEndAt()); root.setPolicyVersionNo(p.getPolicyVersionNo());
    }
}
