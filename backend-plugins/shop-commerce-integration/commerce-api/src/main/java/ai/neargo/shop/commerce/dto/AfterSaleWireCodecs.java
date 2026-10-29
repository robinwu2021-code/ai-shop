package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelAfterSaleGateway;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Supplier values preserve unknown facts; ordinals and coerced strings cannot become normalized
 * after-sale state.
 */
public final class AfterSaleWireCodecs {
    private AfterSaleWireCodecs() {}

    private abstract static class EnumValue<T extends Enum<T>> extends ValueDeserializer<T> {
        private final Class<T> type;

        EnumValue(Class<T> type) {
            this.type = type;
        }

        @Override
        public T deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(type, "String enum required");
            try {
                return Enum.valueOf(type, parser.getString());
            } catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(type, "Unknown after-sale value");
            }
        }
    }

    public static final class TypeValue extends EnumValue<ChannelAfterSaleGateway.Type> {
        public TypeValue() {
            super(ChannelAfterSaleGateway.Type.class);
        }
    }

    public static final class StateValue extends EnumValue<ChannelAfterSaleGateway.State> {
        public StateValue() {
            super(ChannelAfterSaleGateway.State.class);
        }
    }

    public static final class ActionValue extends EnumValue<ChannelAfterSaleGateway.Action> {
        public ActionValue() {
            super(ChannelAfterSaleGateway.Action.class);
        }
    }

    public static final class ResultValue extends EnumValue<ChannelAfterSaleGateway.ResultStatus> {
        public ResultValue() {
            super(ChannelAfterSaleGateway.ResultStatus.class);
        }
    }
}
