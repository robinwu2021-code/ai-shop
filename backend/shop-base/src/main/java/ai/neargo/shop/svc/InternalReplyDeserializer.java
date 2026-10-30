package ai.neargo.shop.svc;

import ai.neargo.shop.spi.security.InternalCall;
import ai.neargo.shop.spi.security.InternalReply;
import ai.neargo.shop.spi.security.InternalRequestBinding;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.node.JsonNodeFactory;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Omitted primitive or nullable facts cannot become a fabricated successful projection. */
public final class InternalReplyDeserializer extends StdDeserializer<InternalReply<?>> {
    private final JavaType resultType;

    /** Jackson resolves the concrete generic result for each declared internal route. */
    public InternalReplyDeserializer() {
        this(null);
    }

    private InternalReplyDeserializer(JavaType resultType) {
        super(InternalReply.class);
        this.resultType = resultType;
    }

    @Override
    public ValueDeserializer<?> createContextual(
            DeserializationContext context, BeanProperty property) {
        JavaType envelope = context.getContextualType();
        if (envelope == null && property != null) {
            envelope = property.getType();
        }
        if (envelope == null
                || envelope.containedTypeCount() != 1
                || !envelope.containedType(0).isRecordType()) {
            return context.reportInputMismatch(InternalReply.class, "typed bound result required");
        }
        return new InternalReplyDeserializer(envelope.containedType(0));
    }

    @Override
    public InternalReply<?> deserialize(JsonParser parser, DeserializationContext context) {
        JsonNode tree = readStrictTree(parser, context, 0, -1);
        if (resultType == null
                || !tree.isObject()
                || tree.size() != 3
                || !tree.has("binding")
                || tree.get("binding").isNull()
                || !tree.has("requestDigest")
                || !tree.has("result")
                || tree.get("result").isNull()
                || !tree.get("requestDigest").isString()) {
            return context.reportInputMismatch(InternalReply.class, "invalid bound reply");
        }
        JavaType bindingType = context.constructType(InternalRequestBinding.class);
        requireShape(tree.get("binding"), bindingType, context);
        requireShape(tree.get("result"), resultType, context);
        InternalRequestBinding binding = context.readTreeAsValue(tree.get("binding"), bindingType);
        Object result = context.readTreeAsValue(tree.get("result"), resultType);
        return new InternalReply<>(binding, tree.get("requestDigest").asString(), result);
    }

