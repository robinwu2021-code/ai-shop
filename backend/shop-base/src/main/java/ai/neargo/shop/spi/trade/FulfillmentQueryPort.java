package ai.neargo.shop.spi.trade;

import java.util.List;
import java.util.Optional;

/**
 * fulfillment → trade：核销台要读订单、也要推进订单状态。
 *
 * <p>为什么不让 fulfillment 直接查 {@code ord_sub_order}：那等于两个模块共写一张表，
 * 状态机就有了第二个入口。所有状态变更必须经 trade 的状态机（TDD-backend §8）。
 */
public interface FulfillmentQueryPort {

    /** 按核销码找单（全局唯一）。 */
    Optional<PickupOrder> findByVerifyCode(String verifyCode);

    /** 某自提点下的订单，按状态过滤（status 可空 = 全部）。 */
    List<PickupOrder> ordersOfPickup(String pickupNo, String status);

    /**
     * 某个团的待取订单（邻里自提，C-GB-06）。
     *
     * <p><b>作用域就是 groupNo</b> —— 发起人只能看到、也只能核销自己这一团的单。
     * 不按团裁剪的话，任何一个开过团的用户都能拿到别人团的核销码（ADR-005 / E16）。
     */
    List<PickupOrder> ordersOfGroup(String groupNo, String status);

    /**
     * 推进为已完成（核销成功 / 确认收货）。**状态机在 trade 侧**，这里只是触发。
     *
     * @return 是否推进成功（已是终态则 false）
     */
    boolean complete(String subOrderNo, String operatorNo, String label);

    /**
     * 履约必需字段。**刻意不含金额与完整手机号** —— Port 返回的结构本身就是裁剪过的，
     * 调用方即使想泄漏也拿不到（越权防线④在跨模块边界上再加一道）。
     */
    /**
     * @param groupNo 参团下单时的团号。<b>邻里自提的核销作用域靠它裁剪</b>（ADR-005 / E16）——
     *                没有它，任何开过团的人都能拿别人团的码来核销
     */
    record PickupOrder(String subOrderNo, String verifyCode, String status,
                       String pickupNo, String merchantNo, String merchantName,
                       String buyerNickname, String buyerPhoneTail,
                       String groupNo, List<Item> items) {

        public record Item(String goodsNo, String title, String spec, int qty) {
        }
    }
}
