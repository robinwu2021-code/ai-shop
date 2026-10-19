package ai.neargo.shop.invbridge;

import ai.neargo.shop.event.OutboxEventBus;
import ai.neargo.shop.event.SysOutboxMapper;
import ai.neargo.shop.invbridge.port.DualWriteStockPort;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.spi.product.InvManagedPort;
import ai.neargo.shop.spi.product.StockPort;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OriginalRestoreWiringTest {
    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withPropertyValues("shop.inventory.stock-authority=DUAL")
                .withUserConfiguration(OriginalMirrorRestore.class, DualWriteStockPort.class)
                .withBean(StockPortImpl.class, () -> mock(StockPortImpl.class))
                .withBean(ai.neargo.shop.product.service.OriginalStockRestoreService.class,
                        () -> mock(ai.neargo.shop.product.service.OriginalStockRestoreService.class))
                .withBean(OutboxEventBus.class, () -> mock(OutboxEventBus.class))
                .withBean(SysOutboxMapper.class, () -> mock(SysOutboxMapper.class))
                .withBean(InvManagedPort.class, () -> mock(InvManagedPort.class))
                .withBean(ObjectMapper.class, ObjectMapper::new);
    }

    @Test
    void disabledInventoryUsesPlatformStockEvenWithAStoredDualSetting() {
        runner().withPropertyValues("shop.inventory.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DualWriteStockPort.class);
            assertThat(context).doesNotHaveBean(OriginalMirrorRestore.class);
            assertThat(context.getBean(StockPort.class)).isSameAs(context.getBean(StockPortImpl.class));
        });
    }

    @Test
    void enabledInventoryWiresDualRestorationWithTheOriginalAdapter() {
        runner().withPropertyValues("shop.inventory.enabled=true")
                .withBean(OriginalInventoryRestoreAdapter.class, () -> mock(OriginalInventoryRestoreAdapter.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(StockPort.class)).isInstanceOf(DualWriteStockPort.class);
                    assertThat(context).hasSingleBean(OriginalMirrorRestore.class);
                });
    }
}
