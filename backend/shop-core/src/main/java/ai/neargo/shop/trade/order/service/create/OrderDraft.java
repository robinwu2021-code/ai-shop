package ai.neargo.shop.trade.order.service.create;

import ai.neargo.shop.trade.entity.OrdItem;
import ai.neargo.shop.trade.entity.OrdSubOrder;

import java.util.List;

/** 建单草稿：一张订单（子单）+ 其商品行，尚未落库。 */
public record OrderDraft(OrdSubOrder order, List<OrdItem> items) {
}
