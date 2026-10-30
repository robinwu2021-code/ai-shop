package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceInstantDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Supplier statement evidence is technical reconciliation data, never independent local funds
 * authority.
 */
public interface ChannelStatementGateway extends CommerceGateway {
    enum Status {
        FOUND,
        UNKNOWN,
        REJECTED
    }

    record Scope(
            String entityNo,
            String accountNo,
            String profileNo,
            String externalAccountRef,
            String authorizationNo,
            String credentialRef) {}

    record Lookup(
            String operationNo,
            Scope scope,
            String statementRef,
            String sourceRevision,
            String documentHash) {}

    record Breakdown(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String saleExTaxMinor,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String discountMinor,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String taxMinor,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String refundMinor,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String feeMinor,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String adjustmentMinor,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String netMinor) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown statement amount field");
        }
    }

    record Line(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String lineRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String kind,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalStoreRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalOrderRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalAfterSaleRef,
            Breakdown amounts) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown statement line field");
        }
    }

    record Document(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String statementRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String profileNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalAccountRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String currency,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant periodStart,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant periodEnd,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant issuedAt,
            Breakdown totals,
            List<Line> lines) {
        public Document {
            if (lines != null) lines = List.copyOf(lines);
        }

        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown statement document field");
        }
    }

    record Receipt(
            String operationNo,
            String lookupHash,
            Status status,
            Document document,
            String documentHash,
            String originalReceiptRef) {}

    Receipt fetchStatement(Lookup lookup);

    /**
     * Exact original lookup only; uncertainty never creates a replacement import or statement
     * revision.
     */
    Receipt queryStatement(Lookup lookup);
}
