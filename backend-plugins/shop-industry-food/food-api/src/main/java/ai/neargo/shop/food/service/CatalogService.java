package ai.neargo.shop.food.service;

import ai.neargo.shop.food.dto.CatalogQuery;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.CatalogVO;

/** Reads published restaurant display using resolved staff, public-store or original guest credentials. */
public interface CatalogService {
    CatalogVO read(String entity, String store, CatalogQuery query);
    /** Uses the original capacity-reserved cart time, never a caller supplied preview clock. */
    default CatalogVO scheduledCart(String entity, String store, String cartNo) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
    CatalogVO publicCatalog(String store, String fulfillment);
    CatalogVO guest(String store, String session, String access, String buyer);
}
