package ai.neargo.shop.invbridge;

import ai.neargo.shop.invbridge.port.DualWriteStockPort;
import ai.neargo.shop.product.port.StockPortImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 双写模式下，加购库存校验必须照平台算 —— <b>生产跑的正是 DUAL</b>。
 *
 * <p>{@code StockPort#sellable} 有个默认实现返回 {@code Integer.MAX_VALUE}（「算不出来、不拦」）。
 * {@link DualWriteStockPort} 忘了覆盖它的话，会静默落到默认值 ——
 * 加购校验在线上整个失效，而本地测试跑的是 PLATFORM 模式，全绿。
 *
 * <p>纯单测、不起 Spring：为了验一个转发去单独起一套 DUAL 上下文，会挤掉测试上下文缓存
 * 重建、H2 种子撞主键（实测踩过）。
 */
class DualWriteStockPortSellableTest {

    @Test
    @DisplayName("★★★ DUAL 照平台的数返回，不是默认的「不限」")
    void delegatesToPlatform() {
        StockPortImpl platform = mock(StockPortImpl.class);
        when(platform.sellable("SKU-X")).thenReturn(4);

        DualWriteStockPort dual = new DualWriteStockPort(platform,
                mock(ai.neargo.shop.event.OutboxEventBus.class),
                mock(ai.neargo.shop.spi.product.InvManagedPort.class));

        assertThat(dual.sellable("SKU-X"))
                .as("落到默认实现的话是 Integer.MAX_VALUE —— 生产上加购永远不拦")
                .isEqualTo(4);
    }
}
