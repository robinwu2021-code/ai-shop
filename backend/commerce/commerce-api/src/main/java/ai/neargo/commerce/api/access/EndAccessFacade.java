package ai.neargo.commerce.api.access;

import ai.neargo.commerce.api.common.*;

/** requireForWrite must run inside the actual write transaction; server Clock supplies authoritative time. */
public interface EndAccessFacade {
 AccessDecision check(AccessCheck check);
  AccessDecision requireForWrite(AccessCheck check);
  EndContextToken issueEndContext(IssueEndContextCommand command);

}
