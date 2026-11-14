package ai.neargo.shop.retail.commerce.mapper;

import ai.neargo.shop.retail.commerce.dto.RetailSelection;

/** Only the selection owner reads its immutable references and CAS-controlled current pointer. */
public interface RetailSelectionMapper {
    RetailSelection revision(String entity, String store, String selection, String revision);

    RetailSelection current(String entity, String store, String selection);

    void publish(RetailSelection selection, String expectedRevision);
}
