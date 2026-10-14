package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.trade.entity.OrdOrder;

import java.util.List;

/** 建单草稿：一张支付单（主单）+ 其下各商家订单草稿，尚未落库。 */
public record PaymentDraft(OrdOrder payment, List<OrderDraft> orders) {
}
