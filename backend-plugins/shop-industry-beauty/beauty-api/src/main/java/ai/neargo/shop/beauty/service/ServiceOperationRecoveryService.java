package ai.neargo.shop.beauty.service;

import ai.neargo.shop.beauty.dto.OperationVO;

/** Explicit authenticated recovery can use a different current manager without impersonating the initiator. */
public interface ServiceOperationRecoveryService {
    OperationVO recoverOperation(String entityNo, String currentStoreNo, String actorNo, String operationNo);
}
