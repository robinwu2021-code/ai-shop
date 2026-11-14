package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelSourceValidationPort.Owner;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 行业路由不能通过枚举下标或大小写猜测得到。 */
public final class CommerceOwnerDeserializer extends ValueDeserializer<Owner> {
    @Override
    public Owner deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(Owner.class, "Source owner must be FOOD or RETAIL");
        }
        try {
            return Owner.valueOf(parser.getString());
        } catch (IllegalArgumentException invalid) {
            return context.reportInputMismatch(Owner.class, "Source owner must be FOOD or RETAIL");
        }
    }
}
