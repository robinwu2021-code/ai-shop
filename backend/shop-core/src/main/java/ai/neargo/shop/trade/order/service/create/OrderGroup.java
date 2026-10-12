package ai.neargo.shop.trade.order.service.create;

import java.util.List;

/**
 * 拆单结果：一组 = 一个商家 = 一张订单（订单服务 2.0 按商家拆）。
 * 同一商家多门店的货归一组，门店在行上（{@link CartLine#supplyStoreNo()}）。
 */
public record OrderGroup(String merchantNo, List<CartLine> lines) {
}
