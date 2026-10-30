package ai.neargo.shop.inventory.service.impl;

import ai.neargo.inventory.api.InventoryError;
import ai.neargo.inventory.api.InventoryException;
import ai.neargo.inventory.api.projection.StockProjectionContracts;
import ai.neargo.inventory.client.CircuitBreaker;
import ai.neargo.inventory.client.InventoryProjectionApi;
import ai.neargo.inventory.client.InventoryReservationClient;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.spi.food.FoodStockFingerprint;
import ai.neargo.shop.spi.inventory.InventoryFoodStockProjectionPort.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class FoodStockProjectionRemoteTest {
    @Test void remoteAdapterPreservesOriginalHashKeysDecimalAndMissingReceiptSemantics() {
        var json=JsonMapper.builder().build();var api=mock(InventoryProjectionApi.class);
        var client=InventoryReservationClient.of(null,null,null,api,new CircuitBreaker(3,Duration.ofSeconds(5),Clock.systemUTC()));
        var beans=new DefaultListableBeanFactory();beans.registerSingleton("inventoryClient",client);
        var service=new FoodStockProjectionServiceImpl(beans.getBeanProvider(InventoryReservationClient.class),json);
        var s=new Scope("E","S");var p=new Snapshot("I","KG","TRACKED","ACTIVE",new BigDecimal("10.000000"),
                new BigDecimal("2.000000"),1,LocalDateTime.of(2026,10,30,8,0),null,null,null);
        String h=FoodStockFingerprint.sha256(json.writeValueAsString(new Object[]{s,1L,p}));
        var independent=json.convertValue(p,StockProjectionContracts.Snapshot.class);
        var scope=new StockProjectionContracts.Scope("E","S");
        assertThat(json.writeValueAsString(new Object[]{scope,1L,independent}))
                .isEqualTo(json.writeValueAsString(new Object[]{s,1L,p}));
        var request=new StockProjectionContracts.Apply(scope,1,"ORIGINAL",h,independent);
        when(api.apply("E","FOOD_SYNC","ORIGINAL",request)).thenReturn(new StockProjectionContracts.Receipt("R","APPLIED",1,1L));
        assertThat(service.apply(s,1,"ORIGINAL",h,p)).isEqualTo(new Receipt("R","APPLIED",1,1L));
        verify(api).apply("E","FOOD_SYNC","ORIGINAL",request);
        when(api.receipt("E","FOOD_SYNC",new StockProjectionContracts.Query(scope,"MISSING",h)))
                .thenReturn(new StockProjectionContracts.ReceiptLookup(null));
        assertThat(service.queryReceipt(s,"MISSING",h)).isNull();
        when(api.apply("E","FOOD_SYNC","ORIGINAL",request)).thenThrow(InventoryException.of(InventoryError.UNAVAILABLE));
        assertThatThrownBy(()->service.apply(s,1,"ORIGINAL",h,p)).isInstanceOf(BizException.class);
    }
}
