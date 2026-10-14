package ai.neargo.shop.pay.points.redeem;

import ai.neargo.shop.spi.points.PointsRedeemPort.HoldCommand;
import ai.neargo.shop.spi.points.PointsRedeemPort.HoldOutcome;
import ai.neargo.shop.spi.points.PointsRedeemPort.RedeemQuery;
import ai.neargo.shop.spi.points.PointsRedeemPort.RedeemQuote;
import ai.neargo.shop.spi.points.PointsRedeemPort.ReleaseCommand;

/** Hold creates a backed promise; quote alone performs no financial change. */
public interface RedeemService {
    RedeemQuote quote(RedeemQuery query);
    HoldOutcome hold(HoldCommand command);
    HoldOutcome release(ReleaseCommand command);
}
