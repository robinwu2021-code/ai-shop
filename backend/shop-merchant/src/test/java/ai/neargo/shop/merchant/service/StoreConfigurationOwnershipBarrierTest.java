package ai.neargo.shop.merchant.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.service.impl.MerchantStoreServiceImpl;
import ai.neargo.shop.merchant.service.impl.StoreFulfillmentServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import java.util.List;
import org.junit.jupiter.api.Test;

class StoreConfigurationOwnershipBarrierTest {
    @Test void storeProfileCannotContinueToAreaOrCommunitySideWritesUnderOldOwnership() {
        var gate=mock(StoreOwnershipWritePort.class);
        var service=mock(MerchantStoreServiceImpl.class,CALLS_REAL_METHODS);
        service.setOwnership(gate);
        doThrow(BizException.of(ErrorCode.CONFLICT)).when(gate).lockAndCheck("MAIN","M","S");
        var command=mock(MerchantStoreService.SaveCommand.class);
        assertThrows(BizException.class,()->service.save("M","S",command));
        verifyNoInteractions(command);
        verify(gate).lockAndCheck("MAIN","M","S");
    }

    @Test void activationCommunityMirrorRequiresSourceEntityFenceBeforeAnyRevive() {
        var gate=mock(StoreOwnershipWritePort.class);
        var service=mock(MerchantStoreServiceImpl.class,CALLS_REAL_METHODS);
        service.setOwnership(gate);
        doThrow(BizException.of(ErrorCode.CONFLICT)).when(gate).lockEntityAndCheck("MAIN","M");
        assertThrows(BizException.class,()->service.syncCommunities("M",List.of("C")));
        verify(gate).lockEntityAndCheck("MAIN","M");
    }

    @Test void fulfillmentFirstWriteCannotCreateSourceOwnedChannelAfterFreeze() {
        var gate=mock(StoreOwnershipWritePort.class);
        var service=mock(StoreFulfillmentServiceImpl.class,CALLS_REAL_METHODS);
        service.setOwnership(gate);
        doThrow(BizException.of(ErrorCode.CONFLICT)).when(gate).lockAndCheck("MAIN","M","S");
        assertThrows(BizException.class,()->service.save("M","S",List.of()));
        verify(gate).lockAndCheck("MAIN","M","S");
    }
}
