package ai.neargo.shop.commerce.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 修订号只接受整数，避免字符串或小数被转换为有效版本。 */
public final class CommerceVersionDeserializer extends ValueDeserializer<Long> {
    @Override
    public Long deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) {
            return context.reportInputMismatch(Long.class, "Version must be a nonnegative integer");
        }
        long value = parser.getLongValue();
        if (value < 0)
            return context.reportInputMismatch(Long.class, "Version must be nonnegative");
        return value;
    }
}
