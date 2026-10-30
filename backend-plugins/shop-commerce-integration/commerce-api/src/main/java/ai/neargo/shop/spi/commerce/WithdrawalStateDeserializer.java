package ai.neargo.shop.spi.commerce;

import ai.neargo.shop.spi.commerce.ChannelSourceWithdrawalProvider.State;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** Native withdrawal disposition has no ordinal or case-insensitive coercion. */
public final class WithdrawalStateDeserializer extends ValueDeserializer<State> {
    @Override
    public State deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING)
            return context.reportInputMismatch(State.class, "Expected exact disposition string");
        try {
            return State.valueOf(parser.getString());
        } catch (IllegalArgumentException invalid) {
            return context.reportInputMismatch(State.class, "Unknown native disposition");
        }
    }
}
