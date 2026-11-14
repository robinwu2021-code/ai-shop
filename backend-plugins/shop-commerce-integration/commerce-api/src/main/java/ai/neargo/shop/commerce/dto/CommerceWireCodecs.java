package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Kind;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.ReadMode;
import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Status;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Enum ordinals are never a valid owner or evidence status on internal service boundaries. */
public final class CommerceWireCodecs {
    private CommerceWireCodecs() {}

    public static final class OwnerDeserializer extends ValueDeserializer<Owner> {
        @Override
        public Owner deserialize(JsonParser p, DeserializationContext context) {
            if (p.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(Owner.class, "String enum required");
            try {
                return Owner.valueOf(p.getString());
            } catch (IllegalArgumentException failure) {
                return context.reportInputMismatch(Owner.class, "Unknown enum value");
            }
        }
    }

    public static final class KindDeserializer extends ValueDeserializer<Kind> {
        @Override
        public Kind deserialize(JsonParser p, DeserializationContext context) {
            if (p.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(Kind.class, "String enum required");
            try {
                return Kind.valueOf(p.getString());
            } catch (IllegalArgumentException failure) {
                return context.reportInputMismatch(Kind.class, "Unknown enum value");
            }
        }
    }

    public static final class StatusDeserializer extends ValueDeserializer<Status> {
        @Override
        public Status deserialize(JsonParser p, DeserializationContext context) {
            if (p.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(Status.class, "String enum required");
            try {
                return Status.valueOf(p.getString());
            } catch (IllegalArgumentException failure) {
                return context.reportInputMismatch(Status.class, "Unknown enum value");
            }
        }
    }

    public static final class ReadModeDeserializer extends ValueDeserializer<ReadMode> {
        @Override
        public ReadMode deserialize(JsonParser p, DeserializationContext context) {
            if (p.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(ReadMode.class, "String enum required");
            try {
                return ReadMode.valueOf(p.getString());
            } catch (IllegalArgumentException failure) {
                return context.reportInputMismatch(ReadMode.class, "Unknown enum value");
            }
        }
    }
}
