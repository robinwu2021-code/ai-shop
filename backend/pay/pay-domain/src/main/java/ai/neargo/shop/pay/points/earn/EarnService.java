package ai.neargo.shop.pay.points.earn;

import ai.neargo.shop.spi.points.PointsEarnPort.GrantCommand;
import ai.neargo.shop.spi.points.PointsEarnPort.GrantOutcome;

/** Earning turns an existing platform budget into backed user rights. */
public interface EarnService {
    GrantOutcome grant(GrantCommand command);

    /** Apply a grant already promised by a verified source PAID fact during drain/recovery. */
    GrantOutcome grantAccepted(GrantCommand command);
}
