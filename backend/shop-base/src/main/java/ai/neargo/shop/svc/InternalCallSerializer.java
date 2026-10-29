package ai.neargo.shop.svc;

import ai.neargo.shop.spi.security.InternalCall;

import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ser.std.StdSerializer;

/** Explicit nested nulls keep the bound wire schema independent of retail mapper settings. */
public final class InternalCallSerializer extends StdSerializer<InternalCall<?>> {
    /** Register only on the bound envelope, without changing ordinary retail serialization. */
    public InternalCallSerializer() {
        super(InternalCall.class);
    }

    @Override
    public void serialize(
            InternalCall<?> value, JsonGenerator generator, SerializationContext context) {
        context.writeTree(generator, InternalBindings.tree(value));
    }
}
