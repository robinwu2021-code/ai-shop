package ai.neargo.shop.promotion.service;

import ai.neargo.shop.spi.entitlement.EntitlementContext;
import ai.neargo.shop.spi.entitlement.SourceRef;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ConfirmParticipation;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationPermit;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ParticipationView;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReleaseParticipation;
import ai.neargo.shop.spi.promotion.ActivityParticipationPort.ReserveActivity;

/** Core transaction owner; the cross-domain SPI is exposed only by its port adapter. */
public interface ActivityParticipationService {
    ParticipationPermit reserve(EntitlementContext context, ReserveActivity command);
    ParticipationView confirm(EntitlementContext context, ConfirmParticipation command);
    ParticipationView release(EntitlementContext context, ReleaseParticipation command);
    ParticipationView query(EntitlementContext context, SourceRef source);

    /** Reconcile trusted terminal outcomes; a deadline alone never releases capacity. */
    int recoverPending(int limit);
}
