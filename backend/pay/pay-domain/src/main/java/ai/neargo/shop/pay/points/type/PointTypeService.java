package ai.neargo.shop.pay.points.type;

import ai.neargo.shop.pay.points.model.PointType;
import ai.neargo.shop.pay.points.model.RedeemRate;

/** One active type per market prevents mixed-rate balances from becoming interchangeable. */
public interface PointTypeService {
    PointType create(CreateType command, String actor);
    PointType activate(String typeCode, String actor);
    PointType drain(String typeCode, String actor);
    record CreateType(String typeCode, String market, RedeemRate rate, int deductRatioBp, int idleDays) { }
}
