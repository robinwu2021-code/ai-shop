package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Public requests contain original references only; completion quantities and stages come from the
 * original owner.
 */
public final class FulfillmentContracts {
    private FulfillmentContracts() {}

    public record Publish(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String channelOrderNo,
            @JsonDeserialize(using = CommerceOwnerDeserializer.class) Owner sourceOwner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceFulfillmentRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown fulfillment publication field");
        }
    }

    public record Line(String orderLineLinkNo, String quantity, String unit) {}

    public record Link(
            String linkNo,
            String channelOrderNo,
            Owner sourceOwner,
            String sourceFulfillmentRef,
            String sourceRevision,
            String stage,
            String operationNo,
            String status,
            String externalEvidenceRef,
            Instant occurredAt,
            List<Line> lines) {
        public Link {
            lines = List.copyOf(lines);
        }
    }
}
