package ai.neargo.shop.platform.api.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.platform.*;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.UserProvisionPort;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsMerchantRegionalContactTest {
    @Test void unavailableOwnerReturnsAnAccountSpecificErrorBeforeWritingAnApplication() {
        var service = mock(OpsService.class);
        var users = mock(UserProvisionPort.class);
        var phones = mock(MarketPhonePolicyService.class);
        var policy = new MarketPhonePolicyService.Policy("TW", "+886", true, false, false, true);
        when(phones.instancePolicy()).thenReturn(policy);
        when(phones.validateContact("TW", null)).thenReturn(policy);
        when(users.registeredUserByEmail("missing@example.test")).thenReturn(Optional.empty());
        var controller = new OpsMerchantApplyController(service, users, mock(AuditLogPort.class), phones);
        try (var security = mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::currentUserNo).thenReturn("OPS");
            var error = assertThrows(BizException.class, () -> controller.applyOnBehalf(
                    new OpsMerchantApplyController.OnBehalfReq(null, "Entity", "ENTERPRISE",
                            null, null, null, null, null, null, null, null, null, null,
                            null, "missing@example.test", null)));
            assertEquals(ErrorCode.OWNER_ACCOUNT_UNAVAILABLE, error.errorCode());
        }
        verifyNoInteractions(service);
        verify(users, never()).ensureUserByPhone(any());
    }

    @Test void taiwanUsesRegisteredEmailAndRecordsTheContactWithoutProvisioningAPhoneIdentity() throws Exception {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        when(phones.instancePolicy()).thenReturn(new MarketPhonePolicyService.Policy("TW","+886",true,false,false,true));
        when(phones.validateContact(eq("TW"),any())).thenReturn(new MarketPhonePolicyService.Policy("TW","+886",true,false,false,true));
        when(users.registeredUserByEmail("owner@example.test")).thenReturn(Optional.of("OWNER"));
        when(service.createApplyOnBehalf(any(),eq("OPS"))).thenReturn("MA-TEST");
        var mvc=MockMvcBuilders.standaloneSetup(new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones)).build();
        try(var security=mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::currentUserNo).thenReturn("OPS");
            assertEquals(200,mvc.perform(post("/ops/merchants/apply-on-behalf").contentType("application/json").content("{\"ownerEmail\":\"owner@example.test\",\"phone\":\"09 1234 5678 / 分机\",\"name\":\"Test\",\"subject\":\"ENTERPRISE\",\"storeProfile\":{\"name\":\"Taipei Store\",\"address\":\"Taipei address\",\"industryCode\":\"RETAIL\",\"subIndustryCode\":\"SHOP\",\"businessTypeCode\":\"RETAIL\"}}")).andReturn().getResponse().getStatus());
        }
        var captured=ArgumentCaptor.forClass(OpsService.SubmitApplyCommand.class);verify(service).createApplyOnBehalf(captured.capture(),eq("OPS"));
        assertEquals("Taipei Store",captured.getValue().storeProfile().name());assertEquals("RETAIL",captured.getValue().storeProfile().businessTypeCode());assertEquals("OWNER",captured.getValue().userNo());assertEquals("TW",captured.getValue().marketCode());
        assertEquals("09 1234 5678 / 分机",captured.getValue().contactPhone());verify(users,never()).ensureUserByPhone(any());
    }
    @Test void legacyMainlandRequestsKeepTheirExistingOwnerResolution() throws Exception {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        when(phones.instancePolicy()).thenReturn(new MarketPhonePolicyService.Policy("CN","+86",false,true,true,true));
        when(phones.validateContact(eq("CN"),any())).thenReturn(new MarketPhonePolicyService.Policy("CN","+86",false,true,true,true));
        when(users.ensureUserByPhone("13800138000")).thenReturn("OWNER");
        var mvc=MockMvcBuilders.standaloneSetup(new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones)).build();
        try(var security=mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::currentUserNo).thenReturn("OPS");
            assertEquals(200,mvc.perform(post("/ops/merchants/apply-on-behalf").contentType("application/json").content("{\"phone\":\"13800138000\",\"name\":\"Test\",\"subject\":\"ENTERPRISE\"}")).andReturn().getResponse().getStatus());
        }
        verify(phones).validateContact("CN","13800138000");verify(users).ensureUserByPhone("13800138000");verify(users,never()).registeredUserByEmail(any());
    }

    @Test void storeProfileUsesFrameworkValidationBeforeAnApplicationWrite() throws Exception {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones)).build();
        assertEquals(400,mvc.perform(post("/ops/merchants/apply-on-behalf").contentType("application/json")
                .content("{\"market\":\"TW\",\"ownerEmail\":\"owner@example.test\",\"name\":\"Entity\",\"subject\":\"ENTERPRISE\",\"storeProfile\":{\"name\":\"Shop\",\"address\":\"\",\"industryCode\":\"RETAIL\",\"subIndustryCode\":\"SHOP\",\"businessTypeCode\":\"RETAIL\"}}"))
                .andReturn().getResponse().getStatus());
        verifyNoInteractions(service,users,phones);
    }


    @Test void addressCannotExceedThePersistedStoreColumn() throws Exception {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones)).build();
        String payload="{\"name\":\"Entity\",\"subject\":\"ENTERPRISE\",\"storeProfile\":{\"name\":\"Shop\",\"address\":\""+"a".repeat(256)+"\",\"industryCode\":\"RETAIL\",\"subIndustryCode\":\"SHOP\",\"businessTypeCode\":\"RETAIL\"}}";
        assertEquals(400,mvc.perform(post("/ops/merchants/apply-on-behalf").contentType("application/json").content(payload)).andReturn().getResponse().getStatus());
        verifyNoInteractions(service,users,phones);
    }

    @Test void malformedEmailIsRejectedByTheFrameworkBeforeAnyApplicationWrite() throws Exception {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones)).build();
        assertEquals(400,mvc.perform(post("/ops/merchants/apply-on-behalf").contentType("application/json").content("{\"market\":\"TW\",\"ownerEmail\":\"invalid\",\"phone\":\"待补\",\"name\":\"Test\",\"subject\":\"ENTERPRISE\"}")).andReturn().getResponse().getStatus());
        verifyNoInteractions(service,users,phones);
    }
    @Test void rejectsCallerRegionOverrideBeforeResolvingOrCreatingAnOwner() {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        when(phones.instancePolicy()).thenReturn(new MarketPhonePolicyService.Policy("TW","+886",true,false,false,true));
        var controller=new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones);
        try(var security=mockStatic(SecurityUtils.class)) {
        security.when(SecurityUtils::currentUserNo).thenReturn("OPS");
        assertThrows(ai.neargo.shop.common.BizException.class,()->controller.applyOnBehalf(
            new OpsMerchantApplyController.OnBehalfReq("13800138000","Entity","ENTERPRISE",null,null,null,null,null,null,null,null,null,null,"CN",null,null)));
        }
        verifyNoInteractions(service,users);verify(phones,never()).validateContact(any(),any());
    }

    @Test void contextUsesTheSystemRegionAndSearchAlwaysRestrictsToAssistedApplications() {
        var service=mock(OpsService.class);var phones=mock(MarketPhonePolicyService.class);
        var policy=new MarketPhonePolicyService.Policy("TW","+886",true,false,false,true);
        when(phones.instancePolicy()).thenReturn(policy);
        var controller=new OpsMerchantApplyController(service,mock(UserProvisionPort.class),mock(AuditLogPort.class),phones);
        assertSame(policy,controller.context());controller.search("APPROVED","Example",2,10);
        verify(service).searchApplies("APPROVED","Example",2,10,true);
    }
}
