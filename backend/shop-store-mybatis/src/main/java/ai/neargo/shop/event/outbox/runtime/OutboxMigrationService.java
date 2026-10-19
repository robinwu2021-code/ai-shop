package ai.neargo.shop.event.outbox.runtime;

import java.time.Clock;
import java.util.List;

/** Explicit offline adoption. Inspection never changes events or infers historical consumer success. */
public final class OutboxMigrationService {
    private final UnifiedOutboxFacade facade;
    private final OutboxProperties properties;
    private final Clock clock;
    public OutboxMigrationService(UnifiedOutboxFacade facade, OutboxProperties properties, Clock clock) {
        this.facade = facade; this.properties = properties; this.clock = clock;
    }
    public List<JdbcOutboxStore.LegacyRecord> inspect(String source) {
        return facade.store(source).inspectLegacy(500);
    }
    public int adopt(String source, List<JdbcOutboxStore.LegacyDecision> confirmedManifest) {
        if (!properties.isMaintenance() || properties.isEnabled() || !facade.isMaintenance()) {
            throw new IllegalStateException("Adoption requires offline maintenance and disabled runner");
        }
        for (var decision : confirmedManifest) {
            decision.externalTargets().forEach((id, external) -> {
                var handler = facade.handler(source, id);
                if (handler == null || handler.externalWorkflow() != external) {
                    throw new IllegalArgumentException("Reviewed target does not match registered handler");
                }
            });
        }
        return facade.store(source).adoptLegacy(confirmedManifest, true, clock.instant());
    }
}
