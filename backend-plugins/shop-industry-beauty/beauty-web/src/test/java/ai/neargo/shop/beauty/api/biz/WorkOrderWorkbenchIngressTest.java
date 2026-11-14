package ai.neargo.shop.beauty.api.biz;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.WorkOrderQueryReq;
import ai.neargo.shop.beauty.service.WorkOrderService;
import ai.neargo.shop.beauty.service.WorkOrderWorkbenchService;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.common.PageData;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

class WorkOrderWorkbenchIngressTest {
    @AfterEach
    void clear() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void currentContextOwnsQueueAndForeignHeaderCannotReachService() {
        var beans = new DefaultListableBeanFactory();
        var service = mock(WorkOrderWorkbenchService.class);
        var directory = mock(StoreDirectoryPort.class);
        when(directory.cards(any()))
                .thenReturn(
                        Map.of(
                                "S",
                                new StoreDirectoryPort.StoreCard(
                                        "S", "Store", "E", null, "ACTIVE", true, "", "", null, null,
                                        0, 0)));
        beans.registerSingleton("queue", service);
        beans.registerSingleton("directory", directory);
        var controller =
                new BizWorkOrderController(
                        beans.getBeanProvider(WorkOrderService.class),
                        beans.getBeanProvider(StoreDirectoryPort.class));
        controller.setWorkbench(beans.getBeanProvider(WorkOrderWorkbenchService.class));
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        when(service.list(eq("E"), eq("S"), eq("ACTOR"), any()))
                .thenReturn(new PageData<>(List.of(), 51, 2, 20));
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "ACTOR"),
                () -> {
                    try {
                        mvc.perform(
                                        get("/biz/x/beauty/work-orders")
                                                .header("X-Store-No", "S")
                                                .param("page", "2")
                                                .param("from", "2026-11-10T08:00:00+08:00")
                                                .param("entityNo", "FOREIGN"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.total").value(51))
                                .andExpect(jsonPath("$.page").value(2));
                        for (String status : List.of("TO_PAY", "COMPLETED"))
                            mvc.perform(
                                            get("/biz/x/beauty/work-orders")
                                                    .header("X-Store-No", "S")
                                                    .param("status", status))
                                    .andExpect(status().isOk())
                                    .andExpect(
                                            jsonPath("$.code").value(ErrorCode.BAD_REQUEST.code()));
                        mvc.perform(
                                        get("/biz/x/beauty/work-orders")
                                                .header("X-Store-No", "S")
                                                .param("page", "0"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.code()));
                        mvc.perform(
                                        get("/biz/x/beauty/work-orders")
                                                .header("X-Store-No", "FOREIGN"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
                    } catch (Exception failure) {
                        throw new RuntimeException(failure);
                    }
                    return null;
                });
        var query = ArgumentCaptor.forClass(WorkOrderQueryReq.class);
        verify(service).list(eq("E"), eq("S"), eq("ACTOR"), query.capture());
        assertThat(query.getValue().from().toInstant().toString())
                .isEqualTo("2026-11-10T00:00:00Z");
        assertThat(query.getValue().size()).isEqualTo(20);
    }
}
