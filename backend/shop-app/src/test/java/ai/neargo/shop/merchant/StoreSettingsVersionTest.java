package ai.neargo.shop.merchant;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.merchant.dto.StoreProfileVO;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchStoreMapper;
import ai.neargo.shop.merchant.service.*;
import ai.neargo.shop.merchant.service.impl.StoreSettingsServiceImpl;
import ai.neargo.shop.spi.user.StoreOwnershipWritePort;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class StoreSettingsVersionTest {
    @Test void staleBasicVersionDoesNotWriteAndHoursChangeDoesNotInvalidateBasic() {
        var profiles=mock(MerchantStoreService.class);var mapper=mock(MchStoreMapper.class);
        var ownership=mock(StoreOwnershipWritePort.class);var mirror=mock(PayMerchantMirror.class);
        var service=new StoreSettingsServiceImpl(profiles,mapper,ownership,mirror);
        var profile=mock(StoreProfileVO.class);
        when(profile.name()).thenReturn("Shop");when(profile.address()).thenReturn("Taipei");when(profile.addressDetail()).thenReturn("1F");
        when(profile.openHours()).thenReturn("09:00-18:00");when(profiles.profile("E1","S1")).thenReturn(profile);
        var before=service.load("E1","S1");
        when(profile.openHours()).thenReturn("10:00-18:00");
        assertEquals(before.sectionVersions().get("BASIC"),service.load("E1","S1").sectionVersions().get("BASIC"));
        assertNotEquals(before.sectionVersions().get("HOURS"),service.load("E1","S1").sectionVersions().get("HOURS"));
        when(profile.name()).thenReturn("Someone changed it");
        assertEquals(ErrorCode.CONFLICT,assertThrows(BizException.class,()->service.basic("E1","S1",before.sectionVersions().get("BASIC"),"Mine","Taipei","2F")).errorCode());
        verify(ownership).lockAndCheck("MAIN","E1","S1");verifyNoInteractions(mapper,mirror);
        assertEquals(ErrorCode.CONFLICT,assertThrows(BizException.class,()->service.hours("E1","S1",before.sectionVersions().get("HOURS"),"11:00-18:00")).errorCode());
    }
}
