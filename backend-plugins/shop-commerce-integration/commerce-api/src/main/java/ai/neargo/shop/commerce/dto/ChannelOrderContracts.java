package ai.neargo.shop.commerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.NotNull;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Operator-safe order projections omit recipient details, credential generations and raw provider
 * payloads.
 */
public final class ChannelOrderContracts {
    private ChannelOrderContracts() {}

    public record Accept(
            @NotNull @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown acceptance field");
        }
    }

    public record Reject(
            @NotNull @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reasonCode,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown rejection field");
        }
    }

    public record Refresh(
            @NotNull @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown refresh field");
        }
    }

    public record Recover(
            @NotNull @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown import recovery field");
        }
    }

    public record Query(
            String connectionNo,
            String externalOrderRef,
            String sourceOwner,
            String importStatus,
            String platformStatus,
            Instant from,
            Instant to,
            int page,
            int size) {
        public Query(String connectionNo, String status, int page, int size) {
            this(connectionNo, null, null, status, null, null, null, page, size);
        }
    }

    public record Revision(
            String revisionNo,
            String externalRevision,
            Long sequence,
            String contentHash,
            String catalogHash,
            String currency,
            Instant capturedAt) {}

    public record Order(
            String channelOrderNo,
            String connectionNo,
            String externalOrderRef,
            String revisionNo,
            String importStatus,
            String acceptanceStatus,
            String platformStatus,
            String coreOrderRef,
            String currency,
            long merchantReceivableMinor,
            long customerPayableMinor,
            Instant orderedAt,
            long version) {}

    public record Allocation(
            String linkNo,
            String externalLineRef,
            String parentExternalLineRef,
            String kind,
            String sourceLineRef,
            String componentRef,
            String quantity,
            String unit,
            long amountMinor,
            String currency) {}

    public record Line(
            String externalLineRef,
            String skuExternalRef,
            String quantity,
            String unit,
            long grossExTaxMinor,
            long discountExTaxMinor,
            long taxMinor,
            long netMinor,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<Allocation> allocations) {
        public Line {
            allocations = List.copyOf(allocations);
        }

        public Line(
                String externalLineRef,
                String skuExternalRef,
                String quantity,
                String unit,
                long grossExTaxMinor,
                long discountExTaxMinor,
                long taxMinor,
                long netMinor) {
            this(
                    externalLineRef,
                    skuExternalRef,
                    quantity,
                    unit,
                    grossExTaxMinor,
                    discountExTaxMinor,
                    taxMinor,
                    netMinor,
                    List.of());
        }
    }

    public record Detail(Order order, List<Line> lines, String contentHash, String catalogHash) {}
}
