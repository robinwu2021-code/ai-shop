package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway.Type;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

public final class AfterSaleContracts {
    private AfterSaleContracts() {}

    public record ApplyDecision(
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceDecisionRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown after-sale decision field");
        }
    }

    public record Query(
            String connectionNo,
            String channelOrderNo,
            String type,
            String status,
            Instant from,
            Instant to,
            int page,
            int size) {}

    public record Line(
            String externalLineRef, String quantity, String unit, long merchantRefundMinor) {}

    public record Link(
            String caseNo,
            String channelOrderNo,
            String connectionNo,
            String externalCaseRef,
            Type type,
            String supplierState,
            String revisionRef,
            String currency,
            long requestedMerchantRefundMinor,
            String operationNo,
            String decisionStatus,
            String sourceDecisionRef,
            String sourceRevision,
            String localFundsStatus,
            String localInventoryStatus,
            List<Line> lines,
            Instant requestedAt,
            long version) {
        public Link {
            lines = List.copyOf(lines);
        }
    }
}
