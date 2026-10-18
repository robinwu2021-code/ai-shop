package ai.neargo.shop.pay.points.query;

import ai.neargo.shop.spi.points.PointsObligationPort.Obligations;

public interface PointsObligationService {
    Obligations inspect(String storeNo, String subOrderNo);
}
