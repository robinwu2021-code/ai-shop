package ai.neargo.shop.beauty.api.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.PreviewWorkOrderSettlementReq;
import ai.neargo.shop.beauty.service.WorkOrderSettlementService;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.StoreDirectoryPort;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class WorkOrderSettlementIngressTest {
    @AfterEach void clear() { BizContext.clear(); }

    @Test void currentContextControlsReadsAndFrameworkRejectsInvalidPreviewWithoutOwnerEffects() {
        var beans = new DefaultListableBeanFactory();
        var service = mock(WorkOrderSettlementService.class);
        var directory = mock(StoreDirectoryPort.class);
        when(directory.cards(any())).thenReturn(Map.of("S", new StoreDirectoryPort.StoreCard("S", "Store", "E",
                null, "ACTIVE", true, "", "", null, null, 0, 0)));
        beans.registerSingleton("service", service);
        beans.registerSingleton("directory", directory);
        var mvc = MockMvcBuilders.standaloneSetup(new BizWorkOrderSettlementController(
                beans.getBeanProvider(WorkOrderSettlementService.class), beans.getBeanProvider(StoreDirectoryPort.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        DelegatedIdentityContext.call(new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "A"), () -> {
            try {
                mvc.perform(get("/biz/x/beauty/work-orders/WO/settlement").header("X-Store-No", "S"))
                        .andExpect(status().isOk());
                verify(service).get("E", "S", "A", "WO");
                clearInvocations(service);
                mvc.perform(get("/biz/x/beauty/work-orders/WO/settlement").header("X-Store-No", "FOREIGN"))
                        .andExpect(jsonPath("$.code").value(10403));
                String body = "{\"expectedFingerprint\":\"" + "a".repeat(64)
                        + "\",\"currency\":\"CNY\",\"walletMinor\":0,\"externalMinor\":10,\"method\":\"CASH\"}";
                for (String invalid : new String[] {body.replace("\"externalMinor\":10", "\"externalMinor\":-1"),
                        body.replace("\"externalMinor\":10", "\"externalMinor\":1.5"),
                        body.replace("\"externalMinor\":10", "\"externalMinor\":\"10\""),
                        body.replace("\"CNY\"", "\"cny\"")}) {
                    mvc.perform(post("/biz/x/beauty/work-orders/WO/settlement-previews").header("X-Store-No", "S")
                            .contentType("application/json").content(invalid)).andExpect(jsonPath("$.code").value(10400));
                }
                verifyNoInteractions(service);
                mvc.perform(post("/biz/x/beauty/work-orders/WO/settlement-previews").header("X-Store-No", "S")
                        .contentType("application/json").content(body)).andExpect(status().isOk());
                verify(service).preview(eq("E"), eq("S"), eq("A"), eq("WO"), any(PreviewWorkOrderSettlementReq.class));
            } catch (Exception e) { throw new AssertionError(e); }
            return null;
        });
    }
}
