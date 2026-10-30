package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.OperationVO;

/** Current authenticated scope is verified for every appointment operation. */
public interface ServiceOperationQueryService {
    OperationVO getOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo);
}
