package ai.neargo.commerce.api.port;

import ai.neargo.commerce.api.common.*;

public interface CommerceAuditPort {
 void record(AuditEntry entry);

}
