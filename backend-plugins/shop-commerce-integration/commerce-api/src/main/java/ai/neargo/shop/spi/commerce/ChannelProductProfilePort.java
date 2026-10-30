package ai.neargo.shop.spi.commerce;

import java.time.Instant;

/**
 * Installed protocol code proves its own immutable metadata. Operator input cannot manufacture
 * protocol verification.
 */
public interface ChannelProductProfilePort {
    String profileNo();

    Proof verifyDefinition(Definition definition);

    default Registration registration() {
        return null;
    }

    enum Status {
        VERIFIED,
        UNSUPPORTED,
        UNKNOWN
    }

    record Definition(
            String profileNo,
            String providerCode,
            String productCode,
            String market,
            String protocolCode,
            String capabilityDefinition,
            int identitySchemaVersion,
            String identityNamespace,
            boolean accountScoped,
            boolean mock) {}

    record Proof(
            Status status,
            Definition definition,
            Instant verifiedAt,
            Instant validUntil,
            String originalReceiptRef) {}

    record Registration(Definition definition, String providerName) {}
}
