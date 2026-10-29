package ai.neargo.shop.spi.trade;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/**
 * Native channel actions must be exact names; integer ordinals never authorize a refund decision.
 */
public final class IndustryChannelAfterSaleCodecs {
    private IndustryChannelAfterSaleCodecs() {}

    public static final class ActionValue
            extends ValueDeserializer<IndustryChannelAfterSalePort.Action> {
        @Override
        public IndustryChannelAfterSalePort.Action deserialize(
                JsonParser parser, DeserializationContext context) {
            if (parser.currentToken() != JsonToken.VALUE_STRING)
                return context.reportInputMismatch(
                        IndustryChannelAfterSalePort.Action.class, "String action required");
            try {
                return IndustryChannelAfterSalePort.Action.valueOf(parser.getString());
            } catch (IllegalArgumentException invalid) {
                return context.reportInputMismatch(
                        IndustryChannelAfterSalePort.Action.class, "Unknown native action");
            }
        }
    }
}
