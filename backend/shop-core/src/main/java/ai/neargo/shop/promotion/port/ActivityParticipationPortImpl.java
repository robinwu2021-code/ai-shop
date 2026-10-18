package ai.neargo.shop.promotion.port;

import ai.neargo.shop.promotion.service.ActivityParticipationService;
import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort;
import org.springframework.stereotype.Component;

/** Cross-domain adapter; all accounting and transactional decisions remain in the core service. */
@Component
public class ActivityParticipationPortImpl implements ActivityParticipationPort {
    private final ActivityParticipationService service;

    public ActivityParticipationPortImpl(ActivityParticipationService service) {
        this.service = service;
    }

    @Override public ParticipationPermit reserve(EntitlementContext context, ReserveActivity command) {
        return service.reserve(context, command);
    }

    @Override public ParticipationView confirm(EntitlementContext context, ConfirmParticipation command) {
        return service.confirm(context, command);
    }

    @Override public ParticipationView release(EntitlementContext context, ReleaseParticipation command) {
        return service.release(context, command);
    }

    @Override public ParticipationView query(EntitlementContext context, SourceRef source) {
        return service.query(context, source);
    }
}
