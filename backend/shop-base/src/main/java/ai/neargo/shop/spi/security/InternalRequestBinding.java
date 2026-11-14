package ai.neargo.shop.spi.security;

/** Safe coordinates are echoed separately from private request payloads. */
public record InternalRequestBinding(
        int protocolVersion,
        String endpoint,
        String action,
        DelegatedIdentity identity,
        String entityNo,
        String storeNo,
        String targetNo) {
    /** Reject incomplete namespaces before they can acquire delegated authority. */
    public InternalRequestBinding {
        if (protocolVersion != 1
                || identity == null
                || invalid(endpoint)
                || invalid(action)
                || ("MERCHANT".equals(identity.realm()) && (invalid(entityNo) || invalid(storeNo)))
                || ("CONSUMER".equals(identity.realm()) && (entityNo != null || storeNo != null))
                || (targetNo != null && invalid(targetNo))) {
            throw new IllegalArgumentException("invalid internal binding");
        }
    }

    private static boolean invalid(String value) {
        return value == null
                || value.isBlank()
                || value.length() > 64
                || !value.equals(value.strip());
    }
}
