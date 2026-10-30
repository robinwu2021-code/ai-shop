package ai.neargo.shop.svc;

import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol.Binding;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol.Call;
import ai.neargo.shop.spi.trade.ExecutionOriginalProtocol.Reply;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.BeanProperty;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JavaType;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.ser.std.StdSerializer;

/** Isolated record annotations provide strict original wire handling without changing legacy HUMAN bytes. */
public final class ExecutionOriginalCodec {
    private ExecutionOriginalCodec() {}

    /** Explicit nested nulls keep the new call schema independent of global NON_NULL settings. */
    public static final class CallSerializer extends StdSerializer<Call<?>> {
        /** Registered only by the original call record annotation. */
        public CallSerializer() {
            super(Call.class);
        }

        @Override
        public void serialize(Call<?> value, JsonGenerator generator, SerializationContext context) {
            context.writeTree(generator, InternalBindings.tree(value));
        }
    }

    /** Original replies share canonical tree rules while retaining their separate envelope type. */
    public static final class ReplySerializer extends StdSerializer<Reply<?>> {
        /** Registered only by the original reply record annotation. */
        public ReplySerializer() {
            super(Reply.class);
        }

        @Override
        public void serialize(Reply<?> value, JsonGenerator generator, SerializationContext context) {
            context.writeTree(generator, InternalBindings.tree(value));
        }
    }

    /** Contextual route types prevent untyped maps or absent primitives from becoming original evidence. */
    public static final class CallDeserializer extends StdDeserializer<Call<?>> {
        private final JavaType requestType;

        /** Jackson resolves the route's concrete generic request before reading any proof. */
        public CallDeserializer() {
            this(null);
        }

        private CallDeserializer(JavaType requestType) {
            super(Call.class);
            this.requestType = requestType;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            return new CallDeserializer(payloadType(context, property, Call.class));
        }

        @Override
        public Call<?> deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode tree = InternalReplyDeserializer.readStrictTree(parser, context, 0, 64);
            requireEnvelope(tree, "request", requestType, false, context);
            JavaType bindingType = context.constructType(Binding.class);
            InternalReplyDeserializer.requireShape(tree.get("binding"), bindingType, context);
            InternalReplyDeserializer.requireShape(tree.get("request"), requestType, context);
            Binding binding = context.readTreeAsValue(tree.get("binding"), bindingType);
            Object request = context.readTreeAsValue(tree.get("request"), requestType);
            return new Call<>(binding, request);
        }
    }

    /** Original result projection must preserve every nullable and primitive property explicitly. */
    public static final class ReplyDeserializer extends StdDeserializer<Reply<?>> {
        private final JavaType resultType;

        /** Jackson resolves the exact result record for each original read route. */
        public ReplyDeserializer() {
            this(null);
        }

        private ReplyDeserializer(JavaType resultType) {
            super(Reply.class);
            this.resultType = resultType;
        }

        @Override
        public ValueDeserializer<?> createContextual(DeserializationContext context, BeanProperty property) {
            return new ReplyDeserializer(payloadType(context, property, Reply.class));
        }

        @Override
        public Reply<?> deserialize(JsonParser parser, DeserializationContext context) {
            JsonNode tree = InternalReplyDeserializer.readStrictTree(parser, context, 0, 64);
            requireEnvelope(tree, "result", resultType, true, context);
            JavaType bindingType = context.constructType(Binding.class);
            InternalReplyDeserializer.requireShape(tree.get("binding"), bindingType, context);
            InternalReplyDeserializer.requireShape(tree.get("result"), resultType, context);
            Binding binding = context.readTreeAsValue(tree.get("binding"), bindingType);
            Object result = context.readTreeAsValue(tree.get("result"), resultType);
            return new Reply<>(binding, tree.get("requestDigest").asString(), result);
        }
    }

    private static JavaType payloadType(DeserializationContext context, BeanProperty property, Class<?> envelopeClass) {
        JavaType envelope = context.getContextualType();
        if (envelope == null && property != null) {
            envelope = property.getType();
        }
        if (envelope == null || envelope.containedTypeCount() != 1
                || !envelope.containedType(0).isRecordType()) {
            return context.reportInputMismatch(envelopeClass, "typed original record required");
        }
        return envelope.containedType(0);
    }

    private static void requireEnvelope(JsonNode tree, String payload, JavaType type,
            boolean reply, DeserializationContext context) {
        if (type == null || !tree.isObject() || tree.size() != (reply ? 3 : 2)
                || !tree.has("binding") || tree.get("binding").isNull()
                || !tree.has(payload) || tree.get(payload).isNull()
                || reply && (!tree.has("requestDigest") || !tree.get("requestDigest").isString())) {
            context.reportInputMismatch(Call.class, "invalid original envelope");
        }
    }

}
