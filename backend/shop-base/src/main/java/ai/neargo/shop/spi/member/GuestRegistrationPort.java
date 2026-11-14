package ai.neargo.shop.spi.member;

import ai.neargo.shop.spi.security.DelegatedIdentity;
import java.util.List;

/** Member-owned guest facts contain no account creation or payment authorization. */
public interface GuestRegistrationPort {
    Registration register(Scope scope, RegisterCommand command);
    GuestFact get(Scope scope, String guestNo);
    GuestFact verify(Scope scope, String guestNo, String claimToken);
    Claim claim(Scope scope, String guestNo, ClaimCommand command);

    /** A claimed guest selector identifies its actual active user without inventing membership. */
    default ClaimedCustomer resolveClaimedCustomer(Scope scope, String guestNo, String claimToken) {
        throw new UnsupportedOperationException("claimed guest customer unavailable");
    }

    /** Exact current claim linkage is separate from a caller's requested purchase authority. */
    record ClaimedCustomer(GuestFact guest, CustomerIdentityPort.ResolvedCustomer customer) {
        public ClaimedCustomer {
            if (guest == null
                    || customer == null
                    || !"CLAIMED".equals(guest.state())
                    || !id(guest.guestNo(), 32)
                    || !id(guest.userNo(), 64)
                    || guest.revision() <= 0
                    || !guest.userNo().equals(customer.userNo())) {
                throw new IllegalArgumentException("invalid claimed guest customer");
            }
        }

        @Override
        public String toString() {
            return "ClaimedCustomer[REDACTED]";
        }
    }

    /** Current consumer ownership cannot be inferred from historical claim receipts. */
    default CurrentClaim currentClaim(ConsumerScope scope, GuestRef guest) {
        throw new UnsupportedOperationException("current claim unavailable");
    }

    /** Complete current claim set; unavailable providers must never return an empty success. */
    default List<CurrentClaim> currentClaims(ConsumerScope scope) {
        throw new UnsupportedOperationException("current claims unavailable");
    }

    /** Consumer coordinates carry no caller-selected customer account. */
    record ConsumerScope(String tenantNo, DelegatedIdentity identity) {
        public ConsumerScope {
            if (identity == null
                    || !identity.tenantNo().equals(tenantNo)
                    || !"CONSUMER".equals(identity.realm())
                    || !"USR".equals(identity.subjectKind())) {
                throw new IllegalArgumentException("invalid consumer claim scope");
            }
        }
    }

    /** Exact member-owned guest coordinates prevent null buyer predicates from widening reads. */
    record GuestRef(String entityNo, String storeNo, String guestNo) {
        public GuestRef {
            if (!id(entityNo, 32) || !id(storeNo, 32) || !id(guestNo, 32)) {
                throw new IllegalArgumentException("invalid guest coordinates");
            }
        }
    }

    /** Minimal current ownership evidence contains no contact, token or operator account. */
    record CurrentClaim(
            String entityNo, String storeNo, String guestNo, String userNo, long claimRevision) {
        public CurrentClaim {
            if (!id(entityNo, 32)
                    || !id(storeNo, 32)
                    || !id(guestNo, 32)
                    || !id(userNo, 64)
                    || claimRevision <= 0) {
                throw new IllegalArgumentException("invalid current claim");
            }
        }
    }

    private static boolean id(String value, int max) {
        return value != null
                && !value.isBlank()
                && value.length() <= max
                && value.equals(value.strip());
    }

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
