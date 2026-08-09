package ai.neargo.shop.trade.service;

import ai.neargo.shop.common.PageData;
import ai.neargo.shop.trade.dto.OrderVO;

import java.util.List;

/** 交易主干（[API 清单 §2.4]）。 */
public interface OrderService {

    /**
     * 结算预览：**按商家拆单 + 试算金额**，不落库、不锁库存。
     * 端上进结算页先调它，拿到的子单结构就是下单后的结构 —— 两处用同一套拆单逻辑，
     * 否则「预览显示 2 个包裹、下单变 3 个」这种问题会反复出现。
     */
    OrderVO preview(CreateOrderCommand cmd);

    /** 下单。幂等由 {@code Idempotency-Key} 保证；库存在此锁定，支付成功才实扣。 */
    OrderVO create(CreateOrderCommand cmd, String idempotencyKey);

    /** 发起支付，返回端上调起支付所需的参数。**端侧不自判成功**，以回调/回查为准。 */
    PayResult pay(String orderNo);

    /** 支付结果回查（端上轮询用）。 */
    OrderVO payResult(String orderNo);

    /** 支付成功（回调驱动）。幂等：重复回调不会重复扣库存、重复发事件。 */
    void markPaid(String orderNo, String payChannel, String payTradeNo);

    /**
     * 订单详情。**同时接受主单号与子单号**（Q6）：
     * 主单号 → 支付视角（合计 + subOrders）；子单号 → 订单视角（单商家 + 核销码 + 时间线）。
     */
    OrderVO detail(String orderNo);

    /**
     * 关闭超时未支付的订单并释放库存（R7）。
     * 由定时任务调用；参数化「当前时间」是为了让测试不必真等 15 分钟。
     *
     * @param now 判定基准时间（毫秒）
     * @return 关闭的订单数
     */
    int closeExpiredOrders(long now);

    /** 订单列表：**子单粒度**（Q6）—— 用户心智里「订单」就是按店分的。 */
    PageData<OrderVO> list(String status, long page, long size);

    OrderVO cancel(String orderNo, String reason);

    /**
     * 确认收货（C-4.4）。**非自提线的终态出口** —— 自提线走核销台。
     * 两条线殊途同归到 COMPLETED，评价与结算都以它为准。
     */
    OrderVO confirmReceipt(String subOrderNo);

    /**
     * @param items       下单行；为空时取购物车勾选行
     * @param fulfillment 履约方式：STORE_PICKUP / NEIGHBOR_PICKUP / MERCHANT_DELIVERY / EXPRESS
     */
    record CreateOrderCommand(List<Item> items, String fulfillment, String pickupNo,
                              String addressId, String couponNo, String remark) {

        public record Item(String goodsNo, String skuNo, int qty) {
        }
    }

    /**
     * @param payParams 端上调起支付的参数（微信 JSAPI 的 timeStamp/nonceStr/package/paySign 等）。
     *                  S2 是 stub 通道，S4 换真微信支付时这个结构不变
     */
    record PayResult(String orderNo, String payChannel, java.util.Map<String, String> payParams) {
    }
}
