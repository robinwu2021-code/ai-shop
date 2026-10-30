package ai.neargo.shop.beauty.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.PrepareServiceTaskReq;
import ai.neargo.shop.beauty.dto.ServiceTaskPreparationVO;
import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;
import ai.neargo.shop.spi.security.DelegatedIdentity;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionBinding;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;

import org.junit.jupiter.api.Test;

/** Public preparations remain staged even after real stock pins have been observed. */
class ServiceTaskPreparationServiceImplTest {
    @Test
    void realStockRetainedStillCannotStart() {
        var view =
                new ServiceTaskPreparationVO(
                        "submission",
                        "work",
                        "task",
                        "execution",
                        "STOCK_RETAINED",
                        "PREPARED",
                        "RETAINED",
                        null,
                        null,
                        null);
        assertFalse(view.canStart());
        assertEquals("submission", view.operationNo());
    }

    @Test
    void missingReferenceRequiresAnAuthenticatedOriginalRecovery() {
        var view =
                ServiceTaskStockPreparationCoordinator.progress(
                        "s",
                        "w",
                        "t",
                        "e",
                        "PREPARED",
                        "HUMAN_REFERENCE_REQUIRED",
                        "UNKNOWN",
                        "HUMAN_REFERENCE_REQUIRED",
                        null);
        assertEquals("HUMAN_REFERENCE_REQUIRED", view.recoveryAction());
        assertFalse(view.canStart());
    }

    @Test
    void publicApplicationCallsQuantityBeforeStockAndUsesOnlyTrustedScope() {
        var quantity = mock(ServiceTaskExecutionService.class);
        var stock = mock(ServiceTaskStockPreparationCoordinator.class);
        String hash = "a".repeat(64);
        var binding =
                new ExecutionBinding(
                        new ExecutionScope("MAIN", "E", "S"),
                        "BEAUTY",
                        "operation",
                        "order",
                        "sub",
                        "line",
                        1,
                        "work",
                        1,
                        "task",
                        0,
                        "binding",
                        1,
                        "execution",
                        1,
                        "submission",
                        "fence",
                        1,
                        1,
                        1,
                        hash,
                        "definition",
                        hash,
                        hash,
                        hash);
        when(quantity.prepareTask(any()))
                .thenReturn(
                        new ServiceTaskExecutionService.ExecutionPreparation(
                                binding, null, "SOURCE_UNKNOWN"));
        var application = new ServiceTaskPreparationServiceImpl(quantity, stock);
        DelegatedIdentityContext.call(
                new DelegatedIdentity("MAIN", "MERCHANT", "USR", "actor"),
                () ->
                        application.prepareTask(
                                "E",
                                "S",
                                "actor",
                                "work",
                                "task",
                                new PrepareServiceTaskReq(0, 1, 1),
                                "key"));
        var ordered = inOrder(quantity, stock);
        ordered.verify(quantity)
                .prepareTask(new FreezeStart(binding.scope(), "work", "task", 0, 1, 1, "key"));
        ordered.verify(stock).prepareAcceptedOriginal(binding, null);
    }
}
