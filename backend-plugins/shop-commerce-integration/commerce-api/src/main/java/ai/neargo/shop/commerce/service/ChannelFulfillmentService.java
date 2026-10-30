package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.FulfillmentContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.common.PageData;

/** Local listings never query the supplier or advance local inventory, order or payment state. */
public interface ChannelFulfillmentService {
    PageData<FulfillmentContracts.Link> listFulfillments(
            String entityNo, String storeNo, String orderNo, int page, int size);

    OperationVO publishFulfillment(
            String entityNo,
            String storeNo,
            String actorNo,
            FulfillmentContracts.Publish request,
            String key);
}
