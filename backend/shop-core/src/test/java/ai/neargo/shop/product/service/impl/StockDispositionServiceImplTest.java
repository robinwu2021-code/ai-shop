package ai.neargo.shop.product.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ai.neargo.shop.product.entity.PrdSku;
import ai.neargo.shop.product.mapper.ProductMappers.SkuMapper;
import ai.neargo.shop.product.mapper.ProductMappers.StockLockMapper;
import ai.neargo.shop.product.port.StockPortImpl;
import ai.neargo.shop.spi.product.StockDispositionContract;
import ai.neargo.shop.spi.product.StockDispositionPort.Need;
import ai.neargo.shop.spi.product.StockDispositionPort.Original;
import ai.neargo.shop.spi.product.StockDispositionPort.Reservation;
import ai.neargo.shop.spi.product.StockPort.SkuQty;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class StockDispositionServiceImplTest {
    @Test
    void sharedSkuAcrossMarketsStillUsesOneStockReservation() {
        var skus = mock(SkuMapper.class);
        var locks = mock(StockLockMapper.class);
        var stock = mock(StockPortImpl.class);
        var service = new StockDispositionServiceImpl(locks, skus, stock, JsonMapper.builder().build());
        var needs = List.of(new Need("SKU1", "STORE1", "LINE1", 1));
        var original = new Original("MAIN", "ENTITY1", "ORDER1", "PLATFORM", 1,
                StockDispositionContract.manifestHash(needs), needs);
        when(skus.selectList(any())).thenReturn(List.of(sku("CN"), sku("TW")));
        when(locks.lockOriginal("ORDER1")).thenReturn(List.of());

        var observation = service.reserveOriginal(
                new Reservation(original, StockDispositionContract.reservationOperationNo(original)));

        verify(stock).lock("ORDER1", List.of(new SkuQty("SKU1", 1, "STORE1", "LINE1", 1)));
        assertThat(observation.outcome()).isEqualTo("UNKNOWN");
    }

    private static PrdSku sku(String market) {
        var sku = new PrdSku();
        sku.setSkuNo("SKU1");
        sku.setEntityNo("ENTITY1");
        sku.setMarket(market);
        return sku;
    }
}
