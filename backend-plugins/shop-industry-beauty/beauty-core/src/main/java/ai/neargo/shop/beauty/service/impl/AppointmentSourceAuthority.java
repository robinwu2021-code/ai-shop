package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyAppointmentCommand;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
import ai.neargo.shop.beauty.policy.ReceptionAccess;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import java.time.ZoneOffset;

/** The provider only trusts receipt bindings committed by the coordinator; public Binding input cannot create
 * intent.
 */
public final class AppointmentSourceAuthority {
    private final AppointmentPersistence db;
    private final AppointmentAccess access;
    private final ReceptionAccess receptionAccess;
    private final BeautyMappers.AppointmentResourceDecisionMapper decisions;
    public AppointmentSourceAuthority(AppointmentPersistence db, AppointmentAccess access,
            BeautyMappers.AppointmentResourceDecisionMapper decisions, ReceptionAccess receptionAccess) {
        this.db = db; this.access = access; this.decisions = decisions; this.receptionAccess = receptionAccess;
    }
    public ResourceSourceAuthorityPort.Decision decide(ResourceSourceAuthorityPort.Request request, boolean freeze) {
        var b = request.binding();
        var root = db.root(b.sourceNo());
        access.source(request, root); // Live owner checks occur before opening local locks.
        AppointmentPersistence.require("MAIN".equals(b.tenantNo()) && "BEAUTY_APPOINTMENT".equals(b.sourceType())
                && root.getEntityNo().equals(b.entityNo()) && root.getStoreNo().equals(b.storeNo()));
        var observedCurrent = db.current(root);
        if (observedCurrent != null && observedCurrent.getOperationNo() != null
                && "RECEPTION_CHANGE".equals(db.operation(observedCurrent.getOperationNo()).getAction())) {
            receptionAccess.customer(root.getEntityNo(), root.getStoreNo(), request.operatorNo());
        }
        return db.tx(() -> {
            var locked = db.lock(root);
            var fence = db.fences.selectScopedForUpdate(b.entityNo(), b.storeNo(), b.sourceNo());
            // 按命令号找命令行作答（§2.6 第 4–6 项方向 A，取代资源意向与资源决策）
            var command = db.commands.selectByCommandForUpdate(b.entityNo(), b.storeNo(), b.commandNo());
            if (command == null) { return unknown(b); }
            AppointmentPersistence.require(command.getAppointmentNo().equals(b.sourceNo())
                    && command.getSourceRevision() == b.sourceRevision() && command.getAction().equals(b.action())
                    && command.getCommandHash().equals(b.commandHash()) && command.getPlanHash().equals(b.planHash()));
            if (AppointmentCoordinator.settled(command)) {
                if (command.getRejectCode() != null) {
                    return new ResourceSourceAuthorityPort.Decision(b, ResourceSourceAuthorityPort.State.DENIED,
                            command.getDecisionNo(), null, null, null, null, command.getRejectCode());
                }
                return authorized(b, command);
            }
            var current = db.current(locked);
            var operation = db.operation(current.getOperationNo());
            var receipt = db.json.readValue(operation.getReceipt(), ResourceBookingPort.Receipt.class);
            var accepted = db.json.readValue(command.getAcceptPayload(), ResourceBookingPort.Accept.class);
            AppointmentPersistence.require(receipt.binding().equals(b)
                    && receipt.frozenPayload().equals(accepted.frozenPayload())
                    && command.getRequestKey().equals(fence.getActiveIntentNo())
                    && locked.getVersion() == b.sourceRevision());
            if ("RECEPTION_CHANGE".equals(operation.getAction())) {
                AppointmentPersistence.require("CHECKED_IN".equals(locked.getStatus())
                        && db.roots.countWorkOrders(b.entityNo(), b.storeNo(), b.sourceNo()) == 0);
            } else if ("CHANGE".equals(operation.getAction())) {
                AppointmentPersistence.require("CONFIRMED".equals(locked.getStatus()));
            }
            if (freeze && BtyAppointmentCommand.BOUND.equals(command.getStatus())) {
                var now = AppointmentPersistence.now();
                AppointmentPersistence.require(db.commands.permit(b.entityNo(), b.storeNo(), b.commandNo(),
                        command.getVersion(), BizKey.next("BTD"), now, now.plusMinutes(2), fence.getFenceNo(),
                        fence.getVersion(), request.operatorNo()) == 1);
                command = db.commands.selectByCommandForUpdate(b.entityNo(), b.storeNo(), b.commandNo());
            }
            if (!BtyAppointmentCommand.PERMITTED.equals(command.getStatus())) {
                return new ResourceSourceAuthorityPort.Decision(b, ResourceSourceAuthorityPort.State.PENDING,
                        null, null, null, null, null, "AUTHENTICATED_REPLAY_REQUIRED");
            }
            // Return exactly the original window even after expiry. No query or retry renews permission.
            return authorized(b, command);
        });
    }
    /** 许可一经写入不再改：决策号、窗口、闸门号与版本原样答回（取代资源决策表的重放）。 */
    private ResourceSourceAuthorityPort.Decision authorized(ResourceSourceAuthorityPort.Binding b,
            BtyAppointmentCommand command) {
        AppointmentPersistence.require(command.getDecisionNo() != null && command.getFenceNo() != null
                && command.getPermittedAt() != null && command.getValidUntil() != null);
        return new ResourceSourceAuthorityPort.Decision(b, ResourceSourceAuthorityPort.State.AUTHORIZED,
                command.getDecisionNo(), command.getFenceNo(), command.getFenceRevision(),
                command.getPermittedAt().toInstant(ZoneOffset.UTC), command.getValidUntil().toInstant(ZoneOffset.UTC),
                null);
    }
    private ResourceSourceAuthorityPort.Decision unknown(ResourceSourceAuthorityPort.Binding b) {
        return new ResourceSourceAuthorityPort.Decision(b, ResourceSourceAuthorityPort.State.UNKNOWN,
                null, null, null, null, null, null);
    }
}
