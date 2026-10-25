package ai.neargo.shop.trade.order.service.query;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.member.GuestRegistrationPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort.ConsumerScope;
import ai.neargo.shop.spi.member.GuestRegistrationPort.GuestRef;
import ai.neargo.shop.trade.entity.OrdOrder;
import ai.neargo.shop.trade.policy.CheckoutAccessPolicy;

import org.springframework.stereotype.Component;

import java.util.HashSet;

/**
 * Current read entitlement is independent from historical customer identity and mutation authority.
 */
@Component
public class OrderCustomerAccess {
    private final GuestRegistrationPort members;
    private final CheckoutAccessPolicy access;

    public OrderCustomerAccess(GuestRegistrationPort members, CheckoutAccessPolicy access) {
        this.members = members;
        this.access = access;
    }

    /** Resolve the complete canonical claim set outside all trade transactions and roots. */
    public CustomerReadScope currentReadScope() {
        String user = access.requireCurrentBuyer();
        var identity = DelegatedIdentityContext.current(user);
        try {
            var claims = members.currentClaims(new ConsumerScope(identity.tenantNo(), identity));
            var seen = new HashSet<GuestRef>();
            for (var claim : claims) {
                if (!user.equals(claim.userNo())
                        || !seen.add(
                                new GuestRef(claim.entityNo(), claim.storeNo(), claim.guestNo()))) {
                    throw new IllegalArgumentException("inconsistent current claim set");
                }
            }
            if (!user.equals(access.requireCurrentBuyer())) {
                throw BizException.of(ErrorCode.FORBIDDEN);
            }
            return new CustomerReadScope(
                    user,
                    claims.stream()
                            .map(
                                    claim ->
                                            new GuestRef(
                                                    claim.entityNo(),
                                                    claim.storeNo(),
                                                    claim.guestNo()))
                            .toList());
        } catch (RuntimeException unavailable) {
            throw BizException.of(ErrorCode.CHECKOUT_DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * Exact typed coordinates make a null guest buyer restrictive instead of an unrestricted query.
     */
    public void requireReadable(OrdOrder order, CustomerReadScope scope) {
        if (order != null && scope != null && "MAIN".equals(order.getTenantNo())) {
            if ("USER".equals(order.getCustomerKind())
                    && order.getGuestNo() == null
                    && scope.userNo().equals(order.getUserNo())) {
                return;
            }
            if ("GUEST".equals(order.getCustomerKind())
                    && order.getUserNo() == null
                    && "MANUAL_OFFLINE".equals(order.getAdmissionKind())
                    && order.getIdentityRevision() != null
                    && order.getIdentityRevision() > 0
                    && order.getEntityNo() != null
                    && order.getStoreNo() != null
                    && order.getGuestNo() != null
                    && scope.claimedGuests()
                            .contains(
                                    new GuestRef(
                                            order.getEntityNo(),
                                            order.getStoreNo(),
                                            order.getGuestNo()))) {
                return;
            }
        }
        throw BizException.of(ErrorCode.NOT_FOUND);
    }
}
