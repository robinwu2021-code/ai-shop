package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

/**
 * Change events are durable reread hints; payloads do not carry authoritative prices or desired
 * menu revisions.
 */
public final class SourceNoticeContracts {
    private SourceNoticeContracts() {}

    public record Notice(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceSystem,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String eventNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String storeNo,
            @JsonDeserialize(using = CommerceWireCodecs.OwnerDeserializer.class) Owner owner,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceRef,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant effectiveAt,
            @JsonInclude(JsonInclude.Include.NON_NULL)
                    @JsonDeserialize(using = CommerceTextDeserializer.class)
                    String kind) {
        public Notice(
                String sourceSystem,
                String eventNo,
                String entityNo,
                String storeNo,
                Owner owner,
                String sourceRef,
                Instant effectiveAt) {
            this(sourceSystem, eventNo, entityNo, storeNo, owner, sourceRef, effectiveAt, null);
        }

        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown change notice field");
        }
    }

    public record Receipt(String noticeNo, Instant readyAt) {}
}
