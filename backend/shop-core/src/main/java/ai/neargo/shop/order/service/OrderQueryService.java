package ai.neargo.shop.order.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.order.contract.OrderListItemVO;
import ai.neargo.shop.order.contract.OrderQuery;
import ai.neargo.shop.order.contract.UnifiedOrderVO;

/** 买家订单的<b>只读</b>查询（统一订单号 + 聚合状态）。 */
public interface OrderQueryService {

    /** 订单详情。{@code orderNo} 为统一订单号（主单号）；兼容传子单号。无权/不存在返回 null。 */
    UnifiedOrderVO detail(String orderNo, String userNo);

    /** 订单列表：一行一订单（主单粒度）。 */
    PageData<OrderListItemVO> list(String userNo, OrderQuery query, long page, long size);
}
