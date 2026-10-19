package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutbox;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.inventory.config.ConditionalOnInventory;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.spi.product.StockPort.RestoreResult;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Platform restore and one durable mirror instruction commit together; inventory completion is separate. */
@Component
@ConditionalOnInventory
@ConditionalOnProperty(prefix = "shop.inventory", name = "stock-authority", havingValue = "DUAL")
public class OriginalMirrorRestore {
    private final StockPortImpl platform;
    private final OriginalInventoryRestoreAdapter inventory;
    private final SysOutboxMapper events;
    private final OutboxEventBus bus;
    private final ObjectMapper json;

    public OriginalMirrorRestore(StockPortImpl platform, OriginalInventoryRestoreAdapter inventory,
                                 SysOutboxMapper events, OutboxEventBus bus, ObjectMapper json) {
        this.platform = platform;
        this.inventory = inventory;
        this.events = events;
        this.bus = bus;
        this.json = json;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public RestoreResult restore(String purpose, String originalRef, List<SkuQty> items) {
        List<SkuQty> mirrored = selectOriginal(originalRef, items);
        platform.restoreOriginal(purpose, originalRef, items);
        if (mirrored.isEmpty()) {
            return RestoreResult.COMPLETED;
        }
        enqueue(purpose, originalRef, mirrored);
        try {
            return inventory.completed(purpose, originalRef, mirrored)
                    ? RestoreResult.COMPLETED : RestoreResult.PENDING;
        } catch (IllegalStateException pending) {
            if (!"STOCK_ORIGINAL_POSTING_UNCONFIRMED".equals(pending.getMessage())) {
                throw pending;
            }
            return RestoreResult.PENDING;
        }
    }

    private List<SkuQty> selectOriginal(String originalRef, List<SkuQty> items) {
        List<SysOutbox> original = events.selectList(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getAggregateId, originalRef).eq(SysOutbox::getEventType, InvMirrorEvent.RESERVE)
                .orderByAsc(SysOutbox::getId).last("FOR UPDATE"));
        if (original.isEmpty()) {
            throw new IllegalStateException("STOCK_SOURCE_MIRROR_MISSING");
        }
        Map<Key, Integer> managed = quantities(readItems(original.getFirst()));
        for (SysOutbox event : original) {
            if (!managed.equals(quantities(readItems(event)))) {
                throw conflict();
            }
        }
        List<SkuQty> mirrored = new ArrayList<>();
        for (var requested : quantities(items).entrySet()) {
            Integer bound = managed.get(requested.getKey());
            if (bound != null) {
                if (requested.getValue() > bound) {
                    throw conflict();
                }
                mirrored.add(new SkuQty(requested.getKey().sku(), requested.getValue(),
                        requested.getKey().store()));
            }
        }
        return mirrored;
    }

    private void enqueue(String purpose, String originalRef, List<SkuQty> items) {
        List<SysOutbox> old = events.selectList(Wrappers.<SysOutbox>lambdaQuery()
                .eq(SysOutbox::getAggregateId, purpose)
                .eq(SysOutbox::getEventType, InvMirrorEvent.RESTORE_ORIGINAL).last("FOR UPDATE"));
        if (old.isEmpty()) {
            bus.publish(new RestoreEvent(purpose, originalRef, items));
            return;
        }
        for (SysOutbox event : old) {
            var payload = json.readTree(event.getPayload());
            if (!Objects.equals(payload.path("originalRef").asString(), originalRef)
                    || !Objects.equals(payload.path("ref").asString(), purpose)
                    || !quantities(readItems(event)).equals(quantities(items))) {
                throw conflict();
            }
        }
    }

    private List<SkuQty> readItems(SysOutbox event) {
        var payload = json.readTree(event.getPayload());
        if (!payload.path("items").isArray()) {
            throw conflict();
        }
        return json.convertValue(payload.path("items"), new TypeReference<>() { });
    }

    private Map<Key, Integer> quantities(List<SkuQty> items) {
        Map<Key, Integer> values = new HashMap<>();
        for (SkuQty item : items) {
            if (item.skuNo() == null || item.qty() <= 0) {
                throw conflict();
            }
            values.merge(new Key(item.skuNo(), item.storeNo()), item.qty(), Math::addExact);
        }
        return values;
    }

    private IllegalStateException conflict() {
        return new IllegalStateException("STOCK_SOURCE_MIRROR_CONFLICT");
    }

    private record Key(String sku, String store) { }

    private record RestoreEvent(String ref, String originalRef, List<SkuQty> items) implements DomainEvent {
        @Override public String aggregateType() { return "INVENTORY"; }
        @Override public String aggregateId() { return ref; }
        @Override public String eventType() { return InvMirrorEvent.RESTORE_ORIGINAL; }
    }
}
