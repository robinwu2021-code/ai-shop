package ai.neargo.shop.invbridge.port;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.spi.product.StockPort;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 双写：<b>平台仍是真相源，进销存跟着记一笔</b>。
 *
 * <h2>为什么需要它</h2>
 * 切换计划原本是两档：{@code PLATFORM}（只写平台）→ {@code INVENTORY}（只写进销存）。
 * 中间没有任何东西让两本账保持一致 —— 搬运把存量拷过去之后，
 * <b>进销存那本账就冻结在搬运那一刻</b>，而平台还在照常扣。
 *
 * <p>于是对差比的是「一本一直在动的账」和「一本停着的账」，差异随每一笔订单增长。
 * 而 G3 的判据是「对差连续 N 天为零才准切」——
 * <b>在有交易的平台上，那道闸门永远不可能绿</b>。今天看着没事，只因为线上 0 订单。
 *
 * <p>这一档补上中间那段：平台照旧权威，进销存同步跟запись，
 * 对差从「比两个不相干的数」变成「验双写有没有漏」—— 那才是它该问的问题。
 *
 * <h2>为什么走 outbox 而不是直接调</h2>
 * 进销存是<b>另一个数据源</b>，跨库事务要 XA。而这件事本来就不需要强一致：
 * 平台扣成功了就该成交，进销存晚几秒记上没有任何影响。
 *
 * <p>直接在这里调进销存的后果更坏：<b>它一慢，下单就跟着慢；它一挂，下单就跟着挂</b> ——
 * 一个还没成为真相源的旁路账本，不该有能力拖垮交易。
 *
 * <p>所以走 {@code sys_outbox}：事件与业务数据同一个事务同生共死，
 * 投递交给 {@code OutboxDispatcher}（带重试、失败留队）。
 * <b>平台侧回滚，事件跟着回滚</b> —— 不会出现「单没成而账记了」。
 *
 * <h2>顺序不能反</h2>
 * 先让平台那一侧真正执行完，再发事件。反过来的话，
 * {@code lock} 抛 {@code PartialLockException}（库存不足）时事件已经发出去了 ——
 * 虽然同事务会回滚，但把「还没确定成不成」的事写进队列是一种自找的时序假设。
 */
@Component
@Primary
@ConditionalOnProperty(prefix = "shop.inventory", name = "stock-authority", havingValue = "DUAL")
public class DualWriteStockPort implements StockPort {

    /** 聚合类型：与 {@code PlatformInventoryEventSink} 用同一个，平台侧按它分流 */
    private static final String AGGREGATE = "INVENTORY";

    private final StockPortImpl platform;
    private final OutboxEventBus bus;

    public DualWriteStockPort(StockPortImpl platform, OutboxEventBus bus) {
        this.platform = platform;
        this.bus = bus;
    }

    @Override
    public List<String> lock(String lockNo, List<StockPort.SkuQty> items) {
        // 平台先做。**不足会抛，事件根本发不出去** —— 这正是要的
        List<String> failed = platform.lock(lockNo, items);
        mirror("MIRROR_RESERVE", lockNo, items);
        return failed;
    }

    @Override
    public void release(String lockNo) {
        platform.release(lockNo);
        mirror("MIRROR_RELEASE", lockNo, List.of());
    }

    @Override
    public void confirm(String lockNo) {
        platform.confirm(lockNo);
        mirror("MIRROR_COMMIT", lockNo, List.of());
    }

    @Override
    public void restore(String restoreNo, List<StockPort.SkuQty> items) {
        platform.restore(restoreNo, items);
        mirror("MIRROR_RESTORE", restoreNo, items);
    }

    /**
     * 把这一笔记进出站表。
     *
     * <p><b>不 try/catch</b>：写 outbox 是同一个库、同一个事务里的一次 insert，
     * 它失败说明这个事务本来就该回滚。吞掉它等于「单成了而账没记」——
     * 而那正是双写要防的事。
     */
    private void mirror(String type, String ref, List<StockPort.SkuQty> items) {
        bus.publish(new MirrorEvent(type, ref, items));
    }

    /**
     * @param ref   平台侧的自然键（锁号 / 售后单号）。进销存那边靠它幂等 ——
     *              至少一次投递意味着同一笔可能来两遍
     * @param items 明细。{@code release} / {@code commit} 不需要（进销存按 ref 找预留）
     */
    private record MirrorEvent(String type, String ref, List<StockPort.SkuQty> items)
            implements DomainEvent {

        @Override
        public String aggregateType() {
            return AGGREGATE;
        }

        @Override
        public String aggregateId() {
            return ref;
        }

        @Override
        public String eventType() {
            // 带 INV_ 前缀：平台的 eventType 是全局的，不带前缀会与别的域撞
            return "INV_" + type;
        }
    }
}
