package ai.neargo.shop.beauty.api.biz;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ai.neargo.shop.auth.BizContext;
import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.TaskWorkflowVO;
import ai.neargo.shop.beauty.service.ServiceTaskWorkflowService;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.common.GlobalExceptionHandler;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.user.StoreDirectoryPort;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.Set;

class TaskWorkflowIngressTest {
    @AfterEach
    void clear() {
        BizContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void cancellationUsesCurrentStoreAndExactOriginalOperationAndRejectsForeignHeader() {
        var beans = new DefaultListableBeanFactory();
        var workflow = mock(ServiceTaskWorkflowService.class);
        var directory = mock(StoreDirectoryPort.class);
        when(directory.cards(any()))
                .thenReturn(
                        Map.of(
                                "S",
                                new StoreDirectoryPort.StoreCard(
                                        "S", "Store", "E", null, "ACTIVE", true, "", "", null, null,
                                        0, 0)));
        beans.registerSingleton("workflow", workflow);
        beans.registerSingleton("directory", directory);
        var controller =
                new BizTaskWorkflowController(
                        beans.getBeanProvider(ServiceTaskWorkflowService.class),
                        beans.getBeanProvider(StoreDirectoryPort.class));
        var mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        when(workflow.cancelPending("E", "S", "COORDINATOR", "W", "T", "ORIGINAL"))
                .thenReturn(
                        new TaskWorkflowVO(
                                "ORIGINAL",
                                "W",
                                "T",
                                "PAUSED",
                                5,
                                "O",
                                2,
                                "PAUSED",
                                false,
                                "NOT_REQUIRED",
                                "CANCELLED_BEFORE_EFFECT",
                                List.of()));
        BizContext.set(new BizContext("E", Set.of(), Set.of(), Set.of("S"), "S", false));
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "MCH", "COORDINATOR"),
                () -> {
                    try {
                        mvc.perform(
                                        post("/biz/x/beauty/work-orders/W/tasks/T/operations/ORIGINAL/cancel")
                                                .header("X-Store-No", "S"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.operationNo").value("ORIGINAL"));
                        mvc.perform(
                                        post("/biz/x/beauty/work-orders/W/tasks/T/operations/ORIGINAL/cancel")
                                                .header("X-Store-No", "FOREIGN"))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.code()));
                    } catch (Exception failed) {
                        throw new RuntimeException(failed);
                    }
                    return null;
                });
        verify(workflow).cancelPending("E", "S", "COORDINATOR", "W", "T", "ORIGINAL");
        verifyNoMoreInteractions(workflow);
    }
}
