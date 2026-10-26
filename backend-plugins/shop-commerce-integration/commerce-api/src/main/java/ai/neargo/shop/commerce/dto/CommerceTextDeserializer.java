package ai.neargo.shop.commerce.dto;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 外部身份必须是字符串，防止数字/布尔值被静默转成合法业务号。 */
public final class CommerceTextDeserializer extends ValueDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return context.reportInputMismatch(
                    String.class, "Commerce identity must be a string token");
        }
        return parser.getString();
    }
}
