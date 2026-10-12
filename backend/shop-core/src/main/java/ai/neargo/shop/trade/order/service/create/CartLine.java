package ai.neargo.shop.trade.order.service.create;

/**
 * 下单时一行已解析归属的购物项：SKU + 数量 + 所属商家 + 供货门店。
 * 落店解析的产物、拆单的输入。{@code merchantNo} 非空（解析保证）。
 */
public record CartLine(String skuNo, int qty, String merchantNo, String supplyStoreNo) {
}
