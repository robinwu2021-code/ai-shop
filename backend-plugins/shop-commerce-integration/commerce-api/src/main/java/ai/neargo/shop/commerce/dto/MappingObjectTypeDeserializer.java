package ai.neargo.shop.commerce.dto;

import ai.neargo.shop.spi.commerce.ChannelResourceIdentityGateway.ObjectType;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

/** 禁止枚举序号和大小写猜测，避免把未知平台类型默认为商品。 */
public final class MappingObjectTypeDeserializer extends ValueDeserializer<ObjectType> {
    @Override
    public ObjectType deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.currentToken() != JsonToken.VALUE_STRING)
            throw new IllegalArgumentException("Resource type must be text");
        return ObjectType.valueOf(parser.getString());
    }
}
