package ai.neargo.shop.spi.commerce;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Independent stock/store state publication. Supplier acknowledgement never mutates original
 * business state.
 */
public interface ChannelAvailabilityGateway extends CommerceGateway {
    enum Status {
        CONFIRMED,
        REJECTED,
        UNKNOWN
    }

    record Scope(
            String entityNo,
            String storeNo,
            String connectionNo,
            String profileNo,
            String externalAccountRef,
            String externalStoreRef,
            String connectionRevisionNo,
            String authorizationNo,
            String credentialRef) {}

    record Publication(
            String operationNo,
            long generation,
            Scope scope,
            ChannelResourceIdentityGateway.Identity identity,
            String mappingNo,
            String mappingRevisionNo,
            ChannelAvailabilitySourcePort.Data source,
            String sourceReceiptRef,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    ChannelConnectionIdentityPort.Proof storeIdentityProof) {
        public Publication(
                String operationNo,
                long generation,
                Scope scope,
                ChannelResourceIdentityGateway.Identity identity,
                String mappingNo,
                String mappingRevisionNo,
                ChannelAvailabilitySourcePort.Data source,
                String sourceReceiptRef) {
            this(
                    operationNo,
                    generation,
                    scope,
                    identity,
                    mappingNo,
                    mappingRevisionNo,
                    source,
                    sourceReceiptRef,
                    null);
        }
    }

    record Result(
            String operationNo, String publicationHash, Status status, String originalReceiptRef) {}

    boolean supportsAvailability(ChannelAvailabilitySourcePort.Kind kind);

    /**
     * Physical opening and order acceptance must be separately expressible by the supplier
     * protocol.
     */
    default boolean supportsStoreMode(ChannelAvailabilitySourcePort.StoreMode mode) {
        return false;
    }

    Result publishAvailability(Publication publication);

    /**
     * Exact frozen original operation only; never reconstruct credentials or issue another write.
     */
    Result queryAvailability(Publication publication);
}
