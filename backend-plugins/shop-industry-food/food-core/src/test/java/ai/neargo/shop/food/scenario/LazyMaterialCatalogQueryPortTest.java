package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.port.LazyMaterialCatalogQueryPortImpl;
import ai.neargo.shop.spi.inventory.MaterialCatalogQueryPort;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

class LazyMaterialCatalogQueryPortTest {
    @Test
    void resolvesBothContractsOnlyAfterOwnerIsRegisteredAndPreservesExactScopes() {
        var beans = new DefaultListableBeanFactory();
        var lazy =
                new LazyMaterialCatalogQueryPortImpl(
                        beans.getBeanProvider(MaterialCatalogQueryPort.class));
        var owner = mock(MaterialCatalogQueryPort.class);
        List<MaterialCatalogQueryPort.MaterialFact> materials = List.of();
        List<MaterialCatalogQueryPort.SkuMaterialFact> skus = List.of();
        when(owner.getMaterials("E", List.of("ITEM"))).thenReturn(materials);
        when(owner.getSkuMaterials("E", "STORE", List.of("SKU"))).thenReturn(skus);
        beans.registerSingleton("originalInventoryOwner", owner);
        assertThat(lazy.getMaterials("E", List.of("ITEM"))).isSameAs(materials);
        assertThat(lazy.getSkuMaterials("E", "STORE", List.of("SKU"))).isSameAs(skus);
        verify(owner).getMaterials("E", List.of("ITEM"));
        verify(owner).getSkuMaterials("E", "STORE", List.of("SKU"));
        verifyNoMoreInteractions(owner);
    }

    @Test
    void preservesOwnerFailureAndDoesNotReplaceItWithUnsupportedOrEmptyFacts() {
        var beans = new DefaultListableBeanFactory();
        var owner = mock(MaterialCatalogQueryPort.class);
        beans.registerSingleton("originalInventoryOwner", owner);
        var lazy =
                new LazyMaterialCatalogQueryPortImpl(
                        beans.getBeanProvider(MaterialCatalogQueryPort.class));
        var outage = new IllegalStateException("original inventory unavailable");
        when(owner.getSkuMaterials("E", "S", List.of("SKU")))
                .thenThrow(outage)
                .thenReturn(List.of());
        assertThatThrownBy(() -> lazy.getSkuMaterials("E", "S", List.of("SKU"))).isSameAs(outage);
        assertThat(lazy.getSkuMaterials("E", "S", List.of("SKU"))).isEmpty();
        verify(owner, times(2)).getSkuMaterials("E", "S", List.of("SKU"));
    }
}
