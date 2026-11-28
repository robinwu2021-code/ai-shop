package ai.neargo.shop.invbridge;

import ai.neargo.inventory.domain.service.InventoryAclService;
import ai.neargo.shop.spi.product.InventoryProductPort;
import ai.neargo.shop.spi.product.InventoryProductPort.GoodsFact;
import ai.neargo.shop.spi.product.InventoryProductPort.SkuFact;
import ai.neargo.shop.product.service.InvManagedService;
import ai.neargo.shop.product.service.StockSyncService;
import ai.neargo.shop.spi.user.MerchantQueryPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class InventoryOverviewQueryTest {
    final InventoryProductPort skus = mock(InventoryProductPort.class);
    final InvManagedService managed = mock(InvManagedService.class);
    final InventoryAclService acl = mock(InventoryAclService.class);
    final StockSyncService sales = mock(StockSyncService.class);
    final MerchantQueryPort merchants = mock(MerchantQueryPort.class);

    InventoryOverviewQuery query() {
        when(merchants.storeNos("E")).thenReturn(List.of("S"));
        SkuFact sku = sku("K", "G");
        when(skus.skus(org.mockito.ArgumentMatchers.eq("E"), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(sku));
        GoodsFact goods = new GoodsFact(1L, "G", "E", "S", null, null, null, true, null);
        when(skus.goodsOf("E", "G")).thenReturn(goods);
        when(skus.isManaged("E", "G")).thenReturn(true);
        when(skus.goodsOfBatch(org.mockito.ArgumentMatchers.eq("E"), org.mockito.ArgumentMatchers.anyCollection())).thenReturn(java.util.Map.of("G", goods));
        when(managed.managedSkus(any())).thenReturn(java.util.Set.of("K"));
        when(sales.sellablesOf("S", java.util.Set.of("K"))).thenReturn(java.util.Map.of("K",
                new StockSyncService.Sellable("STORE", 10, 2)));
        when(sales.sellableOf("S", "K")).thenReturn(new StockSyncService.Sellable("STORE", 10, 2));
        when(sales.ruleOf("S", "G", null)).thenReturn(new StockSyncService.Rule("STORE", "S", "CAP", 10));
        return new InventoryOverviewQuery(skus, managed, acl, sales, merchants);
    }

    static SkuFact sku(String skuNo, String goodsNo) {
        return new SkuFact(1L, skuNo, goodsNo, "E", null, "件", 0);
    }
    @Test void separatesBookAvailableFromSupplyAfterSafety() {
        var query = query();
        when(acl.stockAt("E", "S", "K")).thenReturn(new InventoryAclService.StockAt(20, 5, 3));
        var result = query.get("E", "S", "K");
        assertThat(result.inventoryState()).isEqualTo("MAPPED");
        assertThat(result.available()).isEqualTo(15);
        assertThat(result.supplyAvailable()).isEqualTo(12);
        assertThat(result.onlineSellable()).isEqualTo(10);
        assertThat(result.accountingMode()).isEqualTo("PAYMENT_DEDUCT");
    }
    @Test void missingItemIsNotZeroInventory() {
        var result = query().get("E", "S", "K");
        assertThat(result.inventoryState()).isEqualTo("INITIALIZING");
        assertThat(result.onHand()).isNull();
    }
    @Test void unmanagedDoesNotReadPhysicalInventory() {
        var query = query();
        when(skus.isManaged(any(), any())).thenReturn(false);
        assertThat(query.get("E", "S", "K").inventoryState()).isEqualTo("UNMANAGED");
        verifyNoInteractions(acl);
    }
    @Test void batchDeduplicatesAndRejectsUnboundedRequests() {
        var query = query();
        assertThat(query.batch("E", "S", List.of("K", "K"))).hasSize(1);
        assertThat(query.batch("E", "S", List.of())).isEmpty();
        assertThatThrownBy(() -> query.batch("E", "S", java.util.Collections.nCopies(501, "K")))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> query.batch("E", "OTHER", List.of())).isInstanceOf(RuntimeException.class);
    }

    @Test void batchIgnoresStaleMappingWithoutPerSkuQueries() {
        var query = query();
        when(skus.skus(org.mockito.ArgumentMatchers.eq("E"), org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(List.of(sku("K", "G"), sku("DELETED", "OLD")));
        clearInvocations(skus);
        assertThat(query.batch("E", "S", List.of("K", "DELETED"))).hasSize(1);
        verify(skus, never()).goodsOf(any(), any());
        verify(acl, never()).stockAt(any(), any(), any());
        verify(sales, never()).sellableOf(any(), any());
        verify(merchants).storeNos("E");
    }

    @Test void rejectsForeignStoreAndForeignSku() {
        var query = query();
        assertThatThrownBy(() -> query.get("E", "OTHER", "K")).isInstanceOf(RuntimeException.class);
        when(skus.goodsOf("E", "G")).thenReturn(null);
        assertThatThrownBy(() -> query.get("E", "S", "K")).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(acl);
    }
}
