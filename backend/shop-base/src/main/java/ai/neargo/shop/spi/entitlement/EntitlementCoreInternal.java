package ai.neargo.shop.spi.entitlement;

import ai.neargo.shop.spi.entitlement.EntitlementGrantPort.GrantCommand;
import java.util.Objects;

/** Core fact lookup protocol. Only service-authenticated main-app endpoints may supply these responses. */
public final class EntitlementCoreInternal {
    private EntitlementCoreInternal() { }
    public static final String USE_SOURCE = "/internal/core/entitlement/source/use";
    public static final String SOURCE_QUERY = "/internal/core/entitlement/source/query";
    public static final String AUTHORIZATION = "/internal/core/entitlement/authorization/query";
    public static final String GRANT_SOURCE = "/internal/core/entitlement/source/grant";
    public static final String GRANT_DECISION = "/internal/core/entitlement/source/grant-decision";
    public static final String AUDIENCE = "/internal/core/entitlement/audience/resolve";
    public record SourceQuery(EntitlementContext context, SourceRef source) {
        public SourceQuery { Objects.requireNonNull(context); Objects.requireNonNull(source); }
    }
    public record AuthorizationQuery(EntitlementContext context, String authorizationRef) {
        public AuthorizationQuery { Objects.requireNonNull(context); ContractChecks.id(authorizationRef); }
    }
    public record GrantSourceQuery(EntitlementContext context, GrantCommand command) {
        public GrantSourceQuery { Objects.requireNonNull(context); Objects.requireNonNull(command); }
    }
    public record AudienceQuery(EntitlementContext context, String entityNo, String audienceFactRef) {
        public AudienceQuery {
            Objects.requireNonNull(context); ContractChecks.id(entityNo); ContractChecks.id(audienceFactRef);
        }
    }
}
