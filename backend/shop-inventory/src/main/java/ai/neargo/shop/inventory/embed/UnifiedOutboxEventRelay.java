package ai.neargo.shop.inventory.embed;

import ai.neargo.inventory.domain.spi.InventoryEventRelay;
import ai.neargo.inventory.domain.support.InvEnums;
import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.outbox.runtime.UnifiedOutboxFacade;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 内嵌时把进销存事件交给商城的统一出站表（开关 {@code shop.outbox.sources.inventory.mode}）。
 *
 * <p>事件形状与改造前**逐字段相同**：聚合类型 {@code INVENTORY}、聚合号 = 业主号、
 * 载荷 {@code ownerId / docNo / docKind} —— 下游消费者按这个形状解析，换了就静默收不到。
 *
 * <p>统一出站表不一定装配（关着的部署里没有这个 Bean），所以每次现取，取不到就当没有接管。
 */
@Component
public class UnifiedOutboxEventRelay implements InventoryEventRelay {

    private static final String SOURCE = "INVENTORY";

    private final ObjectProvider<UnifiedOutboxFacade> unified;

    public UnifiedOutboxEventRelay(ObjectProvider<UnifiedOutboxFacade> unified) {
        this.unified = unified;
    }

    @Override
    public boolean maintenance() {
        UnifiedOutboxFacade facade = unified.getIfAvailable();
        return facade != null && facade.isMaintenance();
    }

    @Override
    public boolean active() {
        UnifiedOutboxFacade facade = unified.getIfAvailable();
        return facade != null && facade.enabled(SOURCE);
    }

    @Override
    public void documentPosted(String ownerId, String docNo, String docKind) {
        unified.getObject().publish(SOURCE, new PostingEvent(ownerId, docNo, docKind));
    }

    @Override
    public void signal() {
        UnifiedOutboxFacade facade = unified.getIfAvailable();
        if (facade != null) {
            facade.signal(SOURCE);
        }
    }

    record PostingEvent(String ownerId, String docNo, String docKind) implements DomainEvent {
        @Override
        public String aggregateType() {
            return SOURCE;
        }

        @Override
        public String aggregateId() {
            return ownerId;
        }

        @Override
        public String eventType() {
            return InvEnums.EventType.DOCUMENT_POSTED;
        }
    }
}
