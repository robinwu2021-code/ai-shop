package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.food.dto.CatalogVO;

/** Reads published restaurant display using resolved staff, public-store or original guest credentials. */
public interface CatalogService {
    CatalogVO read(String entity, String store, CatalogQuery query);
    CatalogVO publicCatalog(String store, String fulfillment);
    CatalogVO guest(String store, String session, String access, String buyer);
}
