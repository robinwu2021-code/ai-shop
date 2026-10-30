package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.OperationContracts.Attempt;
import ai.neargo.shop.commerce.dto.OperationContracts.Query;
import ai.neargo.shop.commerce.dto.OperationContracts.Schedule;
import ai.neargo.shop.commerce.dto.OperationVO;
import ai.neargo.shop.common.PageData;

/** 只读本域动作状态，不在GET隐式发送或恢复外部动作。 */
public interface ChannelOperationService {
    default PageData<OperationVO> listOperations(String entity, String store, Query query) {
        throw new UnsupportedOperationException("Operation list unavailable");
    }

    default PageData<Attempt> listAttempts(
            String entity, String store, String number, int page, int size) {
        throw new UnsupportedOperationException("Operation evidence unavailable");
    }

    default OperationVO scheduleRecovery(
            String entity,
            String store,
            String number,
            String actor,
            Schedule request,
            String key,
            boolean refreshOnly) {
        throw new UnsupportedOperationException("Operation recovery unavailable");
    }

    OperationVO getOperation(String entity, String operationNo);
}
