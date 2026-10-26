package ai.neargo.shop.commerce.service;

import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Facts;
import ai.neargo.shop.commerce.dto.ChannelCatalogAuthorityContracts.Query;
import ai.neargo.shop.common.BizException;
import ai.neargo.shop.common.ErrorCode;

/**
 * A missing original provider must never silently authorize a menu or activate a channel
 * connection.
 */
public interface ChannelCatalogAuthorityService {
    default Facts capture(Query query) {
        throw BizException.of(ErrorCode.NOT_FOUND);
    }
}
