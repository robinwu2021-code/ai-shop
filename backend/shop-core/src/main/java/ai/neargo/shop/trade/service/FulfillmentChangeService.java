package ai.neargo.shop.trade.service;

import ai.neargo.shop.trade.dto.OrderVO;
import java.util.List;

/** Guarded changes to the fulfillment method of a paid order. */
public interface FulfillmentChangeService {
    record Option(String fulfillment, boolean allowed, String reason) { }
    record Options(String subOrderNo, String currentFulfillment, long version, List<Option> options) { }
    record Changed(OrderVO order, long version) { }

    Options options(String merchantNo, String storeNo, String subOrderNo);
    Changed change(String merchantNo, String storeNo, String subOrderNo,
                   String target, String reason, Long expectedVersion, String operatorNo);
}
