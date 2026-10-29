package ai.neargo.shop.beauty.service.impl;

import ai.neargo.shop.auth.DelegatedIdentityContext;
import ai.neargo.shop.beauty.dto.PrepareServiceTaskReq;
import ai.neargo.shop.beauty.dto.ServiceTaskPreparationVO;
import ai.neargo.shop.beauty.service.ServiceTaskExecutionService;
import ai.neargo.shop.beauty.service.ServiceTaskPreparationService;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.spi.trade.ServiceExecutionPort.ExecutionScope;
import ai.neargo.shop.spi.trade.ServiceExecutionSourcePort.FreezeStart;

/**
 * The public application path composes real quantity preparation followed by real stock retention.
 */
public final class ServiceTaskPreparationServiceImpl implements ServiceTaskPreparationService {
    private final ServiceTaskExecutionService quantity;
    private final ServiceTaskStockPreparationCoordinator stock;

    public ServiceTaskPreparationServiceImpl(
            ServiceTaskExecutionService quantity, ServiceTaskStockPreparationCoordinator stock) {
        this.quantity = quantity;
        this.stock = stock;
    }

    @Override
    public ServiceTaskPreparationVO prepareTask(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            PrepareServiceTaskReq request,
            String requestKey) {
        var identity = DelegatedIdentityContext.current(actor);
        if (!"MERCHANT".equals(identity.realm()) || !"MAIN".equals(identity.tenantNo())) {
            throw BizException.of(ErrorCode.FORBIDDEN);
        }
        var prepared =
                quantity.prepareTask(
                        new FreezeStart(
                                new ExecutionScope(identity.tenantNo(), entity, store),
                                workOrder,
                                task,
                                request.expectedTaskRevision(),
                                request.expectedBindingRevision(),
                                request.expectedScopeRevision(),
                                requestKey));
        return stock.prepareAcceptedOriginal(prepared.binding(), prepared.grant());
    }
}
