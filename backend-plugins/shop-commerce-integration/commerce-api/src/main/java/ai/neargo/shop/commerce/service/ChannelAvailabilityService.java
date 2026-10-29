package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.AvailabilityContracts;
import ai.neargo.shop.commerce.dto.OperationVO;

public interface ChannelAvailabilityService {
    OperationVO publishStore(
            String entityNo,
            String storeNo,
            String actor,
            AvailabilityContracts.StorePublish request,
            String key);

    OperationVO publishStock(
            String entityNo,
            String storeNo,
            String actor,
            AvailabilityContracts.Publish request,
            String key);
}
