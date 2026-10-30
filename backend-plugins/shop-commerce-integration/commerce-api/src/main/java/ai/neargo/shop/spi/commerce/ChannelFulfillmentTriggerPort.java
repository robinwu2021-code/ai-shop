package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.commerce.dto.CommerceOwnerDeserializer;
import ai.neargo.shop.commerce.dto.CommerceTextDeserializer;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

/**
 * Durable original-event intake records a publication intent, never a supplier delivery or local
 * stock/money acknowledgement.
 */
public interface ChannelFulfillmentTriggerPort {
    enum Status {
        RECORDED,
        WAITING_IMPORT
    }

    record Request(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
            @JsonDeserialize(using = CommerceOwnerDeserializer.class)
                    ChannelSourceValidationPort.Owner sourceOwner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String importNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceOrderRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String coreOrderRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceFulfillmentRef,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRevision,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String originalEventNo) {
        @JsonAnySetter
        public void unknown(String name, Object value) {
            throw new IllegalArgumentException("Unknown original fulfillment trigger field");
        }
    }

    record Result(Request request, Status status, String operationNo, String publicationStatus) {}

    Result record(Request request);
}
