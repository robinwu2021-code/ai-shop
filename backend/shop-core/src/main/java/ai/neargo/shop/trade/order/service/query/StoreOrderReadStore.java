package ai.neargo.shop.trade.order.service.query;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.order.contract.StoreOrderQuery;

/** Both parent and child ownership are checked in persistence; page dependencies are loaded in batches. */
public interface StoreOrderReadStore {
    StoreOrderMaterial findOwned(String subOrderNo, String buyerNo);
    PageData<StoreOrderMaterial> pageOwned(String buyerNo, StoreOrderQuery query, long page, long size);
}
