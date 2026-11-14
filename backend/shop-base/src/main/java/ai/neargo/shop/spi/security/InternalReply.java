package ai.neargo.shop.spi.security;

import ai.neargo.shop.svc.InternalReplyDeserializer;
import ai.neargo.shop.svc.InternalReplySerializer;

import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;

/** A reply binds the original request without returning private request values. */
@JsonSerialize(using = InternalReplySerializer.class)
@JsonDeserialize(using = InternalReplyDeserializer.class)
public record InternalReply<T>(InternalRequestBinding binding, String requestDigest, T result) {
    /** Missing results or malformed digests cannot represent successful business calls. */
    public InternalReply {
        if (binding == null
                || requestDigest == null
                || !requestDigest.matches("[0-9a-f]{64}")
                || result == null
                || !result.getClass().isRecord()) {
            throw new IllegalArgumentException("typed internal reply required");
        }
    }

    @Override
    public String toString() {
        return "InternalReply[REDACTED]";
    }
}
