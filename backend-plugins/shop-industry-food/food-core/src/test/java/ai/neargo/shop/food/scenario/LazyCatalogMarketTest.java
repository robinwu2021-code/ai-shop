package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.port.LazyGoodsCatalogQueryPortImpl;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.GoodsQuery;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuFacts;
import ai.neargo.shop.spi.product.CatalogMarketContracts.SkuQuery;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class LazyCatalogMarketTest {
    @Test void bothNewCapabilitiesResolveLazilyAndPreserveExactQueriesAndFacts() {
        var beans = new DefaultListableBeanFactory();
        var lazy = new LazyGoodsCatalogQueryPortImpl(beans.getBeanProvider(GoodsCatalogQueryPort.class));
        var owner = mock(GoodsCatalogQueryPort.class);
        var goods = new GoodsQuery("E", "S", "G", "AE");
        var sku = new SkuQuery("E", "S", "SKU", null);
        var gf = new GoodsFacts(1, "TEST", goods, null, null, null, null, null);
        var sf = new SkuFacts(1, "TEST", sku, null, null, null, null, null, null);
        when(owner.getCatalogGoods(goods)).thenReturn(gf);
        when(owner.getCatalogSku(sku)).thenReturn(sf);
        beans.registerSingleton("owner", owner);
        assertThat(lazy.getCatalogGoods(goods)).isSameAs(gf);
        assertThat(lazy.getCatalogSku(sku)).isSameAs(sf);
        verify(owner).getCatalogGoods(goods);
        verify(owner).getCatalogSku(sku);
    }

    @Test void originalFailuresPropagateWithoutFallingBackToLegacyCnMethods() {
        var beans = new DefaultListableBeanFactory();
        var owner = mock(GoodsCatalogQueryPort.class);
        beans.registerSingleton("owner", owner);
        var lazy = new LazyGoodsCatalogQueryPortImpl(beans.getBeanProvider(GoodsCatalogQueryPort.class));
        var failure = new IllegalStateException("original market unavailable");
        when(owner.getCatalogGoods(null)).thenThrow(failure);
        when(owner.getCatalogSku(null)).thenThrow(failure);
        assertThatThrownBy(() -> lazy.getCatalogGoods(null)).isSameAs(failure);
        assertThatThrownBy(() -> lazy.getCatalogSku(null)).isSameAs(failure);
    }
}
