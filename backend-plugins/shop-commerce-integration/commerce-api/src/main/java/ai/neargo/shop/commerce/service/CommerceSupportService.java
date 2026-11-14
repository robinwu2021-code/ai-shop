package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ChannelStatisticsContracts;
import ai.neargo.shop.commerce.dto.OperationVO;

/**
 * Platform support remains read-only and explicitly scoped; authorization URLs and original
 * payloads are excluded.
 */
public interface CommerceSupportService {
    ChannelStatisticsContracts.Health supportHealth(String entityNo, String storeNo);

    OperationVO supportOperation(String entityNo, String storeNo, String operationNo);
}
