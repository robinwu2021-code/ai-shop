package ai.neargo.shop.trade.order.service.create;

import java.util.List;

/**
 * 拆单结果：一组 = 一家<b>门店</b> = 一张订单（ADR-031）。{@code merchantNo} 是主体
 * （结算、额度、营销按它），{@code storeNo} 是这组由哪家店卖、哪家店发。
 * 同一主体两家店的货在一单里是两组（两张订单）。
 */
public record OrderGroup(String merchantNo, String storeNo, List<CartLine> lines) {
}
