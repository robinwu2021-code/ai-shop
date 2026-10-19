package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import java.time.Instant;

/** The source owner resolves the full frozen benefit before pay locks; caller-supplied references are not proof. */
public interface EntitlementGrantSourcePort {
    boolean supports(SourceRef source);
    SourceFact resolve(EntitlementContext context, GrantCommand command);

    /** Optional source-owner terminal truth; return null to use the shared durable core decision port. */
    default SourceDecisionPort.SourceDecision decision(EntitlementContext context, SourceRef source) { return null; }

    /** Exact command binding includes recipient, template, paid fact and participation permit. */
    record SourceFact(String tenantNo, GrantCommand binding, Instant paidAt) { }
}
