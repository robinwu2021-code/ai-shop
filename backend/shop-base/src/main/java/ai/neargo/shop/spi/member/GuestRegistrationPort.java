package ai.neargo.shop.spi.member;

import ai.neargo.shop.spi.security.DelegatedIdentity;

/** Member-owned guest facts contain no account creation or payment authorization. */
public interface GuestRegistrationPort {
    Registration register(Scope scope, RegisterCommand command);
    GuestFact get(Scope scope, String guestNo);
    GuestFact verify(Scope scope, String guestNo, String claimToken);
    Claim claim(Scope scope, String guestNo, ClaimCommand command);

    /** Only a transport outcome with an unknown committed result; capability and permission failures stay errors. */
    final class ResultUnknown extends RuntimeException {
        public ResultUnknown() { super("guest owner result unknown"); }
    }

    record Scope(String entityNo, String storeNo, DelegatedIdentity identity) { }
    record RegisterCommand(String contact, String displayName, boolean consent, String idempotencyKey) {
        @Override public String toString() { return "RegisterCommand[REDACTED]"; }
    }
    record Registration(String guestNo, String claimToken, String state, long revision) {
        @Override public String toString() { return "Registration[guestNo=" + guestNo + ",claimToken=REDACTED]"; }
    }
    record GuestFact(String guestNo, String state, String userNo, long revision) { }
    record ClaimCommand(String verifiedIdentity, String claimToken, String idempotencyKey) {
        @Override public String toString() { return "ClaimCommand[REDACTED]"; }
    }
    record Claim(String guestNo, String userNo, long revision) { }
}
