package ai.neargo.shop.trade.order.service.create;

/** 下单号生成：支付单号 + 订单号（第 n 张）。接线阶段用现有 orderNoGenerator 实现。 */
public interface OrderNoGen {

    String nextPayNo();

    String subOrderNo(String payNo, int index);
}
