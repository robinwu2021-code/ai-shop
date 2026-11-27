package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import ai.neargo.shop.common.config.ModuleDataSources;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.inventory.domain.service.InventoryEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 进销存事件的平台出口：内嵌部署时库存过账提交后直接交到这里（R10.3，不再进 {@code inv_outbox}；存量积压仍由
 * {@code InvOutboxDispatchJob} 转来），按原事件号发出 ——
 * 类型在中间件名单里直发中间件，否则写进 {@code sys_outbox}（{@link OutboxEventBus#forward}，清单 §〇 C3）。
 *
 * <h2>为什么要有它</h2>
 * {@link InventoryEventSink} 是本域留给装配方的 SPI，而<b>此前一个实现都没有</b>。
 * 按 {@code InvOutboxDispatchJob} 的设计，没有 sink 时它<b>不把行标成已发</b> ——
 * 事件会在 {@code inv_outbox} 里看得见地堆着。那是有意的（「没有订阅者就当发过了」
 * 是这类表最常见的坏法：等有人来接的时候，前面几个月的事件已经没了），
 * 但堆着终究不是去处。
 *
 * <h2>为什么是「转发到平台 outbox」而不是直接投递</h2>
 * 平台已经有一条投递链（{@code sys_outbox} + {@code OutboxDispatcher} +
 * {@code OutboxConsumer}），带重试、带幂等约定。进销存自己再接一套 MQ 的话，
 * 同一个系统里就有两条投递语义，而它们的重试与去重规则迟早会不一样。
 *
 * <p><b>它在 shop-app 而不在 shop-inventory</b>：本域不认识平台的 outbox ——
 * 独立交付时那边的实现会是 webhook 或 MQ，与这里换一个类就行。
 * 这正是 SPI 存在的意义。
 */
@Component
@ConditionalOnProperty(prefix = "shop.inventory", name = "enabled", havingValue = "true")
public class PlatformInventoryEventSink implements InventoryEventSink {

    private static final Logger log = LoggerFactory.getLogger(PlatformInventoryEventSink.class);

    /** 聚合类型：平台侧按它分流，进销存的事件统一挂这一个 */
    private static final String AGGREGATE = "INVENTORY";

    /** 库存键与平台键都以 {@code EVT} 开头、格式相同，加命名空间免得在平台侧撞号 */
    static final String EVENT_NO_PREFIX = "INV-";

    private final OutboxEventBus bus;

    public PlatformInventoryEventSink(OutboxEventBus bus) {
        this.bus = bus;
    }

    /**
     * {@inheritDoc}
     *
     * <p><b>返回 true 只表示「中间件已确认或已进了平台 outbox」，不表示送到了谁手上。</b>
     * 那正是 outbox 的语义：写库即算交付，之后的投递与重试是投递器的事。
     * 在这里等真正的下游确认，等于把两条链的事务绑在一起 ——
     * 而下游是谁、有没有人，本来就不该由进销存关心。
     */
    private UnifiedOutboxFacade unified;

    @Autowired(required = false)
    public void setUnified(UnifiedOutboxFacade unified) { this.unified = unified; }

    /** 基础能力库的独立事务（发件箱所在，ADR-067 决策 10）；单测里不装 */
    private TransactionTemplate commonTx;

    @Autowired(required = false)
    public void setCommonTx(@Qualifier(ModuleDataSources.COMMON_TX_MANAGER) PlatformTransactionManager tx) {
        TransactionTemplate t = new TransactionTemplate(tx);
        t.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.commonTx = t;
    }

    /**
     * 内嵌部署由本类在库存提交后直接接手（R10.3）：过账不再写 {@code inv_outbox}。
     * 统一模式另有中转（{@code acceptInventory}），不接手。
     */
    @Override
    public boolean takesOverAfterCommit() {
        return unified == null || !unified.enabled("PLATFORM");
    }

    @Override
    public boolean deliver(String eventNo, String ownerId, String eventType, String payload) {
        return deliver(eventNo, ownerId, eventType, payload, Instant.EPOCH);
    }

    @Override
    public boolean deliver(String eventNo, String ownerId, String eventType, String payload, Instant occurredAt) {
        if (unified != null && unified.enabled("PLATFORM")) {
            unified.acceptInventory(eventNo, ownerId, eventType, payload, occurredAt);
            return true;
        }
        try {
            // 沿用库存的事件号：名单内类型直发中间件，否则落平台发件箱时按号去重（重投不再多出一条）
            Runnable forward = () -> bus.forward(EVENT_NO_PREFIX + eventNo, new InventoryDomainEvent(ownerId, eventType, payload));
            if (commonTx == null) {
                forward.run();
            } else {
                // 提交后接手时同线程上还绑着库存库已提交的事务；另起基础能力库的事务，发件箱行与投递泵挂在它上面
                commonTx.executeWithoutResult(s -> forward.run());
            }
            return true;
        } catch (RuntimeException e) {
            // 返回 false 让它留在 inv_outbox 里重投 —— 别把异常吞掉当成功
            log.warn("进销存事件转平台 outbox 失败，留在队列里重投：eventNo={} type={}",
                    eventNo, eventType, e);
            return false;
        }
    }

    /**
     * @param ownerId 进销存的业主号。**不翻译成 entityNo** —— 翻译要查 ACL，
     *                而这里在事务里，多一次跨库查询换不来什么：
     *                消费方需要 entityNo 时自己按 owner 反查，那是它的事
     */
    private record InventoryDomainEvent(String ownerId, String type, String payload)
            implements DomainEvent {

        @Override
        public String aggregateType() {
            return AGGREGATE;
        }

        @Override
        public String aggregateId() {
            return ownerId;
        }

        @Override
        public String eventType() {
            // **带上域前缀**：平台侧的 eventType 是全局的，
            // 进销存的 POSTED 与订单的 POSTED 撞在一起，消费方分不出是谁的
            return "INV_" + type;
        }
    }
}
