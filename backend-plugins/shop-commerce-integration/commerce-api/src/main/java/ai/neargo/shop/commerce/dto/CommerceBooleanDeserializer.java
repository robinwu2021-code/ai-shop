package ai.neargo.shop.commerce.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 公共配置不接受字符串/数值隐式转换。 */
public final class CommerceBooleanDeserializer extends ValueDeserializer<Boolean> {
    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_TRUE
                && parser.currentToken() != JsonToken.VALUE_FALSE) {
            return context.reportInputMismatch(Boolean.class, "Boolean token required");
        }
        return parser.getBooleanValue();
    }
}
