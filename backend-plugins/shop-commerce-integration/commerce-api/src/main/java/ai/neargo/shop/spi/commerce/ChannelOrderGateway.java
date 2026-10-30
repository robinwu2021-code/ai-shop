package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.ChannelOrderWireCodecs;
import ai.neargo.shop.commerce.dto.CommerceInstantDeserializer;
import ai.neargo.shop.commerce.dto.CommerceIntegerDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;
import ai.neargo.shop.commerce.dto.CommerceVersionDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;
import java.util.List;

/**
 * Original platform facts stay separate from local payment, inventory and business acceptance
 * authority.
 */
public interface ChannelOrderGateway extends CommerceGateway {
    Snapshot readOrder(Query query);

    default boolean supportsDecision(Fulfillment fulfillment, Action action) {
        return false;
    }

    DecisionResult decide(Decision decision);

    DecisionResult queryDecision(Decision decision);

    enum State {
        WAITING_ACCEPTANCE,
        ACCEPTED,
        CANCELLED,
        COMPLETED
    }

    enum PaymentState {
        UNPAID,
        PLATFORM_PAID,
        CASH_ON_DELIVERY
    }

    enum Fulfillment {
        INSTANT_DELIVERY,
        PICKUP,
        SHIPMENT,
        VOUCHER
    }

    enum Action {
        ACCEPT,
        REJECT
    }

    enum DecisionStatus {
        CONFIRMED,
        REJECTED,
        UNKNOWN
    }

    record Query(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String profileNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalAccountRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalStoreRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalOrderRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String authorizationNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String credentialRef) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }
    }

    /**
     * Quantity is picks per main sale unit; amountMinor is the original extra charge for the
     * complete external line.
     */
    record Selection(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String lineRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String kind,
            ChannelResourceIdentityGateway.Identity externalIdentity,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String quantity,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long amountMinor) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }
    }

    /**
     * Tax-exclusive gross and discount make the arithmetic explicit even for tax-inclusive supplier
     * protocols.
     */
    record Line(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String lineRef,
            ChannelResourceIdentityGateway.Identity offerIdentity,
            ChannelResourceIdentityGateway.Identity skuIdentity,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String quantity,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String unit,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long grossExTaxMinor,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long merchantDiscountExTaxMinor,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long taxMinor,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long netMinor,
            List<Selection> selections,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String note) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }

        public Line {
            selections = List.copyOf(selections);
        }
    }

    record Totals(
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long merchantChargesMinor,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long platformSubsidyMinor,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long platformFeesMinor,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long deliveryFeesMinor,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long merchantReceivableMinor,
            @JsonProperty(required = true)
                    @JsonDeserialize(using = ChannelOrderWireCodecs.MinorValue.class)
                    long customerPayableMinor) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }
    }

    record Customer(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalCustomerRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String displayName,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String phone,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String address) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }
    }

    record Document(
            @JsonProperty(required = true)
                    @JsonDeserialize(using = CommerceIntegerDeserializer.class)
                    int schemaVersion,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalAccountRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalStoreRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalOrderRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String revisionRef,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long revisionSequence,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String catalogHash,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String currency,
            @JsonDeserialize(using = ChannelOrderWireCodecs.StateValue.class) State state,
            @JsonDeserialize(using = ChannelOrderWireCodecs.PaymentStateValue.class)
                    PaymentState paymentState,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalPaymentRef,
            @JsonDeserialize(using = ChannelOrderWireCodecs.FulfillmentValue.class)
                    Fulfillment fulfillment,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant orderedAt,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant requestedAt,
            Customer customer,
            List<Line> lines,
            Totals totals) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }

        public Document {
            lines = List.copyOf(lines);
        }
    }

    /**
     * A short-lived original read receipt is not a debit authorization or evidence of local
     * acceptance.
     */
    record Snapshot(
            Document document,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant observedAt,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant validUntil,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalReceiptRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String documentHash) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }
    }

    /**
     * The complete immutable action is persisted before send and reused exactly for read-only
     * recovery.
     */
    record Decision(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String operationNo,
            Query scope,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String documentHash,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String revisionRef,
            @JsonDeserialize(using = ChannelOrderWireCodecs.ActionValue.class) Action action,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reasonCode,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String reason) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }
    }

    record DecisionResult(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String operationNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String externalOrderRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String revisionRef,
            @JsonDeserialize(using = ChannelOrderWireCodecs.ActionValue.class) Action action,
            @JsonDeserialize(using = ChannelOrderWireCodecs.DecisionStatusValue.class)
                    DecisionStatus status,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalReceiptRef) {
        @JsonAnySetter
        public void rejectUnknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown normalized order field");
        }
    }
}
