package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.beauty.entity.BtyAppointmentResourceIntent;
import ai.neargo.shop.beauty.entity.BtyAppointmentResourceDecision;
import ai.neargo.shop.beauty.mapper.BeautyMappers;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ai.neargo.shop.beauty.policy.AppointmentAccess;
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
    private final BeautyMappers.AppointmentResourceDecisionMapper decisions;
    public AppointmentSourceAuthority(AppointmentPersistence db, AppointmentAccess access,
            BeautyMappers.AppointmentResourceDecisionMapper decisions) {
        this.db = db; this.access = access; this.decisions = decisions;
    }
    public ResourceSourceAuthorityPort.Decision decide(ResourceSourceAuthorityPort.Request request, boolean freeze) {
        var b = request.binding();
        var root = db.root(b.sourceNo());
        access.source(request, root); // Live owner checks occur before opening local locks.
        AppointmentPersistence.require("MAIN".equals(b.tenantNo()) && "BEAUTY_APPOINTMENT".equals(b.sourceType())
                && root.getEntityNo().equals(b.entityNo()) && root.getStoreNo().equals(b.storeNo()));
        return db.tx(() -> {
            var locked = db.lock(root);
            var fence = db.fences.selectScopedForUpdate(b.entityNo(), b.storeNo(), b.sourceNo());
            var intent = db.intents.selectByCommandForUpdate(b.entityNo(), b.storeNo(), b.commandNo());
            if (intent == null) { return unknown(b); }
            AppointmentPersistence.require(intent.getAppointmentNo().equals(b.sourceNo())
                    && intent.getSourceRevision() == b.sourceRevision() && intent.getAction().equals(b.action())
                    && intent.getCommandHash().equals(b.commandHash()) && intent.getPlanHash().equals(b.planHash()));
            if (intent.getPhaseCode() == 3) {
                if (intent.getReasonCode() != null) {
                    return new ResourceSourceAuthorityPort.Decision(b, ResourceSourceAuthorityPort.State.DENIED,
                            intent.getDecisionNo(), null, null, null, null, intent.getReasonCode());
                }
                return original(b, intent);
            }
            var current = db.current(locked);
            var operation = db.operation(current.getOperationNo());
            var receipt = db.json.readValue(operation.getReceipt(), ResourceBookingPort.Receipt.class);
            AppointmentPersistence.require(receipt.binding().equals(b)
                    && receipt.frozenPayload().equals(intent.getFrozenPlan())
                    && intent.getIntentNo().equals(fence.getActiveIntentNo())
                    && locked.getVersion() == b.sourceRevision());
            if (freeze && intent.getPhaseCode() == 0) {
                var now = AppointmentPersistence.now();
                AppointmentPersistence.require(db.intents.authorize(b.entityNo(), b.storeNo(), intent.getIntentNo(),
                        intent.getVersion(), BizKey.next("BTD"), now, now.plusMinutes(2), request.operatorNo(),
                                now) == 1);
                intent = db.intents.selectByCommandForUpdate(b.entityNo(), b.storeNo(), b.commandNo());
                var original = new BtyAppointmentResourceDecision();
                original.setEntityNo(b.entityNo()); original.setIntentNo(intent.getIntentNo());
                original.setCommandNo(b.commandNo()); original.setDecisionNo(intent.getDecisionNo());
                original.setFenceNo(fence.getFenceNo()); original.setFenceRevision(fence.getVersion());
                decisions.insert(original);
            }
            if (intent.getPhaseCode() != 1) {
                return new ResourceSourceAuthorityPort.Decision(b, ResourceSourceAuthorityPort.State.PENDING,
                        null, null, null, null, null, "AUTHENTICATED_REPLAY_REQUIRED");
            }
            // Return exactly the original window even after expiry. No query or retry renews permission.
            return original(b, intent);
        });
    }
    private ResourceSourceAuthorityPort.Decision original(ResourceSourceAuthorityPort.Binding binding,
            BtyAppointmentResourceIntent intent) {
        var row = decisions.selectOne(Wrappers.<BtyAppointmentResourceDecision>lambdaQuery()
                .eq(BtyAppointmentResourceDecision::getTenantNo, "MAIN")
                .eq(BtyAppointmentResourceDecision::getEntityNo, binding.entityNo())
                .eq(BtyAppointmentResourceDecision::getIntentNo, intent.getIntentNo()));
        AppointmentPersistence.require(row != null && row.getCommandNo().equals(binding.commandNo())
                && row.getDecisionNo().equals(intent.getDecisionNo()));
        return authorized(binding, intent, row.getFenceNo(), row.getFenceRevision());
    }
    private ResourceSourceAuthorityPort.Decision authorized(ResourceSourceAuthorityPort.Binding b,
            BtyAppointmentResourceIntent intent, String fence, long revision) {
        return new ResourceSourceAuthorityPort.Decision(b, ResourceSourceAuthorityPort.State.AUTHORIZED,
                intent.getDecisionNo(), fence, revision, intent.getIssuedAt().toInstant(ZoneOffset.UTC),
                intent.getValidUntil().toInstant(ZoneOffset.UTC), null);
    }
    private ResourceSourceAuthorityPort.Decision unknown(ResourceSourceAuthorityPort.Binding b) {
        return new ResourceSourceAuthorityPort.Decision(b, ResourceSourceAuthorityPort.State.UNKNOWN,
                null, null, null, null, null, null);
    }
}
