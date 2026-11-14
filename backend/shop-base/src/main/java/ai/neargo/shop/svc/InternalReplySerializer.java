package ai.neargo.shop.svc;

import ai.neargo.shop.spi.security.InternalReply;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/** Explicit nested nulls keep the bound wire schema independent of retail mapper settings. */
public final class InternalReplySerializer extends StdSerializer<InternalReply<?>> {
    /** Register only on the bound envelope, without changing ordinary retail serialization. */
    public InternalReplySerializer() {
        super(InternalReply.class);
    }

    @Override
    public void serialize(
            InternalReply<?> value, JsonGenerator generator, SerializationContext context) {
        context.writeTree(generator, InternalBindings.tree(value));
    }
}
