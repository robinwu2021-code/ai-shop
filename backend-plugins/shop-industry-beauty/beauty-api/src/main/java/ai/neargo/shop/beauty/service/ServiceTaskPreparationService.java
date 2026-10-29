package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.PrepareServiceTaskReq;
import ai.neargo.shop.beauty.dto.ServiceTaskPreparationVO;

/**
 * Reachable application composition preserves quantity preparation as a separately callable
 * primitive.
 */
public interface ServiceTaskPreparationService {
    ServiceTaskPreparationVO prepareTask(
            String entity,
            String store,
            String actor,
            String workOrder,
            String task,
            PrepareServiceTaskReq request,
            String requestKey);
}
