package ai.neargo.shop.trade.order.service.create;

/**
 * 下单时一行已解析归属的购物项：SKU + 数量 + 单价(分) + 所属商家 + 供货门店。
 * 落店+定价解析的产物、拆单与建单的输入。{@code merchantNo} 非空。
 */
public record CartLine(String skuNo, int qty, long price, String merchantNo, String supplyStoreNo) {

    /** 该行金额(分) = 单价 × 数量。 */
    public long amount() {
        return price * qty;
    }
}
