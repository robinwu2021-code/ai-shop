package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ChannelOrderContracts;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.common.PageData;

/** Local queries never query the supplier or implicitly accept an external order. */
public interface ChannelOrderService {
    OperationVO accept(
            String entityNo,
            String storeNo,
            String orderNo,
            String actorNo,
            ChannelOrderContracts.Accept request,
            String key);

    OperationVO reject(
            String entityNo,
            String storeNo,
            String orderNo,
            String actorNo,
            ChannelOrderContracts.Reject request,
            String key);

    PageData<ChannelOrderContracts.Order> listOrders(
            String entityNo, String storeNo, ChannelOrderContracts.Query query);

    PageData<ChannelOrderContracts.Revision> listRevisions(
            String entityNo, String storeNo, String channelOrderNo, int page, int size);

    /** Projects a specified immutable order revision without exposing recipient details. */
    PageData<ChannelOrderContracts.Line> listLines(
            String entityNo,
            String storeNo,
            String channelOrderNo,
            String orderRevisionNo,
            int page,
            int size);

    /** Schedules an original read; never overwrites local acceptance with a provider response. */
    OperationVO refreshOrder(
            String entityNo,
            String storeNo,
            String channelOrderNo,
            String actorNo,
            ChannelOrderContracts.Refresh request,
            String key);

    /**
     * Wakes the original import workflow with the same import key and without repeating uncertain
     * supplier writes.
     */
    OperationVO recoverImport(
            String entityNo,
            String storeNo,
            String channelOrderNo,
            String actorNo,
            ChannelOrderContracts.Recover request,
            String key);

    ChannelOrderContracts.Detail getOrder(String entityNo, String storeNo, String channelOrderNo);
}
