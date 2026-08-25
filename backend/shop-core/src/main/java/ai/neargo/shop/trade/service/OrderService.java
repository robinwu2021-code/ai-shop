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

    /**
     * 关掉指定的一笔待支付单（对账自查用：通道明确回「没有这笔」）。
     *
     * <p>与 {@link #closeExpiredOrders} 走同一段关单逻辑 —— 关单要连着释放库存、券、积分，
     * 两处各写一遍的话，漏掉的那一项会让库存一直占着，而没有任何报错。
     *
     * <p>已经不是待支付就当没事发生：对账每一轮都可能再撞到同一笔。
     */
    void closeUnpaid(String orderNo, String reason);

    /** 订单列表：**子单粒度**（Q6）—— 用户心智里「订单」就是按店分的。 */
    /**
     * 买家订单列表。
     *
     * @param status       抽象状态（{@code WAIT_PAY / PAID / FULFILLING / COMPLETED / ...}）。
     *                     <b>不再接受 ARRIVED / SHIPPED</b> —— 那是「状态 × 履约」的组合，
     *                     现在由 {@code fulfillments} 单独表达
     * @param fulfillments 想要的履约方式；空 = 不限。与 {@code status} <b>正交</b>：
     *                     「待取货」= {@code FULFILLING} + 自提类，
     *                     「待使用」= {@code FULFILLING} + 服务类
     */
    PageData<OrderVO> list(String status, java.util.List<String> fulfillments, long page, long size);

    OrderVO cancel(String orderNo, String reason);

    /**
     * 确认收货（C-4.4）。**非自提线的终态出口** —— 自提线走核销台。
     * 两条线殊途同归到 COMPLETED，评价与结算都以它为准。
     */
    OrderVO confirmReceipt(String subOrderNo);

    /**
     * @param items       下单行；为空时取购物车勾选行
     * @param fulfillment 履约方式：STORE_PICKUP / NEIGHBOR_PICKUP / MERCHANT_DELIVERY / EXPRESS
     * @param usePoints   想用多少积分。<b>只是意愿值</b> —— 服务端按
     *                    「商家开关 → 抵扣上限 → 账户余额 → 并发」四道闸截断，
     *                    传多少都不会超。null / 0 = 不用积分
     */
    /**
     * @param appointmentAt 预约开始时间戳。<b>仅 {@code APPOINTMENT} 履约需要，且必填</b> ——
     *                      缺了商家不知道该几点上门，买家也不知道自己约了没有
     */
    /**
     * @param payMode   {@link ai.neargo.shop.common.PayModes} 的取值。空按 {@code ONLINE} 处理 ——
     *                  存量端上不传这个字段，不能因为补了它就让老版本下不了单
     * @param payScene  下单端（{@link ai.neargo.shop.common.PayScenes}），由网关从
     *                  {@code X-Client} 头解析。<b>快照进订单</b>，积分发放的端判定读它
     */
    /**
     * @param appointmentSlotNo 预约时段编号。<b>这家店开了时段就必填</b>，
     *                          没开则忽略（走 {@code appointmentAt} 的旧路）。
     *                          归属会在占位那条 SQL 里比对 —— 端上传别家店的时段号占不到
     */
    record CreateOrderCommand(List<Item> items, String fulfillment, String pickupNo,
                              String addressId, String couponNo, Long usePoints, String remark,
                              Long appointmentAt, String payMode, String payScene,
                              String appointmentSlotNo) {

        public record Item(String goodsNo, String skuNo, int qty) {
        }
    }

    /**
     * @param payParams 端上调起支付的参数（微信 JSAPI 的 timeStamp/nonceStr/package/paySign 等）。
     *                  S2 是 stub 通道，S4 换真微信支付时这个结构不变
     */
    record PayResult(String orderNo, String payChannel, java.util.Map<String, String> payParams) {
    }

    /**
     * 结算页能力提示：能不能开票、能用哪些支付方式、额度够不够。
     *
     * <p>与 {@link #preview} 分开而不是合并：preview 回答「多少钱」，
     * 这个回答「付得了吗、票拿得到吗」。合并的话每次查订单详情都会多带三次查询，
     * 而那三件事在下单之后就不再变化。
     */
    ai.neargo.shop.trade.dto.CheckoutCapabilityVO capability(CreateOrderCommand cmd);
}
