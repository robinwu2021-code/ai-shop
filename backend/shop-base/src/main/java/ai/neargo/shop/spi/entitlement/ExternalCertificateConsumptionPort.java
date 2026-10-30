package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.inventory.MaterialCatalogFacts;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Original completed business consumption for externally issued certificates; no local entitlement
 * instance or user balance.
 */
public interface ExternalCertificateConsumptionPort {
    record Scope(String tenantNo, String entityNo, String storeNo) {}

    record Business(
            @JsonDeserialize(using = MaterialCatalogFacts.TextDeserializer.class) String subOrderNo,
            @JsonProperty(value = "orderItemId", required = true)
                    @JsonDeserialize(using = CatalogRevisionDeserializer.class)
                    long orderItemId) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown actual business consumption reference");
        }
    }

    record Authorization(
            String authorizationRef,
            Scope scope,
            Business business,
            String previewRef,
            String previewHash,
            String certificateIdentityHash,
            String sourceHash,
            BigDecimal quantity,
            String unit,
            String status,
            Instant expiresAt,
            String decisionRef,
            String originalAuthorizationRef,
            String purpose) {}

    Authorization authorize(Scope scope, Business business, String previewRef, String previewHash);

    Authorization freeze(Scope scope, String authorizationRef);

    Authorization reconcile(Scope scope, String authorizationRef);

    Authorization query(Scope scope, String authorizationRef);

    record ReversalReadiness(String status, String reasonCode, Authorization authorization) {}

    /**
     * A missing original owner is an explicit non-authorizing result, never a synthetic success
     * permit.
     */
    default ReversalReadiness reversalReadiness(
            Scope scope, String authorizationRef, String originalReversalRef) {
        try {
            return new ReversalReadiness(
                    "READY", null, authorizeReversal(scope, authorizationRef, originalReversalRef));
        } catch (UnsupportedOperationException unsupported) {
            return new ReversalReadiness(
                    "UNSUPPORTED", "ORIGINAL_REVERSAL_SOURCE_UNAVAILABLE", null);
        }
    }

    /**
     * A refund is not original physical/business reversal proof. Unsupported owners must reject
     * without releasing capacity.
     */
    default Authorization authorizeReversal(
            Scope scope, String authorizationRef, String originalReversalRef) {
        throw new UnsupportedOperationException(
                "Original external certificate consumption reversal owner unavailable");
    }
}
