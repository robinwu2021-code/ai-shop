package ai.neargo.shop.trade.order.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.order.contract.StoreOrderQuery;
import ai.neargo.shop.trade.order.contract.StoreOrderSummary;
import ai.neargo.shop.trade.order.contract.StoreOrderView;
import ai.neargo.shop.trade.order.service.query.StoreOrderMaterial;

/** Buyer fulfillment queries; merchant and platform scopes continue through their own authorized adapters. */
public interface StoreOrderQueryService {
    StoreOrderView detail(String subOrderNo, String buyerNo);
    PageData<StoreOrderSummary> list(String buyerNo, StoreOrderQuery query, long page, long size);
    StoreOrderMaterial material(String subOrderNo, String buyerNo);
    PageData<StoreOrderMaterial> materials(String buyerNo, StoreOrderQuery query, long page, long size);
}
