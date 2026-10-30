package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.entitlement.ExternalCertificateConsumptionPort.Business;
import ai.neargo.shop.spi.product.CatalogRevisionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Public requests and responses contain opaque controlled handles and original source references,
 * never certificate codes.
 */
public final class RedemptionContracts {
    private RedemptionContracts() {}

    public record Preview(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String controlledCertificateRef,
            Business sourceConsumptionRef) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown redemption preview field");
        }
    }

    public record Previewed(
            String previewRef,
            String status,
            String expectedHash,
            String sourceAuthorizationRef,
            BigDecimal quantity,
            String unit,
            Instant expiresAt) {}

    public record Redeem(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String previewRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String expectedHash,
            @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String sourceAuthorizationRef) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown redemption field");
        }
    }

    public record Reverse(
            @JsonDeserialize(using = CatalogRevisionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String sourceReversalAuthorizationRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown redemption reversal field");
        }
    }

    public record Redemption(
            String redemptionNo,
            String storeNo,
            String connectionNo,
            String status,
            String operationNo,
            BigDecimal quantity,
            String unit,
            String sourceAuthorizationRef,
            String sourceDecisionRef,
            String originalReceiptRef,
            long version) {}
}
