package ai.neargo.shop.spi.member;

import ai.neargo.shop.spi.entitlement.EntitlementContext;

/** A scoped identification reference is never consent to debit. Source owners still require customer approval. */
public interface CustomerIdentityPort {
    ResolvedCustomer resolveReference(EntitlementContext context, ReferenceQuery query);

    /** Actor comes from the original authenticated merchant, frozen by the source owner for later C confirmation. */
    record ReferenceQuery(String entityNo, String storeNo, String customerRef) {
        @Override
        public String toString() {
            return "ReferenceQuery[customerRef=REDACTED]";
        }
    }

    record ResolvedCustomer(String userNo,String customerNo) {
        public ResolvedCustomer(String userNo){this(userNo,userNo);}
    }
}
