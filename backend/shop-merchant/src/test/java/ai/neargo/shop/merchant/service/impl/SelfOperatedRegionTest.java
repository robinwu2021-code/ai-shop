package ai.neargo.shop.merchant.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import ai.neargo.shop.merchant.service.SelfOperatedService;
import ai.neargo.shop.merchant.mapper.MerchantMappers.MchEntityMapper;
import ai.neargo.shop.spi.platform.MarketContactPolicyPort;
import ai.neargo.shop.spi.platform.MasterDataPort;
import ai.neargo.shop.spi.user.MerchantAdminPort;
import ai.neargo.shop.spi.user.UserProvisionPort;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class SelfOperatedRegionTest {
    static class Activated extends RuntimeException {}
    private void check(String market,boolean recordOnly,String phone,String email) {
        var service=mock(SelfOperatedServiceImpl.class,CALLS_REAL_METHODS);
        var contacts=mock(MarketContactPolicyPort.class);var policy=mock(MarketContactPolicyPort.Policy.class);
        when(policy.market()).thenReturn(market);when(policy.recordOnly()).thenReturn(recordOnly);
        when(contacts.instancePolicy()).thenReturn(policy);when(contacts.validateContact(eq(market),any())).thenReturn(policy);
        var users=mock(UserProvisionPort.class);when(users.registeredUserByEmail("owner@example.test")).thenReturn(Optional.of("OWNER"));when(users.ensureUserByPhone("13800138000")).thenReturn("OWNER");
        var admin=mock(MerchantAdminPort.class);when(admin.activateSelfOperated(any())).thenThrow(new Activated());
        ReflectionTestUtils.setField(service,"contacts",contacts);ReflectionTestUtils.setField(service,"userProvision",users);ReflectionTestUtils.setField(service,"masterDataPort",mock(MasterDataPort.class));ReflectionTestUtils.setField(service,"entityMapper",mock(MchEntityMapper.class));ReflectionTestUtils.setField(service,"merchantAdminPort",admin);
        assertThrows(Activated.class,()->service.create(new SelfOperatedService.CreateCommand(phone,"Store","CITY",List.of(),null,null,email),"OPS"));
        var captured=ArgumentCaptor.forClass(MerchantAdminPort.ActivateCommand.class);verify(admin).activateSelfOperated(captured.capture());assertEquals(market,captured.getValue().marketCode());assertEquals("OWNER",captured.getValue().ownerUserNo());verify(contacts).validateContact(market,phone);
        if(recordOnly){verify(users).registeredUserByEmail(email);verify(users,never()).ensureUserByPhone(any());}else{verify(users).ensureUserByPhone(phone);verify(users,never()).registeredUserByEmail(any());}
    }
    @Test void taiwanUsesRegisteredEmailAndSystemRegionWithoutPhoneIdentity(){check("TW",true,"contact / extension","owner@example.test");}
    @Test void taiwanAllowsMissingRecordOnlyPhone(){check("TW",true,null,"owner@example.test");}
    @Test void mainlandKeepsExistingPhoneOwnerResolution(){check("CN",false,"13800138000",null);}
}
