package ai.neargo.shop.merchant.api.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.merchant.entity.MchStore;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.paysync.StoreSyncPublisher;
import ai.neargo.shop.merchant.service.StoreAdminService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 运营代改店名与 slug：用门店所属主体调用商户端同一服务，并推一次门店同步。 */
class OpsStoreBasicsControllerTest {
    final MchStoreMapper stores = mock(MchStoreMapper.class);
    final StoreAdminService admin = mock(StoreAdminService.class);
    final AuditLogPort audit = mock(AuditLogPort.class);
    final StoreSyncPublisher sync = mock(StoreSyncPublisher.class);
    @SuppressWarnings("unchecked") final ObjectProvider<StoreSyncPublisher> publisher = mock(ObjectProvider.class);
    final OpsStoreBasicsController controller = new OpsStoreBasicsController(stores, admin, audit, publisher);

    OpsStoreBasicsControllerTest() {
        doAnswer(i -> { ((java.util.function.Consumer<StoreSyncPublisher>) i.getArgument(0)).accept(sync); return null; })
                .when(publisher).ifAvailable(any());
    }

    @Test void renamesAndSetsSlugUnderOwningEntityThenSyncs() {
        var s = new MchStore(); s.setStoreNo("S1"); s.setEntityNo("E1"); s.setName("ume 早餐店");
        when(stores.selectOne(any())).thenReturn(s);
        controller.update("S1", new OpsStoreBasicsController.BasicsReq("ume早餐店", "ume-breakfast", "迁移店名"));
        verify(admin).rename("E1", "S1", "ume早餐店", null);
        verify(admin).setSlug("E1", "S1", "ume-breakfast");
        verify(sync).publishAll(List.of("S1"));
        verify(audit).record(eq("STORE_BASICS_UPDATE"), eq("S1"), contains("ume-breakfast"));
    }

    @Test void unknownStoreOrEmptyChangeIsRejected() {
        assertThrows(BizException.class, () -> controller.update("X", new OpsStoreBasicsController.BasicsReq("n", null, "r")));
        var s = new MchStore(); s.setStoreNo("S1"); s.setEntityNo("E1");
        when(stores.selectOne(any())).thenReturn(s);
        assertThrows(BizException.class, () -> controller.update("S1", new OpsStoreBasicsController.BasicsReq(" ", null, "r")));
        verifyNoInteractions(admin);
    }
}
