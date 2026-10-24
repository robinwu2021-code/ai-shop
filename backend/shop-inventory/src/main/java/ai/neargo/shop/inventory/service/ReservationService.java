package ai.neargo.shop.inventory.service;

import java.util.List;
import java.util.Optional;

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

    default String reserve(String ownerId, String externalRef, List<Line> lines, long ttlSeconds, int lifecycleVersion) {
        return reserve(ownerId, externalRef, lines, ttlSeconds);
    }

    /** Returns persisted original facts; absence is distinct from a durable RELEASED cancellation tombstone. */
    Optional<Snapshot> query(String ownerId, String externalRef);

    /** Typed inventory lifecycle states, including partial fulfillment and partial cancellation. */
    enum Status {
        HELD, ACCEPTED_UNPAID, PARTIAL_UNPAID, ISSUED_UNPAID,
        ALLOCATED, PARTIALLY_COMMITTED, COMMITTED, PARTIAL_RELEASED, RELEASED, EXPIRED
    }

    /** Original reserved quantities remain unchanged by issue/release; callers can compare retry intent exactly. */
    record Snapshot(String reservationId, Status status, int lifecycleVersion,
                    boolean paymentAcknowledged, List<Line> lines) {
        public Snapshot {
            lines = List.copyOf(lines);
        }
    }

    /** Accepts lifecycle3 credit orders without recording payment; accepted holds are excluded from expiry. */
    void acceptUnpaid(String ownerId, String externalRef);

    /**
     * 确认：{@code reserved} 转实扣，并**自动开一张 SALE 出库单**。
     *
     * <p>「直接扣库存」在这个模型里不存在 —— 会计问「这 200 斤米怎么少的」，
     * 要能点开一张单，而不是一行日志。
     *
     * <p>Lifecycle3 acknowledges payment only; physical stock is issued by fulfillment handoff.
     *
     * @return 生成的出库单号；重复 commit 返回原单号；lifecycle3 未交付时为 null
     */
    String commit(String ownerId, String externalRef, String operator);

    /** V2 allocates paid holds; V3 acknowledges payment without deducting stock already issued on credit. */
    void allocatePaidByRef(String externalRef);

    /** Owner-bound transport commands must not mutate another owner's coincident external reference. */
    void allocatePaid(String ownerId, String externalRef);

    /** Owner/reference-bound handoff uses the same original reservation facts as the existing global entry. */
    String issue(String ownerId, String externalRef, String fulfillmentRef, String operator);

    /** Issues only the original reserved lines belonging to one fulfillment reference. */
    String issueByFulfillmentRef(String fulfillmentRef, String operator);

    /** Refund before handoff releases paid allocation without creating a stock receipt. */
    void releaseAllocatedByRef(String externalRef);

    void releaseAllocatedByFulfillmentRef(String fulfillmentRef);

    record ReleaseTarget(String itemId, int cumulativeQty) { }
    /** Set original unissued release progress monotonically, under owner/reference/child locks. */
    void releaseAllocatedTo(String ownerId, String externalRef, String fulfillmentRef, List<ReleaseTarget> targets);

    /** Releases HELD reservations; lifecycle3 also releases accepted but unissued quantities idempotently. */
    void release(String ownerId, String externalRef);

    /**
     * 按调用方的单号释放 —— <b>不需要 ownerId</b>。
     *
     * <p>交易域手里只有订单号：它不认识业主，也不该认识。
     * 订单号在平台内全局唯一，据它反查预留是确定的一条。
     * 找不到就什么都不做（幂等：超时任务与用户取消会撞车）。
     */
    void releaseByRef(String externalRef);

    /** Cancel even before reserve arrives; the durable tombstone prevents a late reserve from holding stock. */
    void cancelByRef(String ownerId, String externalRef);

    /** 按调用方的单号确认。理由同 {@link #releaseByRef}。 */
    String commitByRef(String externalRef, String operator);

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

    /**
     * <b>还剩多少笔到期预留没回收</b>（`HELD` 且已过 `expires_at`）。
     *
     * <p>回收是分批的（一轮 200 条，防长事务），所以「本轮回收了 N 条」答不出
     * 「还积着多少」。而**积压持续不降才是「回收出问题」的信号** ——
     * 那批货被永久占着，商家看到的是「有货卖不出去」，
     * 而任何地方都不会报错。
     *
     * <p>只用来报数，不参与回收 —— 它是给人看的，不是给流程用的。
     */
    int countOverdue();

    record Line(String itemId, String locationId, int qty, String fulfillmentRef) {
        public Line(String itemId, String locationId, int qty) { this(itemId, locationId, qty, null); }
    }
}
