package ai.neargo.shop.svc;

import ai.neargo.shop.spi.product.ExecutionStockRetentionPort.RetentionLookup;
import ai.neargo.shop.spi.product.ExecutionStockRetentionPreparationObservation;
import ai.neargo.shop.spi.trade.ExecutionEvidenceLookup;
import ai.neargo.shop.spi.trade.ExecutionOriginalEvidence;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceBinding;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.EvidenceReply;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockBinding;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockCall;
import ai.neargo.shop.spi.trade.ExecutionPreparationOriginalProtocol.StockReply;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * Concrete record decoders reject unknown fields, absent primitives and arbitrary payload classes.
 */
public final class ExecutionPreparationOriginalCodec {
    private ExecutionPreparationOriginalCodec() {}

    public static final class EvidenceCallSerializer extends StdSerializer<EvidenceCall> {
        public EvidenceCallSerializer() {
            super(EvidenceCall.class);
        }

        @Override
        public void serialize(
                EvidenceCall value, JsonGenerator generator, SerializationContext context) {
            context.writeTree(generator, InternalBindings.tree(value));
        }
    }

    public static final class EvidenceCallDeserializer extends StdDeserializer<EvidenceCall> {
        public EvidenceCallDeserializer() {
            super(EvidenceCall.class);
        }

        @Override
        public EvidenceCall deserialize(JsonParser parser, DeserializationContext context) {
            var tree = InternalReplyDeserializer.readStrictTree(parser, context, 0, 64);
            if (!tree.isObject()
                    || tree.size() != 2
                    || !tree.has("binding")
                    || !tree.has("request")) {
                return context.reportInputMismatch(
                        EvidenceCall.class, "invalid preparation original envelope");
            }
            var bindingType = context.constructType(EvidenceBinding.class);
            var payloadType = context.constructType(ExecutionEvidenceLookup.class);
            InternalReplyDeserializer.requireShape(tree.get("binding"), bindingType, context);
            InternalReplyDeserializer.requireShape(tree.get("request"), payloadType, context);
            EvidenceBinding binding = context.readTreeAsValue(tree.get("binding"), bindingType);
            ExecutionEvidenceLookup value =
                    context.readTreeAsValue(tree.get("request"), payloadType);
            return new EvidenceCall(binding, value);
        }
    }

    public static final class EvidenceReplySerializer extends StdSerializer<EvidenceReply> {
        public EvidenceReplySerializer() {
            super(EvidenceReply.class);
        }

        @Override
        public void serialize(
                EvidenceReply value, JsonGenerator generator, SerializationContext context) {
            context.writeTree(generator, InternalBindings.tree(value));
        }
    }

    public static final class EvidenceReplyDeserializer extends StdDeserializer<EvidenceReply> {
        public EvidenceReplyDeserializer() {
            super(EvidenceReply.class);
        }

        @Override
        public EvidenceReply deserialize(JsonParser parser, DeserializationContext context) {
            var tree = InternalReplyDeserializer.readStrictTree(parser, context, 0, 64);
            if (!tree.isObject()
                    || tree.size() != 3
                    || !tree.has("binding")
                    || !tree.has("result")
                    || !tree.has("requestDigest")) {
                return context.reportInputMismatch(
                        EvidenceReply.class, "invalid preparation original envelope");
            }
            var bindingType = context.constructType(EvidenceBinding.class);
            var payloadType = context.constructType(ExecutionOriginalEvidence.class);
            InternalReplyDeserializer.requireShape(tree.get("binding"), bindingType, context);
            InternalReplyDeserializer.requireShape(tree.get("result"), payloadType, context);
            EvidenceBinding binding = context.readTreeAsValue(tree.get("binding"), bindingType);
            ExecutionOriginalEvidence value =
                    context.readTreeAsValue(tree.get("result"), payloadType);
            if (!tree.get("requestDigest").isString()) {
                return context.reportInputMismatch(EvidenceReply.class, "invalid request digest");
            }
            return new EvidenceReply(binding, tree.get("requestDigest").asString(), value);
        }
    }

    public static final class StockCallSerializer extends StdSerializer<StockCall> {
        public StockCallSerializer() {
            super(StockCall.class);
        }

        @Override
        public void serialize(
                StockCall value, JsonGenerator generator, SerializationContext context) {
            context.writeTree(generator, InternalBindings.tree(value));
        }
    }

    public static final class StockCallDeserializer extends StdDeserializer<StockCall> {
        public StockCallDeserializer() {
            super(StockCall.class);
        }

        @Override
        public StockCall deserialize(JsonParser parser, DeserializationContext context) {
            var tree = InternalReplyDeserializer.readStrictTree(parser, context, 0, 64);
            if (!tree.isObject()
                    || tree.size() != 2
                    || !tree.has("binding")
                    || !tree.has("request")) {
                return context.reportInputMismatch(
                        StockCall.class, "invalid preparation original envelope");
            }
            var bindingType = context.constructType(StockBinding.class);
            var payloadType = context.constructType(RetentionLookup.class);
            InternalReplyDeserializer.requireShape(tree.get("binding"), bindingType, context);
            InternalReplyDeserializer.requireShape(tree.get("request"), payloadType, context);
            StockBinding binding = context.readTreeAsValue(tree.get("binding"), bindingType);
            RetentionLookup value = context.readTreeAsValue(tree.get("request"), payloadType);
            return new StockCall(binding, value);
        }
    }

    public static final class StockReplySerializer extends StdSerializer<StockReply> {
        public StockReplySerializer() {
            super(StockReply.class);
        }

        @Override
        public void serialize(
                StockReply value, JsonGenerator generator, SerializationContext context) {
            context.writeTree(generator, InternalBindings.tree(value));
        }
    }

    public static final class StockReplyDeserializer extends StdDeserializer<StockReply> {
        public StockReplyDeserializer() {
            super(StockReply.class);
        }

        @Override
        public StockReply deserialize(JsonParser parser, DeserializationContext context) {
            var tree = InternalReplyDeserializer.readStrictTree(parser, context, 0, 64);
            if (!tree.isObject()
                    || tree.size() != 3
                    || !tree.has("binding")
                    || !tree.has("result")
                    || !tree.has("requestDigest")) {
                return context.reportInputMismatch(
                        StockReply.class, "invalid preparation original envelope");
            }
            var bindingType = context.constructType(StockBinding.class);
            var payloadType =
                    context.constructType(ExecutionStockRetentionPreparationObservation.class);
            InternalReplyDeserializer.requireShape(tree.get("binding"), bindingType, context);
            InternalReplyDeserializer.requireShape(tree.get("result"), payloadType, context);
            StockBinding binding = context.readTreeAsValue(tree.get("binding"), bindingType);
            ExecutionStockRetentionPreparationObservation value =
                    context.readTreeAsValue(tree.get("result"), payloadType);
            if (!tree.get("requestDigest").isString()) {
                return context.reportInputMismatch(StockReply.class, "invalid request digest");
            }
            return new StockReply(binding, tree.get("requestDigest").asString(), value);
        }
    }
}
