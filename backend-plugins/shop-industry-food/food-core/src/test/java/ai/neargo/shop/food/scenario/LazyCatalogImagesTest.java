package ai.neargo.shop.food.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.food.port.LazyGoodsCatalogQueryPortImpl;
import ai.neargo.shop.spi.product.CatalogImageContracts.Expectation;
import ai.neargo.shop.spi.product.CatalogImageContracts.Facts;
import ai.neargo.shop.spi.product.CatalogImageContracts.Query;
import ai.neargo.shop.spi.product.GoodsCatalogQueryPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

class LazyCatalogImagesTest {
    @Test void newOriginalCapabilityIsForwardedAfterLazyOwnerRegistration() {
        var beans = new DefaultListableBeanFactory();
        var lazy = new LazyGoodsCatalogQueryPortImpl(beans.getBeanProvider(GoodsCatalogQueryPort.class));
        var owner = mock(GoodsCatalogQueryPort.class);
        var q = new Query("E", "S", List.of(new Expectation("G", "NONE", null)));
        var result = new Facts(1, "TEST_ONLY", q, List.of(), null, null, null);
        when(owner.resolveCatalogImages(q)).thenReturn(result);
        beans.registerSingleton("originalProductOwner", owner);
        assertThat(lazy.resolveCatalogImages(q)).isSameAs(result);
        verify(owner).resolveCatalogImages(q);
    }

    @Test void originalOutagePropagatesWithoutFallbackOrEmptySuccess() {
        var beans = new DefaultListableBeanFactory();
        var owner = mock(GoodsCatalogQueryPort.class);
        beans.registerSingleton("owner", owner);
        var lazy = new LazyGoodsCatalogQueryPortImpl(beans.getBeanProvider(GoodsCatalogQueryPort.class));
        var outage = new IllegalStateException("original unavailable");
        when(owner.resolveCatalogImages(null)).thenThrow(outage);
        assertThatThrownBy(() -> lazy.resolveCatalogImages(null)).isSameAs(outage);
    }
}
