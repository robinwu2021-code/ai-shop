package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.List;

/**
 * 从拆单结果 {@link OrderGroup} 建一张订单（{@code OrdSubOrder}）+ 商品行（{@code OrdItem}），尚未落库。
 *
 * <p>订单服务 2.0：订单 = 门店粒度（ADR-031，{@code storeNo}；{@code entityNo} 记所属商家）；{@code order_no} 指向支付单（主单号），
 * {@code sub_order_no} 是对外订单号；每行记供货门店（{@code supplyStoreNo}）。
 * 金额本环节只填<b>商品额</b>（Σ 行金额）；运费/优惠/积分抵扣由后续环节调整。
 */
public final class OrderDraftFactory {

    private OrderDraftFactory() {
    }

    public static OrderDraft build(String payNo, String userNo, String orderNo, OrderGroup group) {
        long goods = group.lines().stream().mapToLong(CartLine::amount).sum();

        OrdSubOrder order = new OrdSubOrder();
        order.setSubOrderNo(orderNo);
        order.setOrderNo(payNo);
        order.setUserNo(userNo);
        order.setEntityNo(group.merchantNo());
        order.setStoreNo(group.storeNo());
        order.setStatus(OrdSubOrder.WAIT_PAY);
        order.setGoodsAmount(goods);
        order.setPayAmount(goods);

        List<OrdItem> items = group.lines().stream().map(l -> toItem(payNo, orderNo, l)).toList();
        return new OrderDraft(order, items);
    }

    private static OrdItem toItem(String payNo, String orderNo, CartLine l) {
        OrdItem i = new OrdItem();
        i.setOrderNo(payNo);
        i.setSubOrderNo(orderNo);
        i.setSkuNo(l.skuNo());
        i.setQty(l.qty());
        i.setPrice(l.price());
        i.setAmount(l.amount());
        i.setSupplyStoreNo(l.supplyStoreNo());
        i.setShippedQty(0);
        return i;
    }
}
