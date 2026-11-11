package ai.neargo.shop.platform.api.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import ai.neargo.shop.auth.SecurityUtils;
import ai.neargo.shop.platform.*;
import ai.neargo.shop.spi.platform.AuditLogPort;
import ai.neargo.shop.spi.user.UserProvisionPort;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsMerchantRegionalContactTest {
    @Test void taiwanUsesRegisteredEmailAndRecordsTheContactWithoutProvisioningAPhoneIdentity() throws Exception {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        when(phones.validateContact(eq("TW"),any())).thenReturn(new MarketPhonePolicyService.Policy("TW","+886",true,false,false,true));
        when(users.registeredUserByEmail("owner@example.test")).thenReturn(Optional.of("OWNER"));
        when(service.createApplyOnBehalf(any(),eq("OPS"))).thenReturn("MA-TEST");
        var mvc=MockMvcBuilders.standaloneSetup(new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones)).build();
        try(var security=mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::currentUserNo).thenReturn("OPS");
            assertEquals(200,mvc.perform(post("/ops/merchants/apply-on-behalf").contentType("application/json").content("{\"market\":\"TW\",\"ownerEmail\":\"owner@example.test\",\"phone\":\"09 1234 5678 / 分机\",\"name\":\"Test\",\"subject\":\"ENTERPRISE\"}")).andReturn().getResponse().getStatus());
        }
        var captured=ArgumentCaptor.forClass(OpsService.SubmitApplyCommand.class);verify(service).createApplyOnBehalf(captured.capture(),eq("OPS"));
        assertEquals("OWNER",captured.getValue().userNo());assertEquals("TW",captured.getValue().marketCode());
        assertEquals("09 1234 5678 / 分机",captured.getValue().contactPhone());verify(users,never()).ensureUserByPhone(any());
    }
    @Test void legacyMainlandRequestsKeepTheirExistingOwnerResolution() throws Exception {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        when(phones.validateContact(eq("CN"),any())).thenReturn(new MarketPhonePolicyService.Policy("CN","+86",false,true,true,true));
        when(users.ensureUserByPhone("13800138000")).thenReturn("OWNER");
        var mvc=MockMvcBuilders.standaloneSetup(new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones)).build();
        try(var security=mockStatic(SecurityUtils.class)) {
            security.when(SecurityUtils::currentUserNo).thenReturn("OPS");
            assertEquals(200,mvc.perform(post("/ops/merchants/apply-on-behalf").contentType("application/json").content("{\"phone\":\"13800138000\",\"name\":\"Test\",\"subject\":\"ENTERPRISE\"}")).andReturn().getResponse().getStatus());
        }
        verify(phones).validateContact("CN","13800138000");verify(users).ensureUserByPhone("13800138000");verify(users,never()).registeredUserByEmail(any());
    }
    @Test void malformedEmailIsRejectedByTheFrameworkBeforeAnyApplicationWrite() throws Exception {
        var service=mock(OpsService.class);var users=mock(UserProvisionPort.class);var phones=mock(MarketPhonePolicyService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new OpsMerchantApplyController(service,users,mock(AuditLogPort.class),phones)).build();
        assertEquals(400,mvc.perform(post("/ops/merchants/apply-on-behalf").contentType("application/json").content("{\"market\":\"TW\",\"ownerEmail\":\"invalid\",\"phone\":\"待补\",\"name\":\"Test\",\"subject\":\"ENTERPRISE\"}")).andReturn().getResponse().getStatus());
        verifyNoInteractions(service,users,phones);
    }
}
