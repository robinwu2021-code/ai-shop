package ai.neargo.shop.beauty.policy;

import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.BizKey;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.idem.IdempotencyScope;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.member.CustomerIdentityPort;
import ai.neargo.shop.spi.member.GuestRegistrationPort;

import org.springframework.beans.factory.ObjectProvider;

/** Only real owner facts can turn explicit guest numbers or short references into canonical scoped identities. */
public final class ReceptionIdentity {
    private final ReceptionAccess access;
    private final ObjectProvider<GuestRegistrationPort> guests;
    private final ObjectProvider<CustomerIdentityPort> customers;
    public ReceptionIdentity(ReceptionAccess access, ObjectProvider<GuestRegistrationPort> guests,
            ObjectProvider<CustomerIdentityPort> customers) {
        this.access = access; this.guests = guests; this.customers = customers;
    }
    /** Keeps internal continuation on the current real employee's customer permission. */
    public void authorize(String entity, String store, String actor) { access.customer(entity, store, actor); }
    public VerifiedIdentity guest(String entity, String store, String guestNo) {
        var scope = access.customer(entity, store, SecurityUtils.requireUser().userNo());
        var fact = guestFact(entity, store, guestNo);
        return "CLAIMED".equals(fact.state()) ? new VerifiedIdentity("USER", fact.userNo())
                : new VerifiedIdentity("GUEST", fact.guestNo());
    }

    /** Current owner state is read without inventing a revision or consumer credential. */
    public GuestRegistrationPort.GuestFact guestFact(String entity, String store, String guestNo) {
        var actor = SecurityUtils.requireUser().userNo();
        var original = access.customer(entity, store, actor);
        var fact = guests.getObject().get(original, guestNo);
        if (!original.equals(access.customer(entity, store, actor))) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        validFact(fact, guestNo);
        return fact;
    }

    public String customer(String entity, String store, String reference) {
        var user = SecurityUtils.requireUser();
        access.customer(entity, store, user.userNo());
        var result = customers.getObject().resolveClaimReference(new EntitlementContext(user.tenantNo(), user.userNo(),
                BizKey.next("BTCR"), IdempotencyScope.requestHash(reference)),
                new CustomerIdentityPort.ReferenceQuery(entity, store, reference));
        if (result == null || result.userNo() == null || result.userNo().isBlank()) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return result.userNo();
    }

    /**
     * A scoped member selector identifies a current user without minting consumer purchase
     * evidence.
     */
    public String customerMember(String entity, String store, String memberNo) {
        var user = SecurityUtils.requireUser();
        var original = access.customer(entity, store, user.userNo());
        var result =
                customers
                        .getObject()
                        .resolveCounterReference(
                                new EntitlementContext(
                                        user.tenantNo(),
                                        user.userNo(),
                                        BizKey.next("BTMR"),
                                        IdempotencyScope.requestHash(entity, store, memberNo)),
                                new CustomerIdentityPort.CounterReferenceQuery(
                                        entity, store, memberNo));
        // The current actor and permission may have changed while the identity owner was reading.
        var current = access.customer(entity, store, user.userNo());
        if (!original.equals(current)) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        if (result == null
                || result.userNo() == null
                || result.userNo().isBlank()
                || result.userNo().length() > 64
                || !result.userNo().equals(result.userNo().strip())) {
            throw BizException.of(ErrorCode.NOT_FOUND);
        }
        return result.userNo();
    }

    /** A formal guest token must be verified by the owner; get() alone proves no possession. */
    public VerifiedIdentity verifyGuest(String entity, String store, String guestNo, String token) {
        var scope = access.customer(entity, store, SecurityUtils.requireUser().userNo());
        var fact = guests.getObject().verify(scope, guestNo, token);
        validFact(fact, guestNo);
        return "CLAIMED".equals(fact.state()) ? new VerifiedIdentity("USER", fact.userNo())
                : new VerifiedIdentity("GUEST", guestNo);
    }
    /** Real consumer proof is checked by its owner under the current consumer namespace. */
    public String consumer(String memberCode) {
        var user = SecurityUtils.requireUser();
        var result = customers.getObject().verifyConsumerProof(new EntitlementContext(user.tenantNo(), user.userNo(),
                BizKey.next("BTMC"), IdempotencyScope.requestHash(memberCode)), memberCode);
        if (result == null || !user.userNo().equals(result.userNo())) { throw BizException.of(ErrorCode.FORBIDDEN); }
        return result.userNo();
    }
    public static void validFact(GuestRegistrationPort.GuestFact fact, String expected) {
        if (fact == null || !expected.equals(fact.guestNo())
                || !("REGISTERED".equals(fact.state()) && fact.userNo() == null && fact.revision() == 1
                || "CLAIMED".equals(fact.state()) && fact.userNo() != null && !fact.userNo().isBlank()
                    && fact.revision() == 2)) {
            throw new IllegalStateException("Invalid guest owner fact");
        }
    }
    public record VerifiedIdentity(String kind, String reference) { }
}
