package ai.neargo.shop.trade.order.service.refund;

import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.List;

/** 退款读出口（小 port），便于 RefundHandler 纯逻辑单测。 */
public interface RefundReadStore {

    OrdSubOrder findOrder(String subOrderNo);

    /** 某行当前退款信息（已下单/已退/单价）。 */
    LineRefundInfo lineInfo(String subOrderNo, String itemId);

    /** 退款落库后，该订单各行的下单/已退数量 —— 判整单是否退完。 */
    List<LineRefund> lineRefundsAfter(String subOrderNo);
}
