package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelAvailabilitySourcePort.StoreMode;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotNull;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;

/** A stock request names an existing original mapping, never self-reports an available quantity. */
public final class AvailabilityContracts {
    private AvailabilityContracts() {}

    /**
     * Only a connection and business interpretation are requested; current state always comes from
     * its real original owner.
     */
    public record StorePublish(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @NotNull @JsonDeserialize(using = StoreModeValue.class) StoreMode mode) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown store availability field");
        }
    }

    public static final class StoreModeValue extends ValueDeserializer<StoreMode> {
        @Override
        public StoreMode deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(StoreMode.class, "Store mode must be a string");
            try {
                return StoreMode.valueOf(parser.getString());
            } catch (IllegalArgumentException unknown) {
                return context.reportInputMismatch(StoreMode.class, "Unknown store mode");
            }
        }
    }

    public record Publish(
            @JsonDeserialize(using = CommerceTextDeserializer.class) String connectionNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String mappingNo,
            @JsonDeserialize(using = CommerceTextDeserializer.class) String mappingRevisionNo) {
        @JsonAnySetter
        public void unknown(String key, Object value) {
            throw new IllegalArgumentException("Unknown availability field");
        }
    }
}
