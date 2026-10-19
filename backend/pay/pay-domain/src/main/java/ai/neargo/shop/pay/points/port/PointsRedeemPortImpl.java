package ai.neargo.shop.pay.points.port;

import ai.neargo.shop.pay.points.redeem.RedeemService;
import ai.neargo.shop.spi.points.PointsRedeemPort;
import org.springframework.stereotype.Component;

/** Trusted order services receive only immutable points outcomes. */
@Component
public class PointsRedeemPortImpl implements PointsRedeemPort {
    private final RedeemService service;
    public PointsRedeemPortImpl(RedeemService service) { this.service=service; }
    @Override public RedeemQuote quote(RedeemQuery c) { return service.quote(c); }
    @Override public HoldOutcome hold(HoldCommand c) { return service.hold(c); }
    @Override public HoldOutcome release(ReleaseCommand c) { return service.release(c); }
}
