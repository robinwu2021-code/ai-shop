package ai.neargo.shop.commerce.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 公共配置不接受字符串/数值隐式转换。 */
public final class CommerceIntegerDeserializer extends ValueDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT)
            return context.reportInputMismatch(Integer.class, "Integer token required");
        return parser.getIntValue();
    }
}
