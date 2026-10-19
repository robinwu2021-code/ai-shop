package ai.neargo.shop.trade.order.points;

import java.util.List;

/** Durable cursor for the bounded source closure sweep. */
public interface OrderPointsClosureScan {
    List<String> next(int limit);
}
