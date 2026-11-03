package ai.neargo.commerce.api.port;

import java.time.Instant;
import ai.neargo.commerce.api.common.*;

/** append and inbound dedup join the caller local DataSource transaction; eventNo/fact identity is unique. No external calls while holding commerce locks. */
public interface CommerceEventPersistencePort {
 boolean append(CommerceEvent event);
  boolean recordInbound(InboundFact fact);
  EventPage pending(EventQuery query);
  void markPublished(String tenantNo, String eventNo, Instant publishedAt);

}
