package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.OperationVO;

/** 只读本域动作状态，不在GET隐式发送或恢复外部动作。 */
public interface ChannelOperationService {
    OperationVO getOperation(String entity, String operationNo);
}
