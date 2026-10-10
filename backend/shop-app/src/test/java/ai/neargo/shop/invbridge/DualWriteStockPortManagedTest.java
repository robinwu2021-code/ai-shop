package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.DomainEvent;
import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.invbridge.port.DualWriteStockPort;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.spi.product.InvManagedPort;
import ai.neargo.shop.spi.product.StockPort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双写只镜像记库存的货（TDD-商品纳入进销存开关 §5.3）。<b>平台那一侧一件都不少</b> ——
 * 不记库存的货照样要扣商城库存，只是进销存那本账里没有它。
 *
 * <p>纯单测、不起 Spring：理由同 {@link DualWriteStockPortSellableTest}。
 */
class DualWriteStockPortManagedTest {

    private final StockPortImpl platform = mock(StockPortImpl.class);
    private final OutboxEventBus bus = mock(OutboxEventBus.class);
    private final InvManagedPort managed = mock(InvManagedPort.class);
    private final DualWriteStockPort dual = new DualWriteStockPort(platform, bus, managed);

    @Test
    @DisplayName("★★★ 一单里混着记与不记：平台全锁，镜像只带记库存的那一行")
    void mixedOrderMirrorsOnlyManagedLines() {
        when(managed.managedSkus(anyCollection())).thenReturn(Set.of("SKU-FRUIT"));
        List<StockPort.SkuQty> items = List.of(
                new StockPort.SkuQty("SKU-FRUIT", 2, "S1"), new StockPort.SkuQty("SKU-CLEAN", 1, "S1"));

        dual.lock("L1", items);

        verify(platform).lock("L1", items);
        ArgumentCaptor<DomainEvent> ev = ArgumentCaptor.forClass(DomainEvent.class);
        verify(bus).publish(ev.capture());
        assertThat(ev.getValue().toString()).contains("SKU-FRUIT").doesNotContain("SKU-CLEAN");
    }

    @Test
    @DisplayName("★★ 整单都是不记库存的货：RESERVE 不发；平台照锁")
    void allUnmanagedPublishesNothing() {
        when(managed.managedSkus(anyCollection())).thenReturn(Set.of());
        List<StockPort.SkuQty> items = List.of(new StockPort.SkuQty("SKU-CLEAN", 1, "S1"));

        dual.lock("L2", items);
        dual.restore("R2", items);

        verify(platform).lock("L2", items);
        verify(platform).restore("R2", items);
        verify(bus, never()).publish(any());
    }

    @Test
    @DisplayName("★★ 改库存：不记库存的货只改商城，不往进销存发 ADJUST")
    void setOnHandSkipsUnmanaged() {
        when(managed.managedSkus(anyCollection())).thenReturn(Set.of());

        dual.setOnHand("SKU-CLEAN", "S1", 99, "OTHER");

        verify(platform).setOnHand("SKU-CLEAN", "S1", 99, "OTHER");
        verify(bus, never()).publish(any());
    }

    @Test
    @DisplayName("★ COMMIT / RELEASE 按 ref 走，不查记不记（消费侧找不到预留就当已处理）")
    void settleByRefIsUnfiltered() {
        dual.confirm("L3");
        dual.release("L4");
        verify(bus, org.mockito.Mockito.times(2)).publish(any());
    }
}
