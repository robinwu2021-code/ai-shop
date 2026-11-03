package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

/** Adapter validates signature, algorithm, audience, key id and expiry. Never expose raw account bearer tokens. */
public interface EndContextCodecPort {
 String encode(ai.neargo.commerce.api.access.EndContext context);
  EndContextVerification verify(String encoded, String expectedAudience, Instant now);

}
