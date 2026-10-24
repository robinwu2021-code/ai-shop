package ai.neargo.shop.spi.security;

import ai.neargo.shop.svc.InternalCallSerializer;
import ai.neargo.shop.svc.InternalReplyDeserializer;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/** Private wire payload; diagnostic text must never expose proofs or original keys. */
@JsonSerialize(using = InternalCallSerializer.class)
@JsonDeserialize(using = InternalReplyDeserializer.Call.class)
public record InternalCall<T>(InternalRequestBinding binding, T request) {
    /** Only typed record payloads can participate in the bound protocol. */
    public InternalCall {
        if (binding == null || request == null || !request.getClass().isRecord()) {
            throw new IllegalArgumentException("typed internal call required");
        }
    }

    @Override
    public String toString() {
        return "InternalCall[REDACTED]";
    }
}
