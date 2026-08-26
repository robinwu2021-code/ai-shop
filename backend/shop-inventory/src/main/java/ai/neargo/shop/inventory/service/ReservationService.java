package ai.neargo.shop.inventory.service;

import java.util.List;

/**
 * 预留协议 —— <b>交易域与本领域之间唯一的写路径</b>。
 *
 * <p><b>跨进程不等于最终一致</b>：{@code available >= 0} 只需要在本领域自己的库里成立，
 * 一条条件更新即可，与调用方在不在同一个进程无关。跨库真正的代价是可用性耦合与一次 RTT，
 * 不是正确性。剩下的两种不一致都可补偿：
 * 预留成功而订单没建 → {@code expires_at} 到期回收；订单建了而预留失败 → 下单整体失败。
 * <b>两种今天都已经在发生</b>，不是新增的失败模式。
 *
 * <p>四个动作与平台今天的 {@code StockPort.lock/release/confirm} 一一对应，
 * 只多一个 {@link #restore}。
 */
public interface ReservationService {

    /**
     * 预留。**全成功或全失败**，不允许部分预留 ——
     * 部分成功意味着「购物车里 3 件商品成了 2 件」，这种单没法结算也没法退。
     *
     * <p>幂等：同一个 {@code externalRef} 再来一次，<b>返回原结果而不是再占一份</b>。
     * 网络超时重试是常态，不幂等就会预留两次，而第二次没人释放。
     *
     * @param ttlSeconds 到期自动回收。跨进程之后**兜底必须在本领域内** —— 调用方可能永远不回来
     * @throws ai.neargo.shop.common.BizException {@code STOCK_NOT_ENOUGH}，带上是哪几件不够
     */
    String reserve(String ownerId, String externalRef, List<Line> lines, long ttlSeconds);

    /**
     * 确认：{@code reserved} 转实扣，并**自动开一张 SALE 出库单**。
     *
     * <p>「直接扣库存」在这个模型里不存在 —— 会计问「这 200 斤米怎么少的」，
     * 要能点开一张单，而不是一行日志。
     *
     * @return 生成的出库单号；重复 commit 返回原单号
     */
    String commit(String ownerId, String externalRef, String operator);

    /** 释放。幂等：只作用于 {@code HELD} 的预留。 */
    void release(String ownerId, String externalRef);

    /**
     * 退货入库 —— 协议的第四个动作，<b>今天平台侧的缺口</b>。
     *
     * <p>触发判据是**售后类型**而不是「退款成功」：
     * {@code REFUND_ONLY} 不回补（货没回来）、{@code RETURN_REFUND} 回补、
     * {@code EXCHANGE} 一出一入。判据在调用方，本方法只管「回补这些行」。
     *
     * @return 生成的入库单号
     */
    String restore(String ownerId, String afterSaleNo, List<Line> lines, String operator);

    /** 回收到期未确认的预留。由定时任务调，返回回收条数。 */
    int expireOverdue(int limit);

    record Line(String itemId, String locationId, int qty) {
    }
}
