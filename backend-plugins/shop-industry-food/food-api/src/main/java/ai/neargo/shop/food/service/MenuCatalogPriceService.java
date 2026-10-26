package ai.neargo.shop.food.service;

import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Facts;
import ai.neargo.shop.food.dto.MenuCatalogPriceContracts.Query;

/**
 * Original FOOD pricing authority is distinct from channel connection and remote publication
 * authority.
 */
public interface MenuCatalogPriceService {
    /**
     * Older providers explicitly lack this evidence, never silently reuse legacy CN checkout
     * quotes.
     */
    default Facts capture(Query query) {
        throw BizException.of(ErrorCode.PRICING_OWNER_UNSUPPORTED);
    }
}
