package ai.neargo.shop.invbridge;

import ai.neargo.shop.inventory.service.InventoryAclService;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class InventorySharedSourceTest {
    final StockSyncService sales = mock(StockSyncService.class);
    final InventoryAclService acl = mock(InventoryAclService.class);
    final MerchantQueryPort merchants = mock(MerchantQueryPort.class);
    final InventoryWritebackService writeback = new InventoryWritebackService(sales,
            mock(InvManagedService.class), acl, merchants, mock(InventoryDeliveryGuard.class),
            mock(StockReconcileService.class));
    @Test void sharedWarehouseCannotBeCopiedIntoIndependentStoreQuotas() {
        when(merchants.storeNos("E")).thenReturn(List.of("A", "B"));
        when(acl.stockLocationOf("E", "A")).thenReturn("WAREHOUSE");
        when(acl.stockLocationOf("E", "B")).thenReturn("WAREHOUSE");
        assertThatThrownBy(() -> writeback.requireExclusiveSource("E", "A"))
                .hasMessageContaining("STOCK_SHARED_SOURCE");
        verifyNoInteractions(sales);
    }
    @Test void independentLocationsRemainSupported() {
        when(merchants.storeNos("E")).thenReturn(List.of("A", "B"));
        when(acl.stockLocationOf("E", "A")).thenReturn("LA");
        when(acl.stockLocationOf("E", "B")).thenReturn("LB");
        assertThatCode(() -> writeback.requireExclusiveSource("E", "A")).doesNotThrowAnyException();
    }
}
