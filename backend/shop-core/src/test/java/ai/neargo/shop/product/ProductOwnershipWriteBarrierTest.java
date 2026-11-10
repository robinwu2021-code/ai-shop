package ai.neargo.shop.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.product.service.MerchantGoodsService;
import ai.neargo.shop.product.service.impl.MerchantGoodsServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The real service entries must fence before any catalog lookup, draft insert or stock creation. */
class ProductOwnershipWriteBarrierTest {
    @AfterEach void clear() { BizContext.clear(); }

    private MerchantGoodsServiceImpl frozen(StoreOwnershipWritePort gate) {
        var service=mock(MerchantGoodsServiceImpl.class,CALLS_REAL_METHODS);
        service.setOwnershipWrites(gate);
        BizContext.set(new BizContext("M",Set.of(),Set.of(),Set.of("S"),"S",true));
        doThrow(BizException.of(ErrorCode.CONFLICT)).when(gate).lockAndCheck("MAIN","M","S");
        return service;
    }

    @Test void newProductDraftCannotWriteAfterClaimFreeze() {
        var gate=mock(StoreOwnershipWritePort.class);var service=frozen(gate);
        var command=mock(MerchantGoodsService.SaveCommand.class);
        assertThrows(BizException.class,()->service.save("M",command));
        verify(gate).lockAndCheck("MAIN","M","S");
        verifyNoInteractions(command);
    }

    @Test void firstStoreStockAndEntityStockCannotWriteUnderOldOwnership() {
        var gate=mock(StoreOwnershipWritePort.class);var service=frozen(gate);
        assertThrows(BizException.class,()->service.saveStoreStock("M","S","G","SKU",0));
        assertThrows(BizException.class,()->service.saveStock("M","G","SKU",0));
        verify(gate,times(2)).lockAndCheck("MAIN","M","S");
    }

    @Test void existingDraftAndMetadataUpdatesCannotReviveOldSource() {
        var gate=mock(StoreOwnershipWritePort.class);var service=frozen(gate);
        assertThrows(BizException.class,()->service.publishDraft("M","G",0L));
        assertThrows(BizException.class,()->service.setPayModes("M","G",java.util.List.of("ONLINE")));
        assertThrows(BizException.class,()->service.saveStorePrice("M","S","G","SKU",1L,1L));
        verify(gate,times(3)).lockAndCheck("MAIN","M","S");
    }
}
