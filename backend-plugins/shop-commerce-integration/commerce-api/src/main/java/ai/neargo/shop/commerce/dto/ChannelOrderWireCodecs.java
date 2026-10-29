package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Action;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.DecisionStatus;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.Fulfillment;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.PaymentState;
import ai.neargo.shop.spi.commerce.ChannelOrderGateway.State;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Normalized source facts must not turn fractional money, enum ordinals or absent amounts into
 * valid order authority.
 */
public final class ChannelOrderWireCodecs {
    private ChannelOrderWireCodecs() {}

    public static final class MinorValue extends ValueDeserializer<Long> {
        @Override
        public Long deserialize(JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
                return context.reportInputMismatch(Long.class, "Integer amount required");
            long value = parser.getLongValue();
            if (value < 0)
                return context.reportInputMismatch(Long.class, "Nonnegative amount required");
            return value;
        }

        @Override
        public Object getNullValue(DeserializationContext context) {
            return context.reportInputMismatch(Long.class, "Amount must not be null");
        }
    }

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
                return context.reportInputMismatch(type, "Unknown enum value");
            }
        }
    }

    public static final class StateValue extends EnumValue<State> {
        public StateValue() {
            super(State.class);
        }
    }

    public static final class PaymentStateValue extends EnumValue<PaymentState> {
        public PaymentStateValue() {
            super(PaymentState.class);
        }
    }

    public static final class FulfillmentValue extends EnumValue<Fulfillment> {
        public FulfillmentValue() {
            super(Fulfillment.class);
        }
    }

    public static final class ActionValue extends EnumValue<Action> {
        public ActionValue() {
            super(Action.class);
        }
    }

    public static final class DecisionStatusValue extends EnumValue<DecisionStatus> {
        public DecisionStatusValue() {
            super(DecisionStatus.class);
        }
    }
}
