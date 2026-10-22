package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.beauty.entity.BtyAppointment;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.resource.ResourceBookingPort;
import ai.neargo.shop.spi.resource.ResourceSourceAuthorityPort;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Checks real current actor namespaces and live store grants; a stored actor name is never an authority. */
public final class AppointmentAccess {
    private final ObjectProvider<MerchantOperatorAccessPort> access;
    private final CustomerOwnership ownership;
    public AppointmentAccess(ObjectProvider<MerchantOperatorAccessPort> access, CustomerOwnership ownership) {
        this.access = access; this.ownership = ownership;
    }
    public LoginUser current(String actor) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("appointment actor checks require no caller transaction");
        }
        var user = SecurityUtils.requireUser();
        if (!"MAIN".equals(user.tenantNo()) || !user.userNo().equals(actor)) { denied(); }
        return user;
    }
    public void consumer(String userNo) {
        var user = current(userNo);
        if (!"CONSUMER".equals(user.realm().name()) || !"USR".equals(user.subjectKind().name())) { denied(); }
    }
    public void merchant(String entity, String store, String actor, boolean write) {
        var user = current(actor);
        if (!"MERCHANT".equals(user.realm().name())
                || !("MCH".equals(user.subjectKind().name()) || "USR".equals(user.subjectKind().name()))) { denied(); }
        if (!allowed(user, entity, store, BizPerms.BOOKING_MANAGE)
                && (write || !allowed(user, entity, store, BizPerms.BOOKING_VIEW))) { denied(); }
    }
    public void root(BtyAppointment root, boolean write) {
        if (root == null) { throw BizException.of(ErrorCode.NOT_FOUND); }
        var user = SecurityUtils.requireUser();
        if ("CONSUMER".equals(user.realm().name())) {
            consumer(user.userNo());
            if (!ownership.owns(root, user.userNo())) { denied(); }
        } else {
            merchant(root.getEntityNo(), root.getStoreNo(), user.userNo(), write);
        }
    }
    public void source(ResourceSourceAuthorityPort.Request request, BtyAppointment root) {
        var user = current(request.operatorNo());
        if (!user.realm().name().equals(request.realm()) || !user.subjectKind().name().equals(request.subjectKind())
                || !user.tenantNo().equals(request.binding().tenantNo())) { denied(); }
        root(root, true);
    }
    public ResourceBookingPort.Actor actor() {
        var user = SecurityUtils.requireUser();
        return new ResourceBookingPort.Actor(user.userNo(), user.realm().name(), user.subjectKind().name());
    }
    private boolean allowed(LoginUser user, String entity, String store, String permission) {
        return access.getObject().allowed(new MerchantOperatorAccessPort.Query(user.tenantNo(), entity, store,
                user.userNo(), user.realm().name(), user.subjectKind().name(), permission));
    }
    private void denied() { throw BizException.of(ErrorCode.FORBIDDEN); }
}
