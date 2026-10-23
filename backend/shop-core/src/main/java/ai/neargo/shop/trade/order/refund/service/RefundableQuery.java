package ai.neargo.shop.trade.order.refund.service;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;
import ai.neargo.shop.trade.order.refund.model.RefundableView;
import ai.neargo.shop.trade.order.refund.port.RefundSourceStore;

import java.util.List;

/**
 * 「这张订单此刻能退什么」：读源数据交给 {@link RefundableViewFactory} 算。只读；结果不缓存（金额必须实时）。
 */
public class RefundableQuery {

    private final RefundSourceStore source;
    private final RefundableViewFactory factory;

    public RefundableQuery(RefundSourceStore source, RefundableViewFactory factory) {
        this.source = source;
        this.factory = factory;
    }

    public RefundableView view(OrdSubOrder sub) {
        return factory.build(sub, items(sub), source.payChannel(sub.getOrderNo()));
    }

    public List<OrdItem> lockItems(OrdSubOrder sub) { return source.lockItems(sub.getSubOrderNo()); }

    public List<OrdItem> items(OrdSubOrder sub) {
        return source.items(sub.getSubOrderNo());
    }
}
