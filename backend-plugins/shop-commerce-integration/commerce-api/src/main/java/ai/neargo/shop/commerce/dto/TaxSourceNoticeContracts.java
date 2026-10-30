package ai.neargo.shop.commerce.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import tools.jackson.databind.annotation.JsonDeserialize;

import java.time.Instant;

public final class TaxSourceNoticeContracts {
    private TaxSourceNoticeContracts() {}

    public record Notice(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String sourceSystem,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String eventNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String tenantNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String entityNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String settingKey,
            @JsonDeserialize(using = CommerceVersionDeserializer.class) Long version,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String contentHash,
            @JsonDeserialize(using = CommerceInstantDeserializer.class) Instant effectiveAt) {
        @JsonAnySetter
        public void rejectUnknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown tax source hint");
        }
    }
}
