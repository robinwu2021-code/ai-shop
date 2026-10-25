package ai.neargo.shop.spi.member;

import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** A scoped identification reference is never consent to debit. Source owners still require customer approval. */
public interface CustomerIdentityPort {
    ResolvedCustomer resolveReference(EntitlementContext context, ReferenceQuery query);

    /**
     * Canonical scoped membership identifies a current user without creating C-session evidence.
     */
    default ResolvedCustomer resolveCounterReference(
            EntitlementContext context, CounterReferenceQuery query) {
        throw new UnsupportedOperationException("counter reference unavailable");
    }

    /** Member number is a selector; the member owner resolves its linked active account. */
    record CounterReferenceQuery(String entityNo, String storeNo, String memberNo) {
        @Override
        public String toString() {
            return "CounterReferenceQuery[REDACTED]";
        }
    }

    /** Current full-namespace claim proof; legacy reference providers deliberately do not authorize this. */
    default ResolvedCustomer resolveClaimReference(EntitlementContext context, ReferenceQuery query) {
        throw new UnsupportedOperationException("strict claim reference unavailable");
    }

    /** Optional capability defaults closed so legacy functional providers cannot invent a consumer identity. */
    default ResolvedCustomer verifyConsumerProof(EntitlementContext context, String memberCode) {
        throw new UnsupportedOperationException("consumer proof unavailable");
    }

    /** Actor comes from the original authenticated merchant, frozen by the source owner for later C confirmation. */
    record ReferenceQuery(String entityNo, String storeNo, String customerRef) {
        @Override
        public String toString() {
            return "ReferenceQuery[customerRef=REDACTED]";
        }
    }

    record ResolvedCustomer(String userNo) { }
}
