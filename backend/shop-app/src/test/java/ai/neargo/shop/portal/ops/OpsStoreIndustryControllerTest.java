package ai.neargo.shop.portal.ops;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.commerce.api.common.*;
import ai.neargo.commerce.starter.http.MallHttpContextPort;
import ai.neargo.shop.merchant.service.StoreIndustryAdminService;
import ai.neargo.shop.spi.platform.AuditLogPort;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class OpsStoreIndustryControllerTest {
    @Test void frameworkRejectsInvalidFieldsBeforeAnyBusinessWrite() throws Exception {
        var service = mock(StoreIndustryAdminService.class);
        var context = mock(MallHttpContextPort.class);
        var validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        var mvc = MockMvcBuilders.standaloneSetup(new OpsStoreIndustryController(service, context, mock(AuditLogPort.class)))
                .setValidator(validator).build();
        mvc.perform(post("/ops/store-industries").contentType(MediaType.APPLICATION_JSON)
                .content("{\"industryCode\":\"RETAIL\",\"subIndustryCode\":\"\",\"businessTypeCode\":\"RETAIL\",\"nameZh\":\"\",\"nameTw\":\"零售\",\"sort\":-1,\"enabled\":true,\"expectedVersion\":null,\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service, context);
        validator.close();
    }

    @Test void originalOperatorAndTenantReachServiceAndWriteAuthorityIsNotReadAuthority() throws Exception {
        var service = mock(StoreIndustryAdminService.class);
        var context = mock(MallHttpContextPort.class);
        var audit = mock(AuditLogPort.class);
        var actor = new ActorContext("MAIN", SubjectKind.OPERATOR, "OPS1", "real-session");
        when(context.operator("commerce:catalog:edit")).thenReturn(actor);
        var command = new StoreIndustryAdminService.Save("RETAIL", "", "RETAIL", "零售", "零售", "Retail", 10, false, 7L, "暂停售卖");
        when(service.save(actor, command)).thenReturn(new StoreIndustryAdminService.View("RETAIL", "", "RETAIL", "零售", "零售", "Retail", 10, false, 8L));
        var controller = new OpsStoreIndustryController(service, context, audit);
        assertThat(controller.save(command).version()).isEqualTo(8);
        verify(service).save(actor, command);
        verify(audit).record("STORE_INDUSTRY_SAVE", "RETAIL:", "暂停售卖");
        assertThat(OpsStoreIndustryController.class.getMethod("save", StoreIndustryAdminService.Save.class)
                .getAnnotation(PreAuthorize.class).value()).contains("commerce:catalog:edit");
        assertThat(OpsStoreIndustryController.class.getMethod("list").getAnnotation(PreAuthorize.class).value())
                .contains("commerce:catalog:read");
    }
}
