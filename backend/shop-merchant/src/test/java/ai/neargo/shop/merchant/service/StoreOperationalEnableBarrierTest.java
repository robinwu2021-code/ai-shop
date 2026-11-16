package ai.neargo.shop.merchant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.merchant.service.impl.StoreAdminServiceImpl;
import ai.neargo.shop.merchant.service.impl.MerchantGovernServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import ai.neargo.shop.spi.product.StoreShelfPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

class StoreOperationalEnableBarrierTest {
    @Test void merchantEnableAndDisableCannotBypassExplicitOperatingReview() {
        for(var review:List.of("PENDING","REJECTED")) {
            var store=new MchStore();store.setEntityNo("E1");store.setStoreNo("S1");store.setStatus("PENDING_OPEN");store.setOperationalReview(review);
            var mapper=mock(MchStoreMapper.class);when(mapper.selectList(any())).thenReturn(List.of(store));
            var service=mock(StoreAdminServiceImpl.class,CALLS_REAL_METHODS);
            service.setOwnership(mock(StoreOwnershipWritePort.class));
            ReflectionTestUtils.setField(service,"storeMapper",mapper);
            assertThrows(BizException.class,()->service.setStatus("E1","S1",true));
            assertThrows(BizException.class,()->service.setStatus("E1","S1",false));
            verify(mapper,never()).updateById(any(MchStore.class));
        }
    }
    @Test void platformRestorePreservesUnfinishedReviewInsteadOfOpeningStore() {
        for(var review:List.of("PENDING","REJECTED")) {
            var store=new MchStore();store.setEntityNo("E1");store.setStoreNo("S1");store.setStatus("SUSPENDED");store.setOperationalReview(review);
            var mapper=mock(MchStoreMapper.class);when(mapper.selectOne(any())).thenReturn(store);
            var service=mock(MerchantGovernServiceImpl.class,CALLS_REAL_METHODS);
            ReflectionTestUtils.setField(service,"storeProfileMapper",mapper);
            ReflectionTestUtils.setField(service,"merchantMapper",mock(MchEntityMapper.class));
            ObjectProvider<StoreShelfPort> shelf=mock(ObjectProvider.class);when(shelf.getObject()).thenReturn(mock(StoreShelfPort.class));
            ReflectionTestUtils.setField(service,"shelfPort",shelf);
            assertEquals("PENDING_OPEN",service.restoreStore("S1","OP1").status());
            verify(mapper).updateById(argThat((MchStore row)->"PENDING_OPEN".equals(row.getStatus())));
        }
    }
}
