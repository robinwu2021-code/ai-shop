package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.points.earn.EarnService;
import ai.neargo.shop.spi.points.PointsEarnPort;
import org.springframework.stereotype.Component;

/** Cross-domain DTOs are delegated without leaking pay entities into the order domain. */
@Component
public class PointsEarnPortImpl implements PointsEarnPort {
    private final EarnService service;
    public PointsEarnPortImpl(EarnService service) { this.service=service; }
    @Override public GrantOutcome grant(GrantCommand command) { return service.grant(command); }
}
