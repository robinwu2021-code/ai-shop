package ai.neargo.shop.spi.user;

import java.util.Optional;

/** Current merchant membership/permission facts, never a durable permission or a business-personnel role. */
public interface MerchantOperatorAccessPort {
    /** Trusted callers supply the authenticated actor; machine credentials alone never authorize an operator. */
    boolean allowed(Query query);

    /**
     * Resolve the real active account only when the same current permission query grants access.
     */
    default Optional<AuthorizedOperator> resolveAuthorizedOperator(Query query) {
        return Optional.empty();
    }

    /**
     * The merchant account identity is distinct from the current login actor and from the entity.
     */
    record AuthorizedOperator(String accountNo) {
        public AuthorizedOperator {
            Query.reference(accountNo, 64);
        }
    }

    /** Explicit namespace prevents a merchant account number from being interpreted as a consumer user number. */
    record Query(String tenantNo, String entityNo, String storeNo, String operatorNo, String realm,
            String subjectKind, String permission) {
        public Query {
            if (!"MAIN".equals(tenantNo) || !"MERCHANT".equals(realm)
                    || !("MCH".equals(subjectKind) || "USR".equals(subjectKind))) {
                throw new IllegalArgumentException(
                        "supported merchant identity namespace required");
            }
            Query.reference(entityNo, 32);
            Query.reference(storeNo, 32);
            Query.reference(operatorNo, 64);
            if (permission == null || permission.length() > 128
                    || !permission.matches("[a-z][a-z0-9]*(?::[a-z0-9][a-z0-9_-]*){1,6}")) {
                throw new IllegalArgumentException("exact permission code required");
            }
        }
        private static void reference(String value, int max) {
            if (value == null || value.isBlank() || value.length() > max || !value.equals(value.strip())) {
                throw new IllegalArgumentException("bounded canonical reference required");
            }
        }
    }
}
