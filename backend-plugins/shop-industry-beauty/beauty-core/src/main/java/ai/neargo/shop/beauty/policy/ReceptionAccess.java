package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.auth.BizPerms;
import ai.neargo.shop.auth.LoginUser;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.MerchantOperatorAccessPort;
import org.springframework.beans.factory.ObjectProvider;

/** Customer access uses today's account namespace and store grant, never an actor remembered by a receipt. */
public final class ReceptionAccess {
    private final AppointmentAccess appointments;
    private final ObjectProvider<MerchantOperatorAccessPort> operators;
    public ReceptionAccess(AppointmentAccess appointments, ObjectProvider<MerchantOperatorAccessPort> operators) {
        this.appointments = appointments; this.operators = operators;
    }
    public GuestRegistrationPort.Scope customer(String entity, String store, String actor) {
        LoginUser user = appointments.current(actor);
        if (!"MERCHANT".equals(user.realm().name())
                || !("USR".equals(user.subjectKind().name()) || "MCH".equals(user.subjectKind().name()))
                || entity == null || entity.isBlank() || store == null || store.isBlank()
                || !operators.getObject().allowed(new MerchantOperatorAccessPort.Query(user.tenantNo(), entity,
                        store, actor, user.realm().name(), user.subjectKind().name(), BizPerms.CUSTOMER))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        return new GuestRegistrationPort.Scope(entity, store, new DelegatedIdentity(user.tenantNo(),
                user.realm().name(), user.subjectKind().name(), actor));
    }
}
