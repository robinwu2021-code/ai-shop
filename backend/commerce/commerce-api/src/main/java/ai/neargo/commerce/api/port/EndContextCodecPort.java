package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.access.EndContext;

import java.time.Instant;

/**
 * Adapter validates signature, algorithm, audience, key id and expiry. Never expose raw account
 * bearer tokens.
 */
public interface EndContextCodecPort {
    String encode(EndContext context);

    EndContextVerification verify(String encoded, String expectedAudience, Instant now);
}