    /** Shared bound readers reject duplicate keys before a tree can discard their original occurrence. */
    static JsonNode readStrictTree(JsonParser parser, DeserializationContext context, int depth, int maxDepth) {
        if (maxDepth >= 0 && depth > maxDepth) {
            return context.reportInputMismatch(InternalCall.class, "bound message nesting exceeded");
        }
        if (parser.currentToken() == JsonToken.START_OBJECT) {
            var result = JsonNodeFactory.instance.objectNode();
            Set<String> fields = new HashSet<>();
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                String field = parser.currentName();
                if (field == null || !fields.add(field) || parser.nextToken() == null) {
                    return context.reportInputMismatch(InternalCall.class, "duplicate or incomplete bound field");
                }
                result.set(field, readStrictTree(parser, context, depth + 1, maxDepth));
            }
            return result;
        }
        if (parser.currentToken() == JsonToken.START_ARRAY) {
            var result = JsonNodeFactory.instance.arrayNode();
            while (parser.nextToken() != JsonToken.END_ARRAY) {
                if (parser.currentToken() == null) {
                    return context.reportInputMismatch(InternalCall.class, "incomplete bound array");
                }
                result.add(readStrictTree(parser, context, depth + 1, maxDepth));
            }
            return result;
        }
        return context.readTree(parser);
    }

    static void requireShape(
            JsonNode value, JavaType type, DeserializationContext context) {
        if (value == null || value.isNull() && type.isPrimitive()) {
            context.reportInputMismatch(type, "required bound field absent");
        }
        if (value.isNull()) {
            return;
        }
        if (type.isRecordType()) {
            RecordComponent[] components = type.getRawClass().getRecordComponents();
            if (!value.isObject() || value.size() != components.length) {
                context.reportInputMismatch(type, "invalid bound record shape");
            }
            for (RecordComponent component : components) {
                JavaType child =
                        context.getTypeFactory()
                                .resolveMemberType(component.getGenericType(), type.getBindings());
                requireShape(value.get(component.getName()), child, context);
            }
        } else if (type.isCollectionLikeType()) {
            if (!value.isArray()) {
                context.reportInputMismatch(type, "bound array required");
            }
            value.forEach(child -> requireShape(child, type.getContentType(), context));
        } else if (type.isMapLikeType()) {
            if (!value.isObject() || !type.getKeyType().hasRawClass(String.class)) {
                context.reportInputMismatch(type, "bound map required");
            }
            value.properties()
                    .forEach(
                            entry ->
                                    requireShape(entry.getValue(), type.getContentType(), context));
        } else {
            Class<?> raw = type.getRawClass();
            boolean valid;
            if (raw == boolean.class || raw == Boolean.class) {
                valid = value.isBoolean();
            } else if (raw == BigDecimal.class) {
                valid = value.isNumber();
            } else if (raw == byte.class
                    || raw == short.class
                    || raw == int.class
                    || raw == long.class
                    || raw == Byte.class
                    || raw == Short.class
                    || raw == Integer.class
                    || raw == Long.class
                    || raw == BigInteger.class) {
                valid = value.isIntegralNumber();
            } else if (raw.isEnum()) {
                valid =
                        value.isString()
                                && Arrays.stream(raw.getEnumConstants())
                                        .anyMatch(
                                                item ->
                                                        ((Enum<?>) item)
                                                                .name()
                                                                .equals(value.asString()));
            } else {
                valid =
                        (raw == String.class
                                        || raw == Instant.class
                                        || raw == OffsetDateTime.class
                                        || raw == LocalDate.class)
                                && value.isString();
            }
            if (!valid) {
                context.reportInputMismatch(type, "invalid bound scalar type");
            }
        }
    }

    /**
     * Incoming calls require the same explicit schema as replies, including nullable C coordinates.
     */
    public static final class Call extends StdDeserializer<InternalCall<?>> {
        private final JavaType requestType;

        /** Jackson supplies the route's exact request record during contextualization. */
        public Call() {
            this(null);
        }

        private Call(JavaType requestType) {
            super(InternalCall.class);
            this.requestType = requestType;
        }

        @Override
        public ValueDeserializer<?> createContextual(
                DeserializationContext context, BeanProperty property) {
            JavaType envelope = context.getContextualType();
            if (envelope == null && property != null) {
                envelope = property.getType();
            }
            if (envelope == null
                    || envelope.containedTypeCount() != 1
                    || !envelope.containedType(0).isRecordType()) {
                return context.reportInputMismatch(
                        InternalCall.class, "typed bound request required");
            }
            return new Call(envelope.containedType(0));
        }

        @Override
        public InternalCall<?> deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode tree = readStrictTree(parser, context, 0, -1);
            if (requestType == null
                    || !tree.isObject()
                    || tree.size() != 2
                    || !tree.has("binding")
                    || tree.get("binding").isNull()
                    || !tree.has("request")
                    || tree.get("request").isNull()) {
                return context.reportInputMismatch(InternalCall.class, "invalid bound call");
            }
            JavaType bindingType = context.constructType(InternalRequestBinding.class);
            requireShape(tree.get("binding"), bindingType, context);
            requireShape(tree.get("request"), requestType, context);
            InternalRequestBinding binding =
                    context.readTreeAsValue(tree.get("binding"), bindingType);
            Object request = context.readTreeAsValue(tree.get("request"), requestType);
            return new InternalCall<>(binding, request);
        }
    }
}
