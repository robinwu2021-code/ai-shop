package ai.neargo.shop.retail.commerce.service;

import ai.neargo.shop.retail.commerce.dto.RetailSelection;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionContracts.Prepared;
import ai.neargo.shop.retail.commerce.dto.RetailSelectionContracts.Publish;

/** Publication qualification always reads the original product and channel authorization owners. */
public interface RetailSelectionService {
    RetailSelection publish(Publish command);

    RetailSelection prepare(Publish command);

    Prepared prepareWithEvidence(Publish command);

    void assertFresh(Prepared prepared);
}
