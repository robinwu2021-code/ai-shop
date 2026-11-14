package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.AfterSaleWireCodecs;
import ai.neargo.shop.commerce.dto.ChannelOrderWireCodecs;
import ai.neargo.shop.commerce.dto.CommerceInstantDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.commerce.dto.CommerceVersionDeserializer;

import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Supplier after-sale observations/decisions never close original local funds or physical
 * resources.
 */
public interface ChannelAfterSaleGateway extends CommerceGateway {
    enum Type {
        REFUND_ONLY,
        RETURN_REFUND
    }

    enum State {
        REQUESTED,
        RETURN_IN_PROGRESS,
        REFUNDED,
        REJECTED,
        CLOSED
    }

    enum Action {
        REFUND_ONLY,
        RETURN_REFUND,
        REJECT
    }

    enum ResultStatus {
        ACKNOWLEDGED,
        REFUNDED,
        REJECTED,
        UNKNOWN
    }

    record RequestedLine(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalLineRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String quantity,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String unit,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long merchantRefundMinor) {}

    record Document(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalAccountRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalStoreRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalOrderRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalCaseRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String revisionRef,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long sequence,
            @JsonDeserialize(using = AfterSaleWireCodecs.TypeValue.class) Type type,
            @JsonDeserialize(using = AfterSaleWireCodecs.StateValue.class) State state,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String currency,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long merchantRefundMinor,
            List<RequestedLine> lines,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant requestedAt) {
        public Document {
            lines = List.copyOf(lines);
        }
    }

    record Snapshot(
            Document document,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String contentHash,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant observedAt,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant validUntil,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalReceiptRef) {}

    /**
     * Read all bounded original cases for the exact verified order; callbacks need not embed
     * trusted case facts.
     */
    List<Snapshot> readAfterSales(ChannelOrderGateway.Query scope);

    record DecisionLine(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalLineRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String quantity,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String unit,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long merchantRefundMinor) {}

    record Publication(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String operationNo,
            ChannelOrderGateway.Query scope,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalCaseRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String caseRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String caseHash,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalOrderHash,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceDecisionRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceDecisionHash,
            @JsonDeserialize(using = AfterSaleWireCodecs.ActionValue.class) Action action,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reasonCode,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String currency,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long merchantRefundMinor,
            List<DecisionLine> lines,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String fundsAuthorizationRef) {
        public Publication {
            lines = List.copyOf(lines);
        }
    }

    record Result(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String operationNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalOrderRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalCaseRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String publicationHash,
            @JsonDeserialize(using = AfterSaleWireCodecs.ResultValue.class) ResultStatus status,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalReceiptRef) {}

    boolean supportsAfterSale(Type type, Action action);

    Result applyAfterSale(Publication publication);

    /** An uncertain write is recovered only by querying the same immutable operation. */
    Result queryAfterSale(Publication publication);
}
