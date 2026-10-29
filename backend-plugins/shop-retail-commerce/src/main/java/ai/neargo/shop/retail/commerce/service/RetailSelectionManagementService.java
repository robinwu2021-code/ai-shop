package ai.neargo.shop.retail.commerce.service;

import ai.neargo.shop.retail.commerce.dto.RetailSelectionManagementContracts.PublishRequest;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionManagementContracts.SaveRequest;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionManagementContracts.View;

import java.util.List;

/**
 * Scoped draft and publication commands preserve original CAS versions and durable replay receipts.
 */
public interface RetailSelectionManagementService {
    List<View> list(String entity, String store, int offset, int size);

    View detail(String entity, String store, String selection);

    View save(
            String entity,
            String store,
            String selection,
            String actor,
            SaveRequest request,
            String key);

    View publish(
            String entity,
            String store,
            String selection,
            String actor,
            PublishRequest request,
            String key);
}
